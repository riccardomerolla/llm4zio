# Integration Tests — Orchestration Failure, Concurrency, Analysis Pipeline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five new integration tests in three files covering orchestration failure modes, dispatch-level concurrency, and the analysis pipeline — all using mocked LLM/run services against a real temporary git repository.

**Architecture:** Each spec file is self-contained; shared git/repo setup helpers live in `IntegrationFixtures`. Stubs are defined as private inner classes or objects within each spec file. `BoardOrchestratorLive` is instantiated directly (no background listener); real git operations run in a `ZIO.acquireRelease`-managed temp dir that is deleted on scope exit.

**Tech Stack:** Scala 3.5.2, ZIO 2.1.24, ZIO Test (`ZIOSpecDefault`, `suite`, `test`, `assertTrue`), `zio.process.Command`, `java.nio.file.{Files, Path}`.

---

## File Map

| Action | Path | Purpose |
|---|---|---|
| Create | `src/it/scala/integration/IntegrationFixtures.scala` | Shared git repo init, board repo builder, LLM stub, workspace builder, stubs |
| Create | `src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala` | 2 failure-mode tests |
| Create | `src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala` | 2 concurrency tests |
| Create | `src/it/scala/integration/AnalysisPipelineIntegrationSpec.scala` | 1 analysis pipeline test |
| Reference | `src/it/scala/integration/WorkspaceGoldenPathIntegrationSpec.scala` | Existing pattern to follow |

---

## Task 1: `IntegrationFixtures` — shared helpers

**Files:**
- Create: `src/it/scala/integration/IntegrationFixtures.scala`

- [ ] **Step 1: Create the fixtures file**

```scala
package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.json.*
import zio.process.Command
import zio.stream.ZStream

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import app.control.FileService
import board.control.{ BoardRepositoryFS, IssueMarkdownParserLive }
import llm4zio.core.{ LlmChunk, LlmError, LlmService, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import workspace.control.GitServiceLive
import workspace.entity.*

object IntegrationFixtures:

  // ── Git helpers ────────────────────────────────────────────────────────────

  def gitRun(cwd: Path, args: String*): Task[Unit] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string.unit

  def gitOutput(cwd: Path, args: String*): Task[String] =
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

  // ── Shared git repo fixture ────────────────────────────────────────────────
  // acquireRelease: creates a temp dir, git-inits with main branch,
  // writes src/HelloWorld.scala, initial commit. Deletes on scope exit.

  val initGitRepo: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      for
        dir <- ZIO.attempt(JFiles.createTempDirectory("it-spec-"))
        _   <- gitRun(dir, "git", "init", "--initial-branch=main")
                 .orElse(gitRun(dir, "git", "init") *> gitRun(dir, "git", "checkout", "-b", "main"))
        _   <- gitRun(dir, "git", "config", "user.name", "spec-user")
        _   <- gitRun(dir, "git", "config", "user.email", "spec@example.com")
        src  = dir.resolve("src")
        _   <- ZIO.attemptBlocking {
                 JFiles.createDirectories(src)
                 JFiles.writeString(
                   src.resolve("HelloWorld.scala"),
                   """|object HelloWorld:
                      |  def main(args: Array[String]): Unit =
                      |    println("Hello, World!")
                      |""".stripMargin,
                 )
               }
        _   <- gitRun(dir, "git", "add", ".")
        _   <- gitRun(dir, "git", "commit", "-m", "initial: Hello World")
      yield dir
    )(dir => deleteRecursively(dir).orDie)

  // ── Board repo builder ─────────────────────────────────────────────────────

  def boardRepoFor(git: GitServiceLive, parser: IssueMarkdownParserLive): UIO[BoardRepositoryFS] =
    Ref.make(Map.empty[String, Semaphore]).map(ref => BoardRepositoryFS(parser, git, ref))

  // ── LLM stub ──────────────────────────────────────────────────────────────
  // Ref-backed queue: executeStructured pops and decodes pre-canned JSON strings.

  def stubLlm(jsonResponses: List[String]): UIO[LlmService] =
    Ref.make(jsonResponses).map { ref =>
      new LlmService:
        override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.ProviderError("executeWithTools not used in integration test"))

        override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          for
            raw    <- ref.modify { case h :: t => (h, t); case Nil => ("", Nil) }
            result <- ZIO
                        .fromEither(raw.fromJson[A])
                        .mapError(msg => LlmError.ParseError(msg, raw))
          yield result

        override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    }

  // ── Workspace builder ──────────────────────────────────────────────────────

  def minimalWorkspace(id: String, path: Path): Workspace =
    Workspace(
      id           = id,
      name         = "test-workspace",
      localPath    = path.toString,
      defaultAgent = Some("codex"),
      description  = None,
      enabled      = true,
      runMode      = RunMode.Host,
      cliTool      = "codex",
      createdAt    = Instant.now(),
      updatedAt    = Instant.now(),
    )

  // ── StubWorkspaceRepository ────────────────────────────────────────────────
  // Returns a single in-memory workspace; all run lookups return Nil.

  final class StubWorkspaceRepository(ws: Workspace) extends WorkspaceRepository:
    import shared.errors.PersistenceError
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                        = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                      = ZIO.succeed(List(ws))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                        =
      ZIO.succeed(Option.when(id == ws.id)(ws))
    override def delete(id: String): IO[PersistenceError, Unit]                                  = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                 = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]         = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]  = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                  = ZIO.succeed(None)

  // ── NoOpActivityHub ────────────────────────────────────────────────────────

  final class NoOpActivityHub extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit]      = ZIO.unit
    override def subscribe: UIO[Dequeue[ActivityEvent]]        =
      Queue.bounded[ActivityEvent](1).map(q => q: Dequeue[ActivityEvent])
```

- [ ] **Step 2: Compile to verify no import errors**

```bash
sbt "It/compile"
```

Expected: compiles cleanly (or only errors in other files, not IntegrationFixtures).

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/IntegrationFixtures.scala
git commit -m "feat(it): add IntegrationFixtures shared helpers"
```

---

## Task 2: `BoardOrchestratorFailureIntegrationSpec` — stubs and test scaffolding

**Files:**
- Create: `src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala`

- [ ] **Step 1: Create the file with stubs and empty test suite**

```scala
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

object BoardOrchestratorFailureIntegrationSpec extends ZIOSpecDefault:

  // ── Stub: always-failing run service ─────────────────────────────────────

  private final class StubFailingRunService extends WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.WorktreeError("agent failed to start"))
    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "stub"))
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Stub: succeeding run service (creates a real feature branch) ──────────

  private final class StubSucceedingRunService(repoPath: Path) extends WorkspaceRunService:
    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      val branchName = s"feature/${req.issueRef}/work"
      for
        _ <- runGit("git", "checkout", "-b", branchName)
        _ <- ZIO.attemptBlocking(
               JFiles.writeString(repoPath.resolve(s"${req.issueRef}-impl.txt"), s"Work for ${req.issueRef}")
             ).mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        _ <- runGit("git", "add", ".")
        _ <- runGit("git", "commit", "-m", s"impl: ${req.issueRef}")
        _ <- runGit("git", "checkout", "main")
        now = Instant.now()
      yield WorkspaceRun(
        id              = s"run-${req.issueRef}",
        workspaceId     = workspaceId,
        parentRunId     = None,
        issueRef        = req.issueRef,
        agentName       = req.agentName,
        prompt          = req.prompt,
        conversationId  = s"conv-${req.issueRef}",
        worktreePath    = repoPath.toString,
        branchName      = branchName,
        status          = RunStatus.Completed,
        attachedUsers   = Set.empty,
        controllerUserId = None,
        createdAt       = now,
        updatedAt       = now,
      )

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "stub"))
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Stub: conflicting run service (sets up merge conflict scenario) ────────

  private final class StubConflictingRunService(repoPath: Path) extends WorkspaceRunService:
    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      val branchName = s"feature/${req.issueRef}/conflict-work"
      for
        _ <- runGit("git", "checkout", "-b", branchName)
        _ <- ZIO.attemptBlocking(
               JFiles.writeString(
                 repoPath.resolve("src").resolve("HelloWorld.scala"),
                 """|object HelloWorld:
                    |  def main(args: Array[String]): Unit =
                    |    println("Hello from feature!")
                    |""".stripMargin,
               )
             ).mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        _ <- runGit("git", "add", ".")
        _ <- runGit("git", "commit", "-m", s"feature: ${req.issueRef}")
        _ <- runGit("git", "checkout", "main")
        now = Instant.now()
      yield WorkspaceRun(
        id              = s"run-${req.issueRef}",
        workspaceId     = workspaceId,
        parentRunId     = None,
        issueRef        = req.issueRef,
        agentName       = req.agentName,
        prompt          = req.prompt,
        conversationId  = s"conv-${req.issueRef}",
        worktreePath    = repoPath.toString,
        branchName      = branchName,
        status          = RunStatus.Completed,
        attachedUsers   = Set.empty,
        controllerUserId = None,
        createdAt       = now,
        updatedAt       = now,
      )

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "stub"))
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Helper: build orchestrator ────────────────────────────────────────────

  private def buildOrchestrator(
    repoPath: Path,
    runService: WorkspaceRunService,
  ): ZIO[Any, Nothing, (BoardOrchestratorLive, BoardRepositoryFS, String)] =
    for
      git          <- ZIO.succeed(GitServiceLive())
      parser        = IssueMarkdownParserLive()
      boardRepo    <- boardRepoFor(git, parser)
      resolver     <- ZIO.service[BoardDependencyResolver].provide(BoardDependencyResolver.live)
      ws            = minimalWorkspace("ws-failure-test", repoPath)
      wsRepo        = StubWorkspaceRepository(ws)
      hub           = NoOpActivityHub()
      orchestrator  = BoardOrchestratorLive(
                        boardRepository     = boardRepo,
                        dependencyResolver  = resolver,
                        workspaceRunService = runService,
                        workspaceRepository = wsRepo,
                        gitService          = git,
                        activityHub         = hub,
                      )
    yield (orchestrator, boardRepo, repoPath.toString)

  // ── Helper: create and move a single issue to Todo ────────────────────────

  private def createTodoIssue(
    boardRepo: BoardRepositoryFS,
    workspacePath: String,
    id: String,
  ): IO[BoardError, BoardIssue] =
    val issue = BoardIssue(
      frontmatter = IssueFrontmatter(
        id                   = BoardIssueId(id),
        title                = s"Issue $id",
        priority             = IssuePriority.Medium,
        assignedAgent        = None,
        requiredCapabilities = List("scala"),
        blockedBy            = Nil,
        tags                 = List("test"),
        acceptanceCriteria   = List("done"),
        estimate             = None,
        proofOfWork          = List("proof"),
        transientState       = TransientState.None,
        branchName           = None,
        failureReason        = None,
        completedAt          = None,
        createdAt            = Instant.now(),
      ),
      body          = s"# Issue $id\n\nTest issue.",
      column        = BoardColumn.Backlog,
      directoryPath = "",
    )
    for
      _     <- boardRepo.createIssue(workspacePath, BoardColumn.Backlog, issue)
      moved <- boardRepo.moveIssue(workspacePath, BoardIssueId(id), BoardColumn.Todo)
    yield moved

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BoardOrchestratorFailureIntegrationSpec")(
      // Tests will be added in Tasks 3 and 4
    )
```

- [ ] **Step 2: Compile to verify the stubs and helpers compile**

```bash
sbt "It/compile"
```

Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala
git commit -m "feat(it): add BoardOrchestratorFailureIntegrationSpec stubs and helpers"
```

---

## Task 3: Failure test 1 — run failure compensation and retry

**Files:**
- Modify: `src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala`

- [ ] **Step 1: Add test 1 to the suite**

Replace the empty `suite(...)` with:

```scala
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BoardOrchestratorFailureIntegrationSpec")(
      test("run failure: dispatch compensates to Todo with failureReason; retry with succeeding service completes") {
        ZIO.scoped {
          val issueId = BoardIssueId("task-retry")
          for
            repoPath                         <- initGitRepo
            (orch1, boardRepo, wsPath)       <- buildOrchestrator(repoPath, StubFailingRunService())
            _                                <- boardRepo.initBoard(wsPath)
            _                                <- createTodoIssue(boardRepo, wsPath, "task-retry")

            // Cycle 1: assign fails → compensation → issue back in Todo with failureReason
            dispatch1                        <- orch1.dispatchCycle(wsPath)
            boardAfterFail                   <- boardRepo.readBoard(wsPath)
            todoAfterFail                     = boardAfterFail.columns.getOrElse(BoardColumn.Todo, Nil)
            failedIssue                      <- boardRepo.readIssue(wsPath, issueId)

            // Swap in succeeding run service and new orchestrator
            (orch2, _, _)                    <- buildOrchestrator(repoPath, StubSucceedingRunService(repoPath))

            // Cycle 2: succeeds → issue dispatched
            dispatch2                        <- orch2.dispatchCycle(wsPath)

            // Complete the issue
            _                                <- orch2.completeIssue(wsPath, issueId, success = true, details = "done")

            boardFinal                       <- boardRepo.readBoard(wsPath)
            doneFinal                         = boardFinal.columns.getOrElse(BoardColumn.Done, Nil)

          yield assertTrue(
            // Cycle 1: skip (assign failed)
            dispatch1.skippedIssueIds.size == 1,
            dispatch1.dispatchedIssueIds.isEmpty,
            // Issue compensated back to Todo
            todoAfterFail.exists(_.frontmatter.id == issueId),
            // failureReason recorded
            failedIssue.frontmatter.failureReason.isDefined,
            // Cycle 2: dispatched
            dispatch2.dispatchedIssueIds.size == 1,
            dispatch2.dispatchedIssueIds.head == issueId,
            // Issue completed and in Done
            doneFinal.exists(_.frontmatter.id == issueId),
            doneFinal.head.frontmatter.completedAt.isDefined,
          )
        }
      },
      // Test 2 will be added in Task 4
    )
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
sbt "It/testOnly integration.BoardOrchestratorFailureIntegrationSpec"
```

Expected: 1 test PASSED.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala
git commit -m "feat(it): add failure test 1 — run failure compensation and retry"
```

---

## Task 4: Failure test 2 — merge conflict recovery

**Files:**
- Modify: `src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala`

- [ ] **Step 1: Add test 2 to the suite**

Add after the first test (replacing the `// Test 2 will be added in Task 4` comment):

```scala
      test("merge conflict: completeIssue(true) fails; git abort then completeIssue(false) recovers to Backlog") {
        ZIO.scoped {
          val issueId = BoardIssueId("task-conflict")
          for
            repoPath                   <- initGitRepo
            (orch, boardRepo, wsPath)  <- buildOrchestrator(repoPath, StubConflictingRunService(repoPath))
            _                          <- boardRepo.initBoard(wsPath)
            _                          <- createTodoIssue(boardRepo, wsPath, "task-conflict")

            // Dispatch: StubConflictingRunService creates feature branch modifying HelloWorld.scala
            dispatch1                  <- orch.dispatchCycle(wsPath)

            // Now make a conflicting commit on main (same file, same line)
            _                          <- ZIO.attemptBlocking(
                                            JFiles.writeString(
                                              repoPath.resolve("src").resolve("HelloWorld.scala"),
                                              """|object HelloWorld:
                                                 |  def main(args: Array[String]): Unit =
                                                 |    println("Hello from main!")
                                                 |""".stripMargin,
                                            )
                                          )
            _                          <- gitRun(repoPath, "git", "add", ".")
            _                          <- gitRun(repoPath, "git", "commit", "-m", "main: conflicting change")

            // completeIssue(true) should fail with GitOperationFailed due to merge conflict
            mergeResult                <- orch.completeIssue(wsPath, issueId, success = true, details = "done").exit

            // Abort the partial merge — must happen before completeIssue(false) because
            // ensureMainBranch checks we are on main; the merge-abort restores clean main state
            _                          <- gitRun(repoPath, "git", "merge", "--abort")

            boardAfterConflict         <- boardRepo.readBoard(wsPath)
            inProgressAfterConflict     = boardAfterConflict.columns.getOrElse(BoardColumn.InProgress, Nil)

            // completeIssue(false) → moves to Backlog with TransientState.Rework
            _                          <- orch.completeIssue(wsPath, issueId, success = false, details = "merge conflict - needs rework")

            boardFinal                 <- boardRepo.readBoard(wsPath)
            backlogFinal                = boardFinal.columns.getOrElse(BoardColumn.Backlog, Nil)
            recoveredIssue             <- boardRepo.readIssue(wsPath, issueId)

          yield assertTrue(
            // Dispatch succeeded
            dispatch1.dispatchedIssueIds.size == 1,
            // completeIssue(true) failed (merge conflict)
            mergeResult.isFailure,
            // Issue still in InProgress after failed merge attempt (before abort + recovery)
            inProgressAfterConflict.exists(_.frontmatter.id == issueId),
            // After recovery: issue in Backlog
            backlogFinal.exists(_.frontmatter.id == issueId),
            // TransientState.Rework set
            recoveredIssue.frontmatter.transientState match
              case TransientState.Rework(_, _) => true
              case _                           => false
            ,
            // failureReason set
            recoveredIssue.frontmatter.failureReason.isDefined,
            // completedAt NOT set
            recoveredIssue.frontmatter.completedAt.isEmpty,
          )
        }
      },
```

- [ ] **Step 2: Run both failure tests**

```bash
sbt "It/testOnly integration.BoardOrchestratorFailureIntegrationSpec"
```

Expected: 2 tests PASSED.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/BoardOrchestratorFailureIntegrationSpec.scala
git commit -m "feat(it): add failure test 2 — merge conflict recovery to Backlog with Rework"
```

---

## Task 5: `DispatchConcurrencyIntegrationSpec` — stubs and scaffolding

**Files:**
- Create: `src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala`

- [ ] **Step 1: Create file with `PooledStubRunService` and helper**

```scala
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

object DispatchConcurrencyIntegrationSpec extends ZIOSpecDefault:

  // ── PooledStubRunService ───────────────────────────────────────────────────
  // sem.tryAcquire gates dispatch; sem.release must be called manually by the
  // test because listRunsByIssueRef returns Nil → cleanupLatestRun is a no-op.

  private final class PooledStubRunService(
    repoPath: Path,
    sem: Semaphore,
    acquireDelay: Duration = Duration.Zero,
  ) extends WorkspaceRunService:
    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      sem.tryAcquire.flatMap {
        case false =>
          ZIO.fail(WorkspaceError.WorktreeError("agent pool exhausted"))
        case true  =>
          val branchName = s"feature/${req.issueRef}/work"
          for
            _ <- ZIO.sleep(acquireDelay)
            _ <- runGit("git", "checkout", "-b", branchName)
            _ <- ZIO.attemptBlocking(
                   JFiles.writeString(
                     repoPath.resolve(s"${req.issueRef}-work.txt"),
                     s"Work for ${req.issueRef}",
                   )
                 ).mapError(e => WorkspaceError.WorktreeError(e.getMessage))
            _ <- runGit("git", "add", ".")
            _ <- runGit("git", "commit", "-m", s"work: ${req.issueRef}")
            _ <- runGit("git", "checkout", "main")
            now = Instant.now()
          yield WorkspaceRun(
            id               = s"run-${req.issueRef}",
            workspaceId      = workspaceId,
            parentRunId      = None,
            issueRef         = req.issueRef,
            agentName        = req.agentName,
            prompt           = req.prompt,
            conversationId   = s"conv-${req.issueRef}",
            worktreePath     = repoPath.toString,
            branchName       = branchName,
            status           = RunStatus.Completed,
            attachedUsers    = Set.empty,
            controllerUserId = None,
            createdAt        = now,
            updatedAt        = now,
          )
      }

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String] = None,
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "stub"))

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ZIO.unit

  // ── Helper: build orchestrator ────────────────────────────────────────────

  private def buildOrchestrator(
    repoPath: Path,
    runService: WorkspaceRunService,
  ): ZIO[Any, Nothing, (BoardOrchestratorLive, BoardRepositoryFS, String)] =
    for
      git         <- ZIO.succeed(GitServiceLive())
      parser       = IssueMarkdownParserLive()
      boardRepo   <- boardRepoFor(git, parser)
      resolver    <- ZIO.service[BoardDependencyResolver].provide(BoardDependencyResolver.live)
      ws           = minimalWorkspace("ws-concurrency-test", repoPath)
      wsRepo       = StubWorkspaceRepository(ws)
      hub          = NoOpActivityHub()
      orchestrator = BoardOrchestratorLive(
                       boardRepository     = boardRepo,
                       dependencyResolver  = resolver,
                       workspaceRunService = runService,
                       workspaceRepository = wsRepo,
                       gitService          = git,
                       activityHub         = hub,
                     )
    yield (orchestrator, boardRepo, repoPath.toString)

  // ── Helper: create N independent issues in Todo ────────────────────────────

  private def createTodoIssues(
    boardRepo: BoardRepositoryFS,
    wsPath: String,
    ids: List[String],
  ): IO[BoardError, Unit] =
    ZIO.foreachDiscard(ids) { id =>
      val issue = BoardIssue(
        frontmatter = IssueFrontmatter(
          id                   = BoardIssueId(id),
          title                = s"Issue $id",
          priority             = IssuePriority.Medium,
          assignedAgent        = None,
          requiredCapabilities = List("scala"),
          blockedBy            = Nil,
          tags                 = List("test"),
          acceptanceCriteria   = List("done"),
          estimate             = None,
          proofOfWork          = List("proof"),
          transientState       = TransientState.None,
          branchName           = None,
          failureReason        = None,
          completedAt          = None,
          createdAt            = Instant.now(),
        ),
        body          = s"# Issue $id",
        column        = BoardColumn.Backlog,
        directoryPath = "",
      )
      boardRepo.createIssue(wsPath, BoardColumn.Backlog, issue) *>
        boardRepo.moveIssue(wsPath, BoardIssueId(id), BoardColumn.Todo).unit
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DispatchConcurrencyIntegrationSpec")(
      // Tests will be added in Tasks 6 and 7
    )
```

- [ ] **Step 2: Compile**

```bash
sbt "It/compile"
```

Expected: compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala
git commit -m "feat(it): add DispatchConcurrencyIntegrationSpec stubs and helpers"
```

---

## Task 6: Concurrency test 1 — pool exhaustion skip-and-retry

**Files:**
- Modify: `src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala`

- [ ] **Step 1: Add test 1**

Replace the empty `suite(...)` with:

```scala
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DispatchConcurrencyIntegrationSpec")(
      test("pool exhaustion: 3 issues dispatched one-per-cycle across 4 sequential cycles") {
        ZIO.scoped {
          for
            repoPath              <- initGitRepo
            sem                   <- Semaphore.make(1)
            runService             = PooledStubRunService(repoPath, sem)
            (orch, boardRepo, wsPath) <- buildOrchestrator(repoPath, runService)
            _                     <- boardRepo.initBoard(wsPath)
            _                     <- createTodoIssues(boardRepo, wsPath, List("issue-a", "issue-b", "issue-c"))

            // Cycle 1: pool empty → A dispatched, B+C skipped (tryAcquire fails)
            cycle1                <- orch.dispatchCycle(wsPath)

            // Cycle 2: A is InProgress, pool still full → B+C skipped
            cycle2                <- orch.dispatchCycle(wsPath)

            // Complete A, release semaphore manually (cleanupLatestRun is no-op)
            _                     <- orch.completeIssue(wsPath, BoardIssueId("issue-a"), success = true, details = "done")
            _                     <- sem.release

            // Cycle 3: B dispatched, C skipped
            cycle3                <- orch.dispatchCycle(wsPath)

            // Complete B, release semaphore
            _                     <- orch.completeIssue(wsPath, BoardIssueId("issue-b"), success = true, details = "done")
            _                     <- sem.release

            // Cycle 4: C dispatched
            cycle4                <- orch.dispatchCycle(wsPath)
            _                     <- orch.completeIssue(wsPath, BoardIssueId("issue-c"), success = true, details = "done")
            _                     <- sem.release

            boardFinal            <- boardRepo.readBoard(wsPath)
            doneFinal              = boardFinal.columns.getOrElse(BoardColumn.Done, Nil)
            gitLog                <- gitOutput(repoPath, "git", "log", "--oneline").orDie

          yield assertTrue(
            // Cycle 1: A dispatched, B+C skipped
            cycle1.dispatchedIssueIds.map(_.value) == List("issue-a"),
            cycle1.skippedIssueIds.size == 2,
            // Cycle 2: nothing dispatched (pool full), B+C still skipped
            cycle2.dispatchedIssueIds.isEmpty,
            cycle2.skippedIssueIds.size == 2,
            // Cycle 3: B dispatched
            cycle3.dispatchedIssueIds.map(_.value) == List("issue-b"),
            // Cycle 4: C dispatched
            cycle4.dispatchedIssueIds.map(_.value) == List("issue-c"),
            // All three issues Done
            doneFinal.size == 3,
            doneFinal.map(_.frontmatter.id.value).toSet == Set("issue-a", "issue-b", "issue-c"),
            // Git log has 3 merge commits
            gitLog.linesIterator.count(_.contains("[board] Merge issue")) == 3,
            // Board clean
            boardFinal.columns.getOrElse(BoardColumn.Todo, Nil).isEmpty,
            boardFinal.columns.getOrElse(BoardColumn.InProgress, Nil).isEmpty,
          )
        }
      },
      // Test 2 will be added in Task 7
    )
```

- [ ] **Step 2: Run test 1**

```bash
sbt "It/testOnly integration.DispatchConcurrencyIntegrationSpec"
```

Expected: 1 test PASSED.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala
git commit -m "feat(it): add concurrency test 1 — pool exhaustion skip-and-retry across 4 cycles"
```

---

## Task 7: Concurrency test 2 — concurrent dispatch no double-dispatch

**Files:**
- Modify: `src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala`

- [ ] **Step 1: Add test 2**

Add after test 1 (replacing `// Test 2 will be added in Task 7`):

```scala
      test("concurrent dispatch cycles do not double-dispatch the same issue") {
        ZIO.scoped {
          for
            repoPath              <- initGitRepo
            sem                   <- Semaphore.make(2)
            // acquireDelay=20ms widens the concurrency window before branch creation
            runService             = PooledStubRunService(repoPath, sem, acquireDelay = 20.millis)
            (orch, boardRepo, wsPath) <- buildOrchestrator(repoPath, runService)
            _                     <- boardRepo.initBoard(wsPath)
            _                     <- createTodoIssues(boardRepo, wsPath, List("issue-x", "issue-y"))

            // Fire two dispatchCycle calls in parallel
            (result1, result2)    <- orch.dispatchCycle(wsPath).zipPar(orch.dispatchCycle(wsPath))

            allDispatched          = result1.dispatchedIssueIds ++ result2.dispatchedIssueIds

            boardFinal            <- boardRepo.readBoard(wsPath)

          yield assertTrue(
            // Primary: no issue dispatched by both cycles
            result1.dispatchedIssueIds.intersect(result2.dispatchedIssueIds).isEmpty,
            // Secondary: both issues dispatched exactly once in total
            allDispatched.distinct.size == 2,
            // Board: 2 InProgress, 0 Todo
            boardFinal.columns.getOrElse(BoardColumn.InProgress, Nil).size == 2,
            boardFinal.columns.getOrElse(BoardColumn.Todo, Nil).isEmpty,
          )
        }
      },
```

- [ ] **Step 2: Run both concurrency tests**

```bash
sbt "It/testOnly integration.DispatchConcurrencyIntegrationSpec"
```

Expected: 2 tests PASSED.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/DispatchConcurrencyIntegrationSpec.scala
git commit -m "feat(it): add concurrency test 2 — parallel dispatch does not double-dispatch issues"
```

---

## Task 8: `AnalysisPipelineIntegrationSpec`

**Files:**
- Create: `src/it/scala/integration/AnalysisPipelineIntegrationSpec.scala`

- [ ] **Step 1: Create the file**

```scala
package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentRepository }
import analysis.control.{ AnalysisAgentRunner, AnalysisAgentRunnerLive }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import app.control.FileService
import db.TaskRepository
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId }
import db.{ LegacyTypes, SettingRow }
import workspace.entity.Workspace

import IntegrationFixtures.*

object AnalysisPipelineIntegrationSpec extends ZIOSpecDefault:

  // ── StubAgentRepository ───────────────────────────────────────────────────
  // list() returns Nil; findByName("code-agent") returns a valid enabled Agent.

  private val codeAgent: Agent = Agent(
    id                = AgentId("code-agent"),
    name              = "code-agent",
    description       = "Code review agent",
    cliTool           = "codex",
    capabilities      = List("code-review"),
    defaultModel      = None,
    systemPrompt      = None,
    maxConcurrentRuns = 1,
    envVars           = Map.empty,
    timeout           = JavaDuration.ofMinutes(30),
    enabled           = true,
    createdAt         = Instant.now(),
    updatedAt         = Instant.now(),
  )

  private final class StubAgentRepository extends AgentRepository:
    import agent.entity.AgentEvent
    override def append(event: AgentEvent): IO[PersistenceError, Unit]              = ZIO.unit
    override def get(id: AgentId): IO[PersistenceError, Agent]                      =
      ZIO.fail(PersistenceError.NotFound(id.value))
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(Nil)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]      =
      ZIO.succeed(Option.when(name == "code-agent")(codeAgent))

  // ── StubAnalysisRepository ─────────────────────────────────────────────────
  // Concrete class; events Ref is accessible for assertion.

  private final class StubAnalysisRepository extends AnalysisRepository:
    val events: Ref[List[AnalysisEvent]] = zio.Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty))
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                   =
      events.update(_ :+ event)
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                  =
      ZIO.fail(PersistenceError.NotFound(id.value))
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]] =
      ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
      ZIO.succeed(Nil)

  // ── StubTaskRepository ─────────────────────────────────────────────────────
  // getSetting for CodeReviewAgentSettingKey returns "code-agent" → deterministic selection.

  private final class StubTaskRepository extends TaskRepository:
    import db.{ TaskRunRow, TaskReportRow, TaskArtifactRow }
    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                     = ZIO.succeed(0L)
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                     = ZIO.unit
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                 = ZIO.succeed(None)
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]  = ZIO.succeed(Nil)
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                            = ZIO.unit
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]              = ZIO.succeed(0L)
    override def getReport(id: Long): IO[PersistenceError, Option[TaskReportRow]]           = ZIO.succeed(None)
    override def getReportsByTask(id: Long): IO[PersistenceError, List[TaskReportRow]]      = ZIO.succeed(Nil)
    override def saveArtifact(a: TaskArtifactRow): IO[PersistenceError, Long]               = ZIO.succeed(0L)
    override def getArtifactsByTask(id: Long): IO[PersistenceError, List[TaskArtifactRow]]  = ZIO.succeed(Nil)
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                     = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]         =
      if key == AnalysisAgentRunner.CodeReviewAgentSettingKey
      then ZIO.succeed(Some(SettingRow("analysis.code-review.agent", "code-agent", Instant.now())))
      else ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]     = ZIO.unit
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]        = ZIO.unit

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisPipelineIntegrationSpec")(
      test("code review: analysis file written to disk, committed, and event persisted") {
        ZIO.scoped {
          val workspaceId = "ws-analysis-test"
          for
            repoPath          <- initGitRepo
            fileService       <- ZIO.service[FileService].provide(FileService.live)
            agentRepo          = StubAgentRepository()
            stubAnalysisRepo   = StubAnalysisRepository()
            taskRepo           = StubTaskRepository()
            wsRepo             = StubWorkspaceRepository(minimalWorkspace(workspaceId, repoPath))
            runner             = AnalysisAgentRunnerLive(
                                   workspaceRepository = wsRepo,
                                   agentRepository     = agentRepo,
                                   analysisRepository  = stubAnalysisRepo,
                                   taskRepository      = taskRepo,
                                   fileService         = fileService,
                                   llmPromptExecutor   = Some((_, agent, _) =>
                                                           ZIO.succeed(s"# Code Review\n\nMocked analysis by ${agent.name}.")
                                                         ),
                                 )

            doc               <- runner.runCodeReview(workspaceId)

            // Verify file on disk
            analysisFilePath   = repoPath.resolve(AnalysisAgentRunner.CodeReviewRelativePath)
            fileExists         = JFiles.exists(analysisFilePath)
            fileContent       <- ZIO.attemptBlocking(JFiles.readString(analysisFilePath))
            events            <- stubAnalysisRepo.events.get
            gitLog            <- gitOutput(repoPath, "git", "log", "--oneline").orDie

          yield assertTrue(
            doc.workspaceId == workspaceId,
            doc.analysisType == AnalysisType.CodeReview,
            fileExists,
            fileContent.contains("# Code Review"),
            events.nonEmpty,
            events.exists {
              case _: AnalysisEvent.AnalysisCreated => true
              case _                                => false
            },
            gitLog.contains("code-review"),
          )
        }
      }
    )
```

- [ ] **Step 2: Run the analysis pipeline test**

```bash
sbt "It/testOnly integration.AnalysisPipelineIntegrationSpec"
```

Expected: 1 test PASSED.

- [ ] **Step 3: Commit**

```bash
git add src/it/scala/integration/AnalysisPipelineIntegrationSpec.scala
git commit -m "feat(it): add AnalysisPipelineIntegrationSpec — code review file written, committed, event persisted"
```

---

## Task 9: Run all integration tests and final verification

- [ ] **Step 1: Run the full integration test suite**

```bash
sbt "It/testOnly integration.*"
```

Expected: all 6 tests PASSED (1 existing golden-path + 2 failure + 2 concurrency + 1 analysis).

- [ ] **Step 2: Verify no regressions in unit tests**

```bash
sbt test
```

Expected: all unit tests pass.

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat(it): complete integration test suite — failure, concurrency, and analysis pipeline"
```

---

## Troubleshooting

**`BoardError.ConcurrencyConflict` in failure tests:** `moveIssue` raises this when an issue is already in the target column. This is expected only during concurrent dispatch — if it appears in sequential tests, check that `dispatchCompensation` correctly moves the issue back to `Todo` before the next `dispatchCycle`.

**`ensureMainBranch` failure in merge conflict test:** `completeIssue(false)` checks `git branch --show-current == main`. If the test calls `completeIssue(false)` without first running `git merge --abort`, the repo is in a mid-merge state and this check fails. Ensure `gitRun(repoPath, "git", "merge", "--abort")` completes before calling `completeIssue(false)`.

**`StubAnalysisRepository.events` must use `Ref.unsafe.make`:** The `Ref` must be created outside of a ZIO effect scope to be accessible as a `val` on the class. The pattern `zio.Unsafe.unsafe(implicit u => Ref.unsafe.make(...))` is the correct idiom.

**`SettingRow` requires three fields:** `SettingRow(key, value, updatedAt)` — the third `updatedAt: Instant` argument is required. Use `Instant.now()` or `Instant.EPOCH`.

**`TaskRepository` interface:** Some methods (`getSettingsByPrefix`, `deleteSettingsByPrefix`) have default implementations in the trait — only the abstract methods need to be overridden in stubs.
