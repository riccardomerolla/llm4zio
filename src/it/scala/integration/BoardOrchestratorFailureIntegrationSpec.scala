package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.process.Command
import zio.test.*

import board.control.*
import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.control.{ AssignRunRequest, GitServiceLive, WorkspaceRunService }
import workspace.entity.*

import IntegrationFixtures.*

/** Integration tests for [[BoardOrchestratorLive]] failure modes.
  *
  * Test 1: When the run service fails on [[WorkspaceRunService.assign]], the orchestrator compensates (moves the issue
  * back to Todo) so a later dispatch cycle with a working service succeeds.
  *
  * Test 2: When [[BoardOrchestrator.completeIssue]] is called with `success=true` but the run service left the repo on
  * the feature branch (not main), `ensureMainBranch` fails with a ConcurrencyConflict. The caller then checks out main
  * and calls `completeIssue(false)` to recover the issue to Backlog with a Rework transient state.
  */
object BoardOrchestratorFailureIntegrationSpec extends ZIOSpecDefault:

  private val workspaceId = "ws-failure-spec"

  // ── Stub: always-failing run service ─────────────────────────────────────────

  final private class StubFailingRunService extends WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.WorktreeError(s"simulated assign failure for ${req.issueRef}"))

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "StubFailing: continueRun not supported"))

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Stub: succeeding run service ──────────────────────────────────────────────

  final private class StubSucceedingRunService(repoPath: Path) extends WorkspaceRunService:

    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      val branchName = s"feature/${req.issueRef}/work"
      for
        _  <- runGit("git", "checkout", "-b", branchName)
        _  <- ZIO
                .attemptBlocking(
                  JFiles.writeString(
                    repoPath.resolve(s"${req.issueRef}-impl.txt"),
                    s"Work done for ${req.issueRef}",
                  )
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
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "StubSucceeding: continueRun not supported"))

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Stub: conflicting run service ─────────────────────────────────────────────
  // Creates a feature branch and intentionally stays on it (does NOT checkout main).
  // This guarantees that completeIssue(true) fails at ensureMainBranch with a
  // BoardError.ConcurrencyConflict, exercising the error-recovery path reliably
  // without depending on zio-process exit-code propagation for `git merge`.

  final private class StubConflictingRunService(repoPath: Path) extends WorkspaceRunService:

    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      val branchName = s"feature/${req.issueRef}/conflict"
      val mainFile   = repoPath.resolve("src").resolve("HelloWorld.scala")
      for
        // Create feature branch and commit some work — intentionally stay on it
        _  <- runGit("git", "checkout", "-b", branchName)
        _  <- ZIO
                .attemptBlocking(JFiles.writeString(mainFile, "object HelloWorld { def greeting = \"Hi!\" }\n"))
                .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        _  <- runGit("git", "add", ".")
        _  <- runGit("git", "commit", "-m", s"feature: ${req.issueRef}")
        // NOTE: we deliberately do NOT checkout main here; the orchestrator sees
        // current branch != 'main' and fails ensureMainBranch on completeIssue(true).
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
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "StubConflicting: continueRun not supported"))

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Helper: build orchestrator ────────────────────────────────────────────────

  private def buildOrchestrator(
    repoPath: Path,
    runService: WorkspaceRunService,
  ): ZIO[Any, Nothing, (BoardOrchestratorLive, BoardRepositoryFS)] =
    val git    = GitServiceLive()
    val parser = IssueMarkdownParserLive()
    for
      boardRepo   <- boardRepoFor(git, parser)
      resolver    <- ZIO.service[BoardDependencyResolver].provide(BoardDependencyResolver.live)
      wsRepo       = StubWorkspaceRepository(minimalWorkspace(workspaceId, repoPath))
      hub          = NoOpActivityHub()
      orchestrator = BoardOrchestratorLive(
                       boardRepository = boardRepo,
                       dependencyResolver = resolver,
                       workspaceRunService = runService,
                       workspaceRepository = wsRepo,
                       gitService = git,
                       activityHub = hub,
                       governancePolicyService = NoOpGovernancePolicyService,
                     )
    yield (orchestrator, boardRepo)

  // ── Helper: create an issue in Backlog and move it to Todo ────────────────────

  private def createTodoIssue(
    boardRepo: BoardRepositoryFS,
    workspacePath: String,
    id: String,
  ): IO[BoardError, BoardIssue] =
    val fm    = IssueFrontmatter(
      id = BoardIssueId(id),
      title = s"Test issue $id",
      priority = IssuePriority.High,
      assignedAgent = None,
      requiredCapabilities = List("scala"),
      blockedBy = Nil,
      tags = Nil,
      acceptanceCriteria = List(s"$id is implemented"),
      estimate = None,
      proofOfWork = Nil,
      transientState = TransientState.None,
      branchName = None,
      failureReason = None,
      completedAt = None,
      createdAt = Instant.now(),
    )
    val issue = BoardIssue(
      frontmatter = fm,
      body = s"Implement $id",
      column = BoardColumn.Backlog,
      directoryPath = "",
    )
    for
      _     <- boardRepo.createIssue(workspacePath, BoardColumn.Backlog, issue)
      moved <- boardRepo.moveIssue(workspacePath, BoardIssueId(id), BoardColumn.Todo)
    yield moved

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BoardOrchestratorFailureIntegrationSpec")(
      test("run assign failure: dispatch compensates and retry on next cycle succeeds") {
        ZIO.scoped {
          for
            repoPath     <- initGitRepo
            workspacePath = repoPath.toString

            // ── Build orchestrator with failing run service ──────────────────
            (orchFail, boardRepo) <- buildOrchestrator(repoPath, StubFailingRunService())
            _                     <- boardRepo.initBoard(workspacePath)
            issueId                = BoardIssueId("fail-retry-issue")
            _                     <- createTodoIssue(boardRepo, workspacePath, issueId.value)

            // ── Cycle 1: dispatch fails, compensation moves issue back to Todo ─
            dispatch1 <- orchFail.dispatchCycle(workspacePath)
            board1    <- boardRepo.readBoard(workspacePath)
            todoAfter1 = board1.columns.getOrElse(BoardColumn.Todo, Nil)
            ipAfter1   = board1.columns.getOrElse(BoardColumn.InProgress, Nil)

            // ── Build orchestrator with succeeding run service ────────────────
            (orchOk, _) <- buildOrchestrator(repoPath, StubSucceedingRunService(repoPath))

            // ── Cycle 2: dispatch succeeds ────────────────────────────────────
            dispatch2  <- orchOk.dispatchCycle(workspacePath)
            _          <- orchOk.completeIssue(workspacePath, issueId, success = true, details = "done in retry")
            _          <- orchOk.approveIssue(workspacePath, issueId)
            boardFinal <- boardRepo.readBoard(workspacePath)
            doneFinal   = boardFinal.columns.getOrElse(BoardColumn.Done, Nil)
          yield assertTrue(
            // Cycle 1: issue was skipped (assign failed); compensation moved it to Todo
            dispatch1.dispatchedIssueIds.isEmpty,
            dispatch1.skippedIssueIds.size == 1,
            dispatch1.skippedIssueIds.head.value == issueId.value,
            todoAfter1.exists(_.frontmatter.id == issueId),
            todoAfter1.head.frontmatter.failureReason.isDefined,
            ipAfter1.isEmpty,
            // Cycle 2: issue successfully dispatched
            dispatch2.dispatchedIssueIds.size == 1,
            dispatch2.dispatchedIssueIds.head.value == issueId.value,
            // Final: issue in Done
            doneFinal.size == 1,
            doneFinal.head.frontmatter.id == issueId,
          )
        }
      },
      test("merge conflict: completeIssue(true) fails; abort then completeIssue(false) recovers to Backlog") {
        ZIO.scoped {
          for
            repoPath     <- initGitRepo
            workspacePath = repoPath.toString

            (orchConflict, boardRepo) <- buildOrchestrator(repoPath, StubConflictingRunService(repoPath))
            _                         <- boardRepo.initBoard(workspacePath)
            issueId                    = BoardIssueId("merge-conflict-issue")
            _                         <- createTodoIssue(boardRepo, workspacePath, issueId.value)

            // ── Dispatch: branch created; stays on feature branch ────────────
            dispatch <- orchConflict.dispatchCycle(workspacePath)

            // ── completeIssue(true) now succeeds (moves to Review; no branch check) ─
            _ <- orchConflict.completeIssue(workspacePath, issueId, success = true, details = "")

            // ── approveIssue must fail: we are on feature branch, not main ────
            approveResult <- orchConflict.approveIssue(workspacePath, issueId).either

            // ── Check out main so the repo is in a known-good state for recovery ─
            _ <- gitRun(repoPath, "git", "checkout", "main")

            // ── completeIssue(false) recovers: issue goes to Backlog with Rework
            _ <- orchConflict.completeIssue(
                   workspacePath,
                   issueId,
                   success = false,
                   details = "merge conflict detected",
                 )

            boardFinal <- boardRepo.readBoard(workspacePath)
            backlog     = boardFinal.columns.getOrElse(BoardColumn.Backlog, Nil)
          yield assertTrue(
            // Dispatch succeeded
            dispatch.dispatchedIssueIds.size == 1,
            dispatch.dispatchedIssueIds.head.value == issueId.value,
            // approveIssue failed (not on main branch)
            approveResult.isLeft,
            // Issue is in Backlog with Rework transient state and failureReason
            backlog.size == 1,
            backlog.head.frontmatter.id == issueId,
            backlog.head.frontmatter.transientState match
              case TransientState.Rework(_, _) => true
              case _                           =>
                false
            ,
            backlog.head.frontmatter.failureReason.contains("merge conflict detected"),
          )
        }
      },
    )
