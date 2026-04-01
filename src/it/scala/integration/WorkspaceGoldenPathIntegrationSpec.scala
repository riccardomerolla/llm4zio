package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.json.*
import zio.process.Command
import zio.stream.ZStream
import zio.test.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import app.control.FileService
import board.control.*
import board.entity.*
import llm4zio.core.{ LlmChunk, LlmError, LlmService, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ProjectId }
import workspace.control.{ AssignRunRequest, GitServiceLive, WorkspaceRunService }
import workspace.entity.*

import IntegrationFixtures.NoOpGovernancePolicyService

/** Integration test for the gateway workspace golden path.
  *
  * Tests the end-to-end flow without real AI providers by mocking [[LlmService]], [[WorkspaceRunService]], and
  * [[WorkspaceRepository]]. All file system operations (board structure, git commits, merges) execute against a real
  * temporary git repository so the test is fully re-runnable and self-cleaning.
  *
  * Golden path:
  *   1. Init a "Hello World" Scala git repository (source fixture)
  *   2. Init the board structure (.board/)
  *   3. Plan two issues via [[IssueCreationWizard]] (LLM is mocked)
  *      - add-greeting-param (high priority, no dependencies)
  *      - add-language-param (medium priority, blocked by add-greeting-param)
  *   4. Move issues to Todo
  *   5. Dispatch cycle 1 → only add-greeting-param dispatched (dependency guard) Stub [[WorkspaceRunService]] creates a
  *      real feature branch
  *   6. Complete add-greeting-param → merges feature branch → issue moves to Done
  *   7. Dispatch cycle 2 → add-language-param now unblocked
  *   8. Complete add-language-param → merges → Done
  *   9. Assert: both issues Done, git log contains merge commits
  */
object WorkspaceGoldenPathIntegrationSpec extends ZIOSpecDefault:

  // ── Git helpers ────────────────────────────────────────────────────────────

  private def gitRun(cwd: Path, args: String*): Task[Unit] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string.unit

  private def gitOutput(cwd: Path, args: String*): Task[String] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string

  private def deleteRecursively(path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val stream = JFiles.walk(path)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).forEach { p =>
          val _ = JFiles.deleteIfExists(p)
        }
      finally stream.close()
    }.unit

  // ── Fixture: isolated Hello World git repository ───────────────────────────

  private def initHelloWorldRepo: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      for
        dir <- ZIO.attempt(JFiles.createTempDirectory("golden-path-spec-"))
        // Use --initial-branch for deterministic branch name; fall back gracefully
        _   <- gitRun(dir, "git", "init", "--initial-branch=main")
                 .orElse(gitRun(dir, "git", "init") *> gitRun(dir, "git", "checkout", "-b", "main"))
        _   <- gitRun(dir, "git", "config", "user.name", "spec-user")
        _   <- gitRun(dir, "git", "config", "user.email", "spec@example.com")
        src  = dir.resolve("src")
        _   <- ZIO.attemptBlocking {
                 JFiles.createDirectories(src)
                 JFiles.writeString(
                   src.resolve("HelloWorld.scala"),
                   s"""|object HelloWorld:
                       |  def main(args: Array[String]): Unit =
                       |    println("Hello, World!")
                       |""".stripMargin,
                 )
               }
        _   <- gitRun(dir, "git", "add", ".")
        _   <- gitRun(dir, "git", "commit", "-m", "initial: Hello World")
      yield dir
    )(dir => deleteRecursively(dir).orDie)

  // ── Stub: LlmService ──────────────────────────────────────────────────────
  // Returns pre-canned JSON strings in order; [[executeStructured]] decodes them.

  private def stubLlm(jsonResponses: List[String]): UIO[LlmService] =
    Ref.make(jsonResponses).map { ref =>
      new LlmService:
        override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.ProviderError("executeWithTools not used in golden-path test"))

        override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          for
            raw    <- ref.modify { case h :: t => (h, t); case Nil => ("", Nil) }
            result <- ZIO
                        .fromEither(raw.fromJson[A])
                        .mapError(msg => LlmError.ParseError(msg, raw))
          yield result

        override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    }

  // ── Stub: WorkspaceRepository ──────────────────────────────────────────────
  // Returns a single in-memory workspace; run lookups return empty lists.

  final private class StubWorkspaceRepository(ws: Workspace) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(List(ws))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]    =
      list.map(_.filter(_.projectId == projectId))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       =
      ZIO.succeed(Option.when(id == ws.id)(ws))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  // ── Stub: WorkspaceRunService ──────────────────────────────────────────────
  // Simulates agent execution: creates a real git feature branch with a dummy
  // commit, returns a [[WorkspaceRun]] containing that branch name, and checks
  // back out to main so subsequent board operations stay on the right branch.

  final private class StubWorkspaceRunService(repoPath: Path) extends WorkspaceRunService:

    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      val branchName = s"feature/${req.issueRef}/work"
      for
        _  <- runGit("git", "checkout", "-b", branchName)
        _  <- ZIO
                .attemptBlocking(
                  JFiles.writeString(repoPath.resolve(s"${req.issueRef}-impl.txt"), s"Work done for ${req.issueRef}")
                )
                .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        _  <- runGit("git", "add", ".")
        _  <- runGit("git", "commit", "-m", s"impl: ${req.issueRef}")
        _  <- runGit("git", "checkout", "main")
        now = Instant.now()
      yield WorkspaceRun(
        id = s"run-${req.issueRef}",
        workspaceId = workspaceId,
        parentRunId = None,
        issueRef = req.issueRef,
        agentName = req.agentName,
        prompt = req.prompt,
        conversationId = s"conv-${req.issueRef}",
        worktreePath = repoPath.toString,
        branchName = branchName,
        status = RunStatus.Completed,
        attachedUsers = Set.empty,
        controllerUserId = None,
        createdAt = now,
        updatedAt = now,
      )

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "stub does not support continueRun"))

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Stub: ActivityHub ─────────────────────────────────────────────────────
  // No-op hub: events are published but not routed (the orchestrator's background
  // listener is not started when instantiating BoardOrchestratorLive directly).

  final private class NoOpActivityHub extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
    override def subscribe: UIO[Dequeue[ActivityEvent]]   =
      Queue.bounded[ActivityEvent](1).map(q => q: Dequeue[ActivityEvent])

  // ── Pre-canned LLM response ────────────────────────────────────────────────
  // JSON matching GeneratedIssueBatch: two issues with a one-way dependency.

  private val issueBatchJson: String =
    """|{
       |  "summary": "Add parameterisation to HelloWorld greeting",
       |  "issues": [
       |    {
       |      "id": "add-greeting-param",
       |      "title": "Add greeting parameter",
       |      "priority": "high",
       |      "requiredCapabilities": ["scala"],
       |      "blockedBy": [],
       |      "tags": ["feature"],
       |      "acceptanceCriteria": ["greet() accepts a name parameter"],
       |      "proofOfWork": ["unit test covers personalised greeting"],
       |      "body": "Refactor HelloWorld so greet() accepts a name parameter and prints a personalised message."
       |    },
       |    {
       |      "id": "add-language-param",
       |      "title": "Add language parameter",
       |      "priority": "medium",
       |      "requiredCapabilities": ["scala"],
       |      "blockedBy": ["add-greeting-param"],
       |      "tags": ["feature"],
       |      "acceptanceCriteria": ["greet() accepts a language parameter"],
       |      "proofOfWork": ["unit test covers language-specific greeting"],
       |      "body": "Extend greet() to accept a language parameter and return the greeting in that language."
       |    }
       |  ]
       |}""".stripMargin

  // ── Spec ──────────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("WorkspaceGoldenPathIntegrationSpec")(
      test("full golden path: plan issues, dispatch with dependency resolution, complete and merge") {
        ZIO.scoped {
          val workspaceId = "ws-golden-path-test"

          for
            repoPath     <- initHelloWorldRepo
            workspacePath = repoPath.toString

            // ── Build service graph (mix of real and stub services) ────────────
            git          = GitServiceLive()
            parser       = IssueMarkdownParserLive()
            locksRef    <- Ref.make(Map.empty[String, Semaphore])
            boardRepo    = BoardRepositoryFS(parser, git, locksRef)
            resolver    <- ZIO.service[BoardDependencyResolver].provide(BoardDependencyResolver.live)
            llm         <- stubLlm(List(issueBatchJson))
            fileService <- ZIO.service[FileService].provide(FileService.live)
            wizardState <- Ref.Synchronized.make(Map.empty[String, IssueCreationSession])
            wizard       = IssueCreationWizardLive(llm, boardRepo, parser, fileService, wizardState)

            testWorkspace = Workspace(
                              id = workspaceId,
                              projectId = ProjectId("test-project"),
                              name = "hello-world",
                              localPath = workspacePath,
                              defaultAgent = Some("codex"),
                              description = None,
                              enabled = true,
                              runMode = RunMode.Host,
                              cliTool = "codex",
                              createdAt = Instant.now(),
                              updatedAt = Instant.now(),
                            )
            wsRepo        = StubWorkspaceRepository(testWorkspace)
            runService    = StubWorkspaceRunService(repoPath)
            hub           = NoOpActivityHub()

            // BoardOrchestratorLive instantiated directly (no scoped fork of the
            // activity listener) — we drive completion explicitly via completeIssue.
            orchestrator = BoardOrchestratorLive(
                             boardRepository = boardRepo,
                             dependencyResolver = resolver,
                             workspaceRunService = runService,
                             workspaceRepository = wsRepo,
                             gitService = git,
                             activityHub = hub,
                             governancePolicyService = NoOpGovernancePolicyService,
                           )

            // ── Phase 1: Init board structure ──────────────────────────────────
            _ <- boardRepo.initBoard(workspacePath)

            // ── Phase 2: Plan issues via mocked LLM ───────────────────────────
            session <- wizard.startNaturalLanguage(
                         workspacePath,
                         "Add a greeting parameter and a language parameter to HelloWorld",
                       )
            created <- wizard.confirm(session.sessionId)

            // Move both issues from Backlog → Todo (ready for dispatch)
            _ <- ZIO.foreach(created.issueIds)(id =>
                   boardRepo.moveIssue(workspacePath, id, BoardColumn.Todo)
                 )

            // ── Phase 3: First dispatch cycle ─────────────────────────────────
            // add-greeting-param has no blockers → dispatched
            // add-language-param is blocked by add-greeting-param → skipped
            dispatch1  <- orchestrator.dispatchCycle(workspacePath)
            board1     <- boardRepo.readBoard(workspacePath)
            inProgress1 = board1.columns.getOrElse(BoardColumn.InProgress, Nil)

            // ── Phase 4: Complete first issue (simulated agent done) ───────────
            greetingId = BoardIssueId("add-greeting-param")
            _         <- orchestrator.completeIssue(
                           workspacePath,
                           greetingId,
                           success = true,
                           details = "greet(name: String) implemented with unit tests",
                         )
            _         <- orchestrator.approveIssue(workspacePath, greetingId)

            board2    <- boardRepo.readBoard(workspacePath)
            doneAfter1 = board2.columns.getOrElse(BoardColumn.Done, Nil)

            // ── Phase 5: Second dispatch cycle ────────────────────────────────
            // add-language-param is now unblocked (dependency is Done)
            dispatch2 <- orchestrator.dispatchCycle(workspacePath)

            // ── Phase 6: Complete second issue ────────────────────────────────
            languageId = BoardIssueId("add-language-param")
            _         <- orchestrator.completeIssue(
                           workspacePath,
                           languageId,
                           success = true,
                           details = "greet(name: String, language: String) implemented with unit tests",
                         )
            _         <- orchestrator.approveIssue(workspacePath, languageId)

            // ── Phase 7: Collect final state for assertions ────────────────────
            boardFinal <- boardRepo.readBoard(workspacePath)
            doneFinal   = boardFinal.columns.getOrElse(BoardColumn.Done, Nil)
            gitLog     <- gitOutput(repoPath, "git", "log", "--oneline").orDie
          yield assertTrue(
            // LLM generated two issues
            created.issueIds.size == 2,
            // Dispatch cycle 1: only add-greeting-param (other is blocked)
            dispatch1.dispatchedIssueIds.size == 1,
            dispatch1.dispatchedIssueIds.head.value == "add-greeting-param",
            dispatch1.skippedIssueIds.isEmpty,
            // add-greeting-param moved to InProgress with a branch name
            inProgress1.size == 1,
            inProgress1.head.frontmatter.branchName.isDefined,
            // add-greeting-param completed and moved to Done
            doneAfter1.exists(_.frontmatter.id.value == "add-greeting-param"),
            doneAfter1.head.frontmatter.completedAt.isDefined,
            // Dispatch cycle 2: add-language-param now unblocked
            dispatch2.dispatchedIssueIds.size == 1,
            dispatch2.dispatchedIssueIds.head.value == "add-language-param",
            // Final board: both issues Done
            doneFinal.size == 2,
            doneFinal.exists(_.frontmatter.id.value == "add-greeting-param"),
            doneFinal.exists(_.frontmatter.id.value == "add-language-param"),
            doneFinal.forall(_.frontmatter.completedAt.isDefined),
            // Git log contains board merge commits for both issues
            gitLog.contains("[board] Merge issue add-greeting-param"),
            gitLog.contains("[board] Merge issue add-language-param"),
            // No issues remain in active columns
            boardFinal.columns.getOrElse(BoardColumn.InProgress, Nil).isEmpty,
            boardFinal.columns.getOrElse(BoardColumn.Todo, Nil).isEmpty,
          )
        }
      }
    )
