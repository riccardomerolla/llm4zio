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

/** Integration tests for dispatch-level concurrency in [[BoardOrchestratorLive]].
  *
  * Test 1 — Pool exhaustion skip-and-retry: a run service with a single slot allows only one issue to be dispatched per
  * cycle; subsequent cycles pick up the skipped issues once the slot is released.
  *
  * Test 2 — No double-dispatch: a second sequential dispatch cycle must not re-dispatch issues already in InProgress.
  * The `readyToDispatch` resolver only returns Todo issues, so InProgress issues are invisible to the next cycle.
  */
object DispatchConcurrencyIntegrationSpec extends ZIOSpecDefault:

  private val workspaceId = "ws-concurrency-spec"

  // ── PooledStubRunService ──────────────────────────────────────────────────────
  // slotsRef: atomic slot counter — 0 means the pool is exhausted.
  // tryAcquire decrements atomically; release increments.
  // dispatchedRef records each successfully started issueRef for assertion.

  final private class PooledStubRunService(
    repoPath: Path,
    slotsRef: Ref[Int],
    dispatchedRef: Ref[List[String]],
    acquireDelay: Duration = Duration.Zero,
    branchSuffix: Ref[Int],
  ) extends WorkspaceRunService:

    private def runGit(args: String*): IO[WorkspaceError, Unit] =
      Command(args.head, args.drop(1)*).workingDirectory(repoPath.toFile).string.unit
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      for
        acquired  <- slotsRef.modify { n =>
                       if n > 0 then (true, n - 1)
                       else (false, n)
                     }
        _         <- ZIO
                       .fail(WorkspaceError.WorktreeError(s"pool exhausted: no slots available for ${req.issueRef}"))
                       .when(!acquired)
        _         <- ZIO.sleep(acquireDelay).when(acquireDelay != Duration.Zero)
        suffix    <- branchSuffix.updateAndGet(_ + 1)
        branchName = s"feature/${req.issueRef}/work-$suffix"
        _         <- runGit("git", "checkout", "-b", branchName)
        _         <- ZIO
                       .attemptBlocking(
                         JFiles.writeString(
                           repoPath.resolve(s"${req.issueRef}-impl.txt"),
                           s"Work done for ${req.issueRef}",
                         )
                       )
                       .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        _         <- runGit("git", "add", ".")
        _         <- runGit("git", "commit", "-m", s"impl: ${req.issueRef}")
        _         <- runGit("git", "checkout", "main")
        _         <- dispatchedRef.update(_ :+ req.issueRef)
        now        = Instant.now()
      yield WorkspaceRun(
        id = s"run-${req.issueRef}-$suffix",
        workspaceId = workspaceId,
        parentRunId = None,
        issueRef = req.issueRef,
        agentName = req.agentName,
        prompt = req.prompt,
        conversationId = s"conv-${req.issueRef}-$suffix",
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
      ZIO.fail(WorkspaceError.InvalidRunState(runId, "idle", "PooledStub: continueRun not supported"))

    // Releasing a slot allows the next dispatch cycle to pick up a skipped issue.
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
      slotsRef.update(_ + 1)

  private def makePooledService(
    repoPath: Path,
    slots: Int,
    acquireDelay: Duration = Duration.Zero,
  ): UIO[(PooledStubRunService, Ref[List[String]])] =
    for
      slotsRef      <- Ref.make(slots)
      dispatchedRef <- Ref.make(List.empty[String])
      branchSuffix  <- Ref.make(0)
    yield (PooledStubRunService(repoPath, slotsRef, dispatchedRef, acquireDelay, branchSuffix), dispatchedRef)

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
      wsRepo       = StubWorkspaceRepository.single(minimalWorkspace(workspaceId, repoPath))
      hub          = NoOpActivityHub
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

  // ── Helper: create N independent issues in Todo ───────────────────────────────

  private def createTodoIssues(
    boardRepo: BoardRepositoryFS,
    workspacePath: String,
    ids: List[String],
  ): IO[BoardError, Unit] =
    ZIO.foreachDiscard(ids) { id =>
      val fm    = IssueFrontmatter(
        id = BoardIssueId(id),
        title = s"Concurrency test issue $id",
        priority = IssuePriority.Medium,
        assignedAgent = None,
        requiredCapabilities = List("scala"),
        blockedBy = Nil,
        tags = Nil,
        acceptanceCriteria = List(s"$id is done"),
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
      boardRepo.createIssue(workspacePath, BoardColumn.Backlog, issue) *>
        boardRepo.moveIssue(workspacePath, BoardIssueId(id), BoardColumn.Todo).unit
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DispatchConcurrencyIntegrationSpec")(
      test("pool exhaustion: skipped issues are retried in subsequent dispatch cycles") {
        ZIO.scoped {
          for
            repoPath     <- initGitRepo
            workspacePath = repoPath.toString

            // 1 slot: only one issue dispatched per cycle
            (runService, dispatchedRef) <- makePooledService(repoPath, slots = 1)
            (orch, boardRepo)           <- buildOrchestrator(repoPath, runService)
            _                           <- boardRepo.initBoard(workspacePath)

            issueIds = List("slot-issue-a", "slot-issue-b")
            _       <- createTodoIssues(boardRepo, workspacePath, issueIds)

            // ── Cycle 1: slot-issue-a acquires the single slot; slot-issue-b is skipped ──
            dispatch1 <- orch.dispatchCycle(workspacePath)
            board1    <- boardRepo.readBoard(workspacePath)
            ip1        = board1.columns.getOrElse(BoardColumn.InProgress, Nil)
            todo1      = board1.columns.getOrElse(BoardColumn.Todo, Nil)

            // Release the slot held by slot-issue-a
            _ <- runService.cancelRun(s"run-slot-issue-a-1")

            // ── Cycle 2: slot-issue-b can now acquire the slot ─────────────────
            dispatch2 <- orch.dispatchCycle(workspacePath)
            board2    <- boardRepo.readBoard(workspacePath)
            ip2        = board2.columns.getOrElse(BoardColumn.InProgress, Nil)

            dispatched <- dispatchedRef.get
          yield assertTrue(
            // Cycle 1: exactly one dispatched, one skipped
            dispatch1.dispatchedIssueIds.size == 1,
            dispatch1.skippedIssueIds.size == 1,
            // slot-issue-a dispatched first (highest priority / creation order)
            dispatch1.dispatchedIssueIds.head.value == "slot-issue-a",
            dispatch1.skippedIssueIds.head.value == "slot-issue-b",
            // Board after cycle 1: slot-issue-a InProgress, slot-issue-b Todo
            ip1.exists(_.frontmatter.id.value == "slot-issue-a"),
            todo1.exists(_.frontmatter.id.value == "slot-issue-b"),
            // Cycle 2: slot-issue-b now dispatched
            dispatch2.dispatchedIssueIds.size == 1,
            dispatch2.dispatchedIssueIds.head.value == "slot-issue-b",
            // Board after cycle 2: both in InProgress
            ip2.size == 2,
            ip2.exists(_.frontmatter.id.value == "slot-issue-a"),
            ip2.exists(_.frontmatter.id.value == "slot-issue-b"),
            // No issue was dispatched more than once
            dispatched.distinct.size == dispatched.size,
          )
        }
      },
      test("second dispatch cycle does not re-dispatch in-progress issues") {
        ZIO.scoped {
          for
            repoPath     <- initGitRepo
            workspacePath = repoPath.toString

            // Large pool so slots are not limiting; focus is on board-level dedup
            (runService, dispatchedRef) <- makePooledService(repoPath, slots = 10)
            (orch, boardRepo)           <- buildOrchestrator(repoPath, runService)
            _                           <- boardRepo.initBoard(workspacePath)

            issueIds = List("conc-issue-1", "conc-issue-2", "conc-issue-3")
            _       <- createTodoIssues(boardRepo, workspacePath, issueIds)

            // ── Cycle 1: dispatches all 3 issues to InProgress ────────────────
            cycle1 <- orch.dispatchCycle(workspacePath)

            // ── Cycle 2: all issues are now InProgress, nothing to dispatch ───
            cycle2 <- orch.dispatchCycle(workspacePath)

            dispatched <- dispatchedRef.get
          yield assertTrue(
            // Cycle 1 dispatched all 3
            cycle1.dispatchedIssueIds.size == 3,
            cycle1.skippedIssueIds.isEmpty,
            // Cycle 2 dispatched nothing (all issues are in InProgress, not Todo)
            cycle2.dispatchedIssueIds.isEmpty,
            cycle2.skippedIssueIds.isEmpty,
            // The run service was called exactly 3 times (no double-dispatch)
            dispatched.size == 3,
            dispatched.distinct.size == 3,
          )
        }
      },
    )
