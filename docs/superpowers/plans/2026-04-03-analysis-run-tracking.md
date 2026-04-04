# Analysis Run Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make workspace analysis jobs (CodeReview, Architecture, Security) create `WorkspaceRun` records so they appear in the Command Center's Active Runs widget and the issue timeline view.

**Architecture:** Modify `WorkspaceAnalysisScheduler.runJob()` to create a `WorkspaceRun` per analysis job, linked to the board issue via `issueRef`. The `ensureHumanReviewIssue` chain is moved to run first and returns the `BoardIssueId`. No changes needed to timeline or active runs systems — they already query `WorkspaceRun` records.

**Tech Stack:** Scala 3, ZIO, event-sourced `WorkspaceRunEvent`

**Spec:** `docs/superpowers/specs/2026-04-03-analysis-run-tracking-design.md`

---

### Task 1: Make the test harness capture run events

The existing `StubWorkspaceRepository` in `TestFixtures.scala` is stateless — `appendRun` returns `ZIO.unit` and discards events. The scheduler spec needs a workspace repository that accumulates run events so we can verify runs are created.

**Files:**
- Modify: `src/test/scala/analysis/control/WorkspaceAnalysisSchedulerSpec.scala`

- [ ] **Step 1: Add a stateful workspace repository to the spec's Harness**

Add a `Ref[List[WorkspaceRunEvent]]` to capture run events, and a local workspace repository class that stores them. Update `Harness` and `makeHarness` accordingly.

In `WorkspaceAnalysisSchedulerSpec.scala`, update the `Harness` case class (line 20) to add a `runEventsRef`:

```scala
  final private case class Harness(
    service: WorkspaceAnalysisSchedulerLive,
    countsRef: Ref[Map[AnalysisType, Int]],
    docsRef: Ref[List[AnalysisDoc]],
    activityRef: Ref[List[ActivityEvent]],
    boardRef: Ref[Map[BoardIssueId, BoardIssue]],
    runEventsRef: Ref[List[WorkspaceRunEvent]],
    releaseAll: UIO[Unit],
  )
```

Add a new inner class before `makeHarness`:

```scala
  final private class CapturingWorkspaceRepository(
    workspaces: List[Workspace],
    runEventsRef: Ref[List[WorkspaceRunEvent]],
  ) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(workspaces)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]     =
      ZIO.succeed(workspaces.filter(_.projectId == projectId))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       =
      ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                =
      runEventsRef.update(_ :+ event)
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
      runEventsRef.get.map(buildRuns(_).filter(_.workspaceId == workspaceId))
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      runEventsRef.get.map(buildRuns(_).filter(_.issueRef == issueRef))
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
      runEventsRef.get.map(buildRuns(_).find(_.id == id))

    private def buildRuns(events: List[WorkspaceRunEvent]): List[WorkspaceRun] =
      events
        .groupBy(_.runId)
        .values
        .flatMap(evts => WorkspaceRun.fromEvents(evts.toList).toOption)
        .toList
```

In `makeHarness` (around line 140), add the ref and replace the workspace repository:

Replace:
```scala
      workspaceRepo  = new StubWorkspaceRepository(
```
through to the closing `)` of StubWorkspaceRepository with:

```scala
      runEventsRef  <- Ref.make(List.empty[WorkspaceRunEvent])
      workspaceRepo  = CapturingWorkspaceRepository(
                         List(
                           Workspace(
                             id = "ws-1",
                             projectId = ProjectId("test-project"),
                             name = "workspace-1",
                             localPath = "/tmp/ws-1",
                             defaultAgent = None,
                             description = None,
                             enabled = true,
                             runMode = RunMode.Host,
                             cliTool = "codex",
                             createdAt = Instant.EPOCH,
                             updatedAt = Instant.EPOCH,
                           )
                         ),
                         runEventsRef,
                       )
```

Update the `yield` at the end of `makeHarness` to include `runEventsRef`:

```scala
    yield Harness(service, countsRef, docsRef, activityRef, boardRef, runEventsRef, releaseAll)
```

- [ ] **Step 2: Compile and run existing tests to verify the harness change is neutral**

Run: `/opt/homebrew/bin/sbt 'testOnly *WorkspaceAnalysisSchedulerSpec'`

Expected: All 5 existing tests pass. The harness change is additive — existing tests don't query `runEventsRef`.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/analysis/control/WorkspaceAnalysisSchedulerSpec.scala
git commit -m "refactor: use capturing workspace repository in analysis scheduler tests

Replaces the stateless StubWorkspaceRepository with a CapturingWorkspaceRepository
that stores WorkspaceRunEvent records, preparing for tests that verify analysis
jobs create WorkspaceRun records."
```

---

### Task 2: Write failing tests for analysis run creation

**Files:**
- Modify: `src/test/scala/analysis/control/WorkspaceAnalysisSchedulerSpec.scala`

- [ ] **Step 1: Add test that analysis jobs create WorkspaceRun records**

Add this test after the existing "completed analyses do not create duplicate analysis review board issues" test (around line 306):

```scala
    test("analysis jobs create WorkspaceRun records linked to the board issue") {
      for
        harness   <- makeHarness()
        _         <- harness.service.triggerManual("ws-1")
        _         <- processQueued(harness.service)
        runEvents <- harness.runEventsRef.get
        assigned   = runEvents.collect { case e: WorkspaceRunEvent.Assigned => e }
        issues    <- harness.boardRef.get.map(_.values.toList)
        reviewId   = issues.find(_.frontmatter.tags.contains("analysis-review")).map(_.frontmatter.id.value)
      yield assertTrue(
        assigned.size == 3,
        assigned.map(_.agentName).toSet == Set("analysis-code-review", "analysis-architecture", "analysis-security"),
        assigned.forall(_.workspaceId == "ws-1"),
        assigned.forall(a => reviewId.contains(a.issueRef)),
      )
    },
```

- [ ] **Step 2: Add test that run status transitions to Completed**

Add this test after the previous one:

```scala
    test("analysis run status transitions through Running to Completed") {
      for
        harness   <- makeHarness()
        _         <- harness.service.triggerManual("ws-1")
        _         <- processQueued(harness.service)
        runEvents <- harness.runEventsRef.get
        statuses   = runEvents.collect { case e: WorkspaceRunEvent.StatusChanged => e }
        running    = statuses.filter(_.status == RunStatus.Running(RunSessionMode.Autonomous))
        completed  = statuses.filter(_.status == RunStatus.Completed)
      yield assertTrue(
        running.size == 3,
        completed.size == 3,
      )
    },
```

- [ ] **Step 3: Add test that failed analysis sets run status to Failed**

Add a failing runner variant and test. First add a helper method after `processQueued`:

```scala
  private def makeFailingHarness: ZIO[Scope, Nothing, Harness] =
    for
      countsRef    <- Ref.make(Map.empty[AnalysisType, Int])
      docsRef      <- Ref.make(List.empty[AnalysisDoc])
      activityRef  <- Ref.make(List.empty[ActivityEvent])
      boardRef     <- Ref.make(Map.empty[BoardIssueId, BoardIssue])
      runEventsRef <- Ref.make(List.empty[WorkspaceRunEvent])
      repository    = StubAnalysisRepository(docsRef)
      activityHub   = new StubActivityHub(activityRef)
      taskRepository = StubTaskRepository(Map.empty)
      workspaceRepo = CapturingWorkspaceRepository(
                        List(
                          Workspace(
                            id = "ws-1",
                            projectId = ProjectId("test-project"),
                            name = "workspace-1",
                            localPath = "/tmp/ws-1",
                            defaultAgent = None,
                            description = None,
                            enabled = true,
                            runMode = RunMode.Host,
                            cliTool = "codex",
                            createdAt = Instant.EPOCH,
                            updatedAt = Instant.EPOCH,
                          )
                        ),
                        runEventsRef,
                      )
      boardRepo     = StubBoardRepository(boardRef)
      failingRunner = new AnalysisAgentRunner:
                        override def runCodeReview(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]   =
                          ZIO.fail(AnalysisAgentRunnerError.ProcessFailed("code-review", "simulated failure"))
                        override def runArchitecture(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc] =
                          ZIO.fail(AnalysisAgentRunnerError.ProcessFailed("architecture", "simulated failure"))
                        override def runSecurity(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]     =
                          ZIO.fail(AnalysisAgentRunnerError.ProcessFailed("security", "simulated failure"))
      queue         <- Queue.unbounded[WorkspaceAnalysisJob]
      runtimeState  <- Ref.Synchronized.make(Map.empty[(String, AnalysisType), WorkspaceAnalysisStatus])
      service        = WorkspaceAnalysisSchedulerLive(
                         runner = failingRunner,
                         repository = repository,
                         activityHub = activityHub,
                         taskRepository = taskRepository,
                         boardRepository = boardRepo,
                         workspaceRepository = workspaceRepo,
                         projectStorageService = StubProjectStorageService,
                         queue = queue,
                         runtimeState = runtimeState,
                       )
      releaseAll     = ZIO.unit
    yield Harness(service, countsRef, docsRef, activityRef, boardRef, runEventsRef, releaseAll)
```

Then add the test:

```scala
    test("failed analysis sets run status to Failed") {
      for
        harness   <- makeFailingHarness
        _         <- harness.service.triggerManual("ws-1")
        _         <- processQueued(harness.service)
        runEvents <- harness.runEventsRef.get
        statuses   = runEvents.collect { case e: WorkspaceRunEvent.StatusChanged => e }
        failed     = statuses.filter(_.status == RunStatus.Failed)
      yield assertTrue(
        failed.size == 3,
      )
    },
```

- [ ] **Step 4: Run the new tests to verify they fail**

Run: `/opt/homebrew/bin/sbt 'testOnly *WorkspaceAnalysisSchedulerSpec'`

Expected: The 3 new tests FAIL (no `WorkspaceRunEvent.Assigned` events are created yet). The 5 existing tests should still pass.

- [ ] **Step 5: Commit the failing tests**

```bash
git add src/test/scala/analysis/control/WorkspaceAnalysisSchedulerSpec.scala
git commit -m "test: add failing tests for analysis run tracking

Tests verify that analysis jobs create WorkspaceRun records linked to the
board issue, transition status to Completed, and set Failed on error."
```

---

### Task 3: Change `ensureHumanReviewIssue` chain to return `BoardIssueId`

**Files:**
- Modify: `src/main/scala/analysis/control/WorkspaceAnalysisScheduler.scala`

- [ ] **Step 1: Update `createWorkspaceBoardReviewIssue` to return `BoardIssueId`**

In `WorkspaceAnalysisScheduler.scala`, change the method (around line 297):

Old:
```scala
  private def createWorkspaceBoardReviewIssue(
    workspacePath: String,
    title: String,
    description: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    for
      boardIssueId <- ZIO
                        .fromEither(BoardIssueId.fromString(IssueId.generate.value))
                        .mapError(err => PersistenceError.QueryFailed("board_issue_id", err))
      _            <- boardRepository
                        .createIssue(
                          workspacePath,
                          BoardColumn.Review,
                          BoardIssue(
                            frontmatter = IssueFrontmatter(
                              id = boardIssueId,
                              title = title,
                              priority = IssuePriority.Medium,
                              assignedAgent = None,
                              requiredCapabilities = Nil,
                              blockedBy = Nil,
                              tags = List("analysis-review", "auto-generated"),
                              acceptanceCriteria = Nil,
                              estimate = None,
                              proofOfWork = Nil,
                              transientState = TransientState.None,
                              branchName = None,
                              failureReason = None,
                              completedAt = None,
                              createdAt = now,
                            ),
                            body = description,
                            column = BoardColumn.Review,
                            directoryPath = "",
                          ),
                        )
                        .unit
                        .mapError(mapBoardError)
    yield ()
```

New:
```scala
  private def createWorkspaceBoardReviewIssue(
    workspacePath: String,
    title: String,
    description: String,
    now: Instant,
  ): IO[PersistenceError, BoardIssueId] =
    for
      boardIssueId <- ZIO
                        .fromEither(BoardIssueId.fromString(IssueId.generate.value))
                        .mapError(err => PersistenceError.QueryFailed("board_issue_id", err))
      _            <- boardRepository
                        .createIssue(
                          workspacePath,
                          BoardColumn.Review,
                          BoardIssue(
                            frontmatter = IssueFrontmatter(
                              id = boardIssueId,
                              title = title,
                              priority = IssuePriority.Medium,
                              assignedAgent = None,
                              requiredCapabilities = Nil,
                              blockedBy = Nil,
                              tags = List("analysis-review", "auto-generated"),
                              acceptanceCriteria = Nil,
                              estimate = None,
                              proofOfWork = Nil,
                              transientState = TransientState.None,
                              branchName = None,
                              failureReason = None,
                              completedAt = None,
                              createdAt = now,
                            ),
                            body = description,
                            column = BoardColumn.Review,
                            directoryPath = "",
                          ),
                        )
                        .unit
                        .mapError(mapBoardError)
    yield boardIssueId
```

- [ ] **Step 2: Update `ensureWorkspaceBoardReviewIssue` to return `BoardIssueId`**

Old (around line 273):
```scala
  private def ensureWorkspaceBoardReviewIssue(
    workspaceId: String,
    title: String,
    description: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    for
      workspaceOpt <- workspaceRepository.get(workspaceId)
      workspace    <- ZIO.fromOption(workspaceOpt).orElseFail(PersistenceError.NotFound("workspace", workspaceId))
      boardPath    <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _            <- boardRepository.initBoard(boardPath).mapError(mapBoardError)
      existing     <- ZIO
                        .foreach(boardColumns)(column => boardRepository.listIssues(boardPath, column))
                        .map(_.flatten)
                        .mapError(mapBoardError)
      hasReview     = existing.exists(issue =>
                        issue.frontmatter.tags.exists(_.equalsIgnoreCase("analysis-review")) ||
                        issue.frontmatter.title.equalsIgnoreCase(title)
                      )
      _            <- ZIO.unless(hasReview) {
                        createWorkspaceBoardReviewIssue(boardPath, title, description, now)
                      }
    yield ()
```

New:
```scala
  private def ensureWorkspaceBoardReviewIssue(
    workspaceId: String,
    title: String,
    description: String,
    now: Instant,
  ): IO[PersistenceError, BoardIssueId] =
    for
      workspaceOpt <- workspaceRepository.get(workspaceId)
      workspace    <- ZIO.fromOption(workspaceOpt).orElseFail(PersistenceError.NotFound("workspace", workspaceId))
      boardPath    <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _            <- boardRepository.initBoard(boardPath).mapError(mapBoardError)
      existing     <- ZIO
                        .foreach(boardColumns)(column => boardRepository.listIssues(boardPath, column))
                        .map(_.flatten)
                        .mapError(mapBoardError)
      existingReview = existing.find(issue =>
                         issue.frontmatter.tags.exists(_.equalsIgnoreCase("analysis-review")) ||
                         issue.frontmatter.title.equalsIgnoreCase(title)
                       )
      issueId      <- existingReview match
                        case Some(issue) => ZIO.succeed(issue.frontmatter.id)
                        case None        => createWorkspaceBoardReviewIssue(boardPath, title, description, now)
    yield issueId
```

- [ ] **Step 3: Update `upsertHumanReviewIssue` to return `BoardIssueId`**

Old (around line 255):
```scala
  private def upsertHumanReviewIssue(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    val title       = s"Analysis docs for workspace $workspaceId"
    val description =
      s"Automatically created review issue for workspace $workspaceId. Review the latest code review, architecture, and security analysis docs."
    ensureWorkspaceBoardReviewIssue(workspaceId, title, description, now)
```

New:
```scala
  private def upsertHumanReviewIssue(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, BoardIssueId] =
    val title       = s"Analysis docs for workspace $workspaceId"
    val description =
      s"Automatically created review issue for workspace $workspaceId. Review the latest code review, architecture, and security analysis docs."
    ensureWorkspaceBoardReviewIssue(workspaceId, title, description, now)
```

- [ ] **Step 4: Update `ensureHumanReviewIssueWithAnalysis` to return `BoardIssueId`**

Old (around line 247):
```scala
  private def ensureHumanReviewIssueWithAnalysis(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    runtimeState.modifyZIO { current =>
      upsertHumanReviewIssue(workspaceId, now).as(((), current))
    }
```

New:
```scala
  private def ensureHumanReviewIssueWithAnalysis(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, BoardIssueId] =
    runtimeState.modifyZIO { current =>
      upsertHumanReviewIssue(workspaceId, now).map(id => (id, current))
    }
```

- [ ] **Step 5: Compile to verify signature changes are consistent**

Run: `/opt/homebrew/bin/sbt compile`

Expected: Compiles successfully. The only caller of `ensureHumanReviewIssueWithAnalysis` is `runJob()`, which currently ignores the return value (`_ <-`), so the type change is compatible.

- [ ] **Step 6: Run existing tests to verify no regressions**

Run: `/opt/homebrew/bin/sbt 'testOnly *WorkspaceAnalysisSchedulerSpec'`

Expected: The 5 original tests still pass. The 3 new tests still fail (run events not yet created).

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/analysis/control/WorkspaceAnalysisScheduler.scala
git commit -m "refactor: return BoardIssueId from ensureHumanReviewIssue chain

Changes four private methods to return the board issue ID instead of Unit,
enabling runJob to use it as the issueRef for WorkspaceRun records."
```

---

### Task 4: Create `WorkspaceRun` records in `runJob()`

**Files:**
- Modify: `src/main/scala/analysis/control/WorkspaceAnalysisScheduler.scala`

- [ ] **Step 1: Add imports for run event types**

At the top of `WorkspaceAnalysisScheduler.scala`, add to the existing imports (around line 15):

```scala
import workspace.entity.{ WorkspaceRepository, WorkspaceRunEvent, RunStatus, RunSessionMode }
```

Replace the existing `import workspace.entity.WorkspaceRepository` with the line above.

- [ ] **Step 2: Add `analysisAgentName` helper**

Add this private method after `activitySummaryPrefix` (around line 428):

```scala
  private def analysisAgentName(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "analysis-code-review"
      case AnalysisType.Architecture => "analysis-architecture"
      case AnalysisType.Security     => "analysis-security"
      case AnalysisType.Custom(name) => s"analysis-${name.toLowerCase}"
```

- [ ] **Step 3: Rewrite `runJob()` to create and update WorkspaceRun records**

Replace the entire `runJob` method (lines 166-214) with this version. It uses a `Ref` to track the `runId` so the error handler can mark failed runs:
      _         <- workspaceRepository
                     .appendRun(WorkspaceRunEvent.StatusChanged(runId, RunStatus.Running(RunSessionMode.Autonomous), startedAt))
      _         <- publishActivity(job.workspaceId, job.analysisType, ActivityEventType.AnalysisStarted, startedAt, None)
      doc       <- runAnalysis(job.workspaceId, job.analysisType)
      completed <- Clock.instant
      _         <- workspaceRepository
                     .appendRun(WorkspaceRunEvent.StatusChanged(runId, RunStatus.Completed, completed))
      _         <- updateStatus(job.workspaceId, job.analysisType) { current =>
                     current.copy(
                       state = WorkspaceAnalysisState.Completed,
                       completedAt = Some(doc.updatedAt),
                       lastUpdatedAt = completed,
                     )
                   }
      _         <- publishActivity(
                     job.workspaceId,
                     job.analysisType,
                     ActivityEventType.AnalysisCompleted,
                     completed,
                     Some(doc.generatedBy.value),
                   )
    yield runId

    effect.foldZIO(
      failure = err =>
        Clock.instant.flatMap { failedAt =>
          updateStatus(job.workspaceId, job.analysisType) { current =>
            current.copy(state = WorkspaceAnalysisState.Failed, lastUpdatedAt = failedAt)
          } *>
            publishActivity(job.workspaceId, job.analysisType, ActivityEventType.AnalysisFailed, failedAt, None) *>
            ZIO.logWarning(
              s"Analysis execution failed for ${job.workspaceId}/${renderAnalysisType(job.analysisType)}: ${renderJobError(err)}"
            )
        },
      success = _ => ZIO.unit,
    )
```

Wait — the failure handler also needs the `runId` to set its status to Failed. The problem is the `runId` is created inside the for-comprehension and isn't available in the error handler if the failure happens after assignment.

Better approach: use a `Ref` to track the run ID so the error handler can access it.

Replace the full `runJob` method with this final version:

```scala
  private def runJob(job: WorkspaceAnalysisJob): UIO[Unit] =
    Ref.make(Option.empty[String]).flatMap { runIdRef =>
      val effect = for
        startedAt <- Clock.instant
        issueId   <- ensureHumanReviewIssueWithAnalysis(job.workspaceId, startedAt)
        runId      = java.util.UUID.randomUUID().toString
        _         <- runIdRef.set(Some(runId))
        workspace <- workspaceRepository.get(job.workspaceId).mapError(identity)
        localPath  = workspace.map(_.localPath).getOrElse("")
        _         <- workspaceRepository
                       .appendRun(
                         WorkspaceRunEvent.Assigned(
                           runId = runId,
                           workspaceId = job.workspaceId,
                           issueRef = issueId.value,
                           agentName = analysisAgentName(job.analysisType),
                           prompt =
                             s"${renderAnalysisType(job.analysisType)} analysis for workspace ${job.workspaceId}",
                           conversationId = "",
                           worktreePath = localPath,
                           branchName = "",
                           occurredAt = startedAt,
                         )
                       )
          _         <- updateStatus(job.workspaceId, job.analysisType) { current =>
                       current.copy(
                         state = WorkspaceAnalysisState.Running,
                         startedAt = Some(startedAt),
                         lastUpdatedAt = startedAt,
                       )
                     }
        _         <- workspaceRepository
                       .appendRun(
                         WorkspaceRunEvent.StatusChanged(runId, RunStatus.Running(RunSessionMode.Autonomous), startedAt)
                       )
          _         <- publishActivity(
                       job.workspaceId,
                       job.analysisType,
                       ActivityEventType.AnalysisStarted,
                       startedAt,
                       None,
                     )
        doc       <- runAnalysis(job.workspaceId, job.analysisType)
        completed <- Clock.instant
        _         <- workspaceRepository
                       .appendRun(WorkspaceRunEvent.StatusChanged(runId, RunStatus.Completed, completed))
          _         <- updateStatus(job.workspaceId, job.analysisType) { current =>
                       current.copy(
                         state = WorkspaceAnalysisState.Completed,
                         completedAt = Some(doc.updatedAt),
                         lastUpdatedAt = completed,
                       )
                     }
        _         <- publishActivity(
                       job.workspaceId,
                       job.analysisType,
                       ActivityEventType.AnalysisCompleted,
                       completed,
                       Some(doc.generatedBy.value),
                     )
      yield ()

      effect.catchAll { err =>
        Clock.instant.flatMap { failedAt =>
          runIdRef.get.flatMap { maybeRunId =>
            val markRunFailed = maybeRunId match
              case Some(id) =>
                workspaceRepository
                  .appendRun(WorkspaceRunEvent.StatusChanged(id, RunStatus.Failed, failedAt))
                  .ignore
              case None     => ZIO.unit
            markRunFailed *>
              updateStatus(job.workspaceId, job.analysisType) { current =>
                current.copy(state = WorkspaceAnalysisState.Failed, lastUpdatedAt = failedAt)
              } *>
              publishActivity(
                job.workspaceId,
                job.analysisType,
                ActivityEventType.AnalysisFailed,
                failedAt,
                None,
              ) *>
              ZIO.logWarning(
                s"Analysis execution failed for ${job.workspaceId}/${renderAnalysisType(job.analysisType)}: ${renderJobError(err)}"
              )
          }
        }
      }
    }
```

- [ ] **Step 6: Compile**

Run: `/opt/homebrew/bin/sbt compile`

Expected: Compiles successfully.

- [ ] **Step 7: Run all tests**

Run: `/opt/homebrew/bin/sbt 'testOnly *WorkspaceAnalysisSchedulerSpec'`

Expected: All 8 tests pass (5 existing + 3 new).

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/analysis/control/WorkspaceAnalysisScheduler.scala
git commit -m "feat: create WorkspaceRun records for analysis jobs

Analysis jobs now create WorkspaceRun records linked to the board issue
via issueRef. Each analysis type gets its own run with agentName like
'analysis-code-review'. Runs transition Pending→Running→Completed (or
Failed). This makes analysis visible in Active Runs and issue timeline."
```

---

### Task 5: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `/opt/homebrew/bin/sbt test`

Expected: 1175+ tests pass. Only pre-existing flaky failures (e.g., `WorkspaceRunServiceSpec` timeout, `WorkspaceServiceIntegrationSpec` parallel race) are acceptable.

- [ ] **Step 2: Verify no unused import/member warnings**

The compile output should have zero warnings. If there are unused warnings (fatal under `-Werror`), fix them.

- [ ] **Step 3: Final commit if any fixups needed**

Only if Step 2 required changes:

```bash
git add -u
git commit -m "fix: resolve unused warnings from analysis run tracking"
```
