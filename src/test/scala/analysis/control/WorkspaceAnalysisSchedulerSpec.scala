package analysis.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Live

import activity.entity.{ ActivityEvent, ActivityEventType }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import board.entity.*
import db.*
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId, BoardIssueId, ProjectId }
import shared.testfixtures.*
import workspace.entity.*

object WorkspaceAnalysisSchedulerSpec extends ZIOSpecDefault:

  final private case class Harness(
    service: WorkspaceAnalysisSchedulerLive,
    countsRef: Ref[Map[AnalysisType, Int]],
    docsRef: Ref[List[AnalysisDoc]],
    activityRef: Ref[List[ActivityEvent]],
    boardRef: Ref[Map[BoardIssueId, BoardIssue]],
    runEventsRef: Ref[List[WorkspaceRunEvent]],
    releaseAll: UIO[Unit],
  )

  final private class StubAnalysisRepository(docsRef: Ref[List[AnalysisDoc]]) extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                        = ZIO.unit
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       =
      docsRef.get.flatMap(docs =>
        ZIO
          .fromOption(docs.find(_.id == id))
          .orElseFail(PersistenceError.NotFound("analysis_doc", id.value))
      )
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   =
      docsRef.get.map(_.filter(_.workspaceId == workspaceId))
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
      docsRef.get.map(_.filter(_.analysisType == analysisType))

  final private class StubTaskRepository(settings: Map[String, String]) extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           =
      ZIO.fail(PersistenceError.QueryFailed("createRun", "unused"))
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           =
      ZIO.fail(PersistenceError.QueryFailed("updateRun", "unused"))
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       =
      ZIO.fail(PersistenceError.QueryFailed("getRun", "unused"))
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]        =
      ZIO.fail(PersistenceError.QueryFailed("listRuns", "unused"))
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  =
      ZIO.fail(PersistenceError.QueryFailed("deleteRun", "unused"))
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    =
      ZIO.fail(PersistenceError.QueryFailed("saveReport", "unused"))
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getReport", "unused"))
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     =
      ZIO.fail(PersistenceError.QueryFailed("getReportsByTask", "unused"))
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              =
      ZIO.fail(PersistenceError.QueryFailed("saveArtifact", "unused"))
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getArtifactsByTask", "unused"))
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           =
      ZIO.succeed(settings.toList.map((key, value) => SettingRow(key, value, Instant.EPOCH)))
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
      ZIO.succeed(settings.get(key).map(value => SettingRow(key, value, Instant.EPOCH)))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            =
      ZIO.fail(PersistenceError.QueryFailed("upsertSetting", "unused"))

  final private class StubBoardRepository(boardRef: Ref[Map[BoardIssueId, BoardIssue]]) extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit]                                   = ZIO.unit
    override def readBoard(workspacePath: String): IO[BoardError, Board]                                  =
      boardRef.get.map(all =>
        Board(
          workspacePath,
          BoardColumn.values.map(column => column -> all.values.filter(_.column == column).toList).toMap,
        )
      )
    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue]      =
      boardRef.get.flatMap(all =>
        ZIO.fromOption(all.get(issueId)).orElseFail(BoardError.IssueNotFound(issueId.value))
      )
    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      boardRef
        .modify { current =>
          current.get(issue.frontmatter.id) match
            case Some(_) =>
              (Left(BoardError.IssueAlreadyExists(issue.frontmatter.id.value)), current)
            case None    =>
              val created = issue.copy(column = column)
              (Right(created), current.updated(issue.frontmatter.id, created))
        }
        .absolve
    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      boardRef
        .modify { current =>
          current.get(issueId) match
            case None        => (Left(BoardError.IssueNotFound(issueId.value)), current)
            case Some(issue) =>
              val moved = issue.copy(column = toColumn)
              (Right(moved), current.updated(issueId, moved))
        }
        .absolve
    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      boardRef
        .modify { current =>
          current.get(issueId) match
            case None        => (Left(BoardError.IssueNotFound(issueId.value)), current)
            case Some(issue) =>
              val updated = issue.copy(frontmatter = update(issue.frontmatter))
              (Right(updated), current.updated(issueId, updated))
        }
        .absolve
    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]          = ZIO.unit
    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      boardRef.get.map(_.values.filter(_.column == column).toList)
    override def invalidateWorkspace(workspacePath: String): UIO[Unit]                                    = ZIO.unit

  private object StubProjectStorageService extends ProjectStorageService:
    override def initProjectStorage(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def projectRoot(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                         =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def boardPath(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                           =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/.board"))
    override def workspaceAnalysisPath(
      projectId: shared.ids.Ids.ProjectId,
      workspaceId: String,
    ): UIO[java.nio.file.Path] =
      ZIO.succeed(
        java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/workspaces/$workspaceId/.llm4zio/analysis")
      )

  private val NoOpControlPlane = NoOpOrchestratorControlPlane

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

  private def makeHarness(blockTypes: Set[AnalysisType] = Set.empty, settings: Map[String, String] = Map.empty): ZIO[
    Scope,
    Nothing,
    Harness,
  ] =
    for
      countsRef     <- Ref.make(Map.empty[AnalysisType, Int])
      docsRef       <- Ref.make(List.empty[AnalysisDoc])
      activityRef   <- Ref.make(List.empty[ActivityEvent])
      boardRef      <- Ref.make(Map.empty[BoardIssueId, BoardIssue])
      blockers      <-
        ZIO.foreach(blockTypes)(analysisType => Promise.make[Nothing, Unit].map(analysisType -> _)).map(_.toMap)
      repository     = StubAnalysisRepository(docsRef)
      activityHub    = new StubActivityHub(activityRef)
      taskRepository = StubTaskRepository(settings)
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
      boardRepo      = StubBoardRepository(boardRef)
      runner         = new AnalysisAgentRunner:
                         override def runCodeReview(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]   =
                           run(workspaceId, AnalysisType.CodeReview)
                         override def runArchitecture(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc] =
                           run(workspaceId, AnalysisType.Architecture)
                         override def runSecurity(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]     =
                           run(workspaceId, AnalysisType.Security)

                         private def run(workspaceId: String, analysisType: AnalysisType)
                           : IO[AnalysisAgentRunnerError, AnalysisDoc] =
                           for
                             _   <- blockers.get(analysisType).map(_.await).getOrElse(ZIO.unit)
                             now <- Clock.instant
                             _   <- countsRef.update(current =>
                                      current.updated(analysisType, current.getOrElse(analysisType, 0) + 1)
                                    )
                             doc  = AnalysisDoc(
                                      id = AnalysisDocId(s"${workspaceId}-${analysisType.toString}-${now.toEpochMilli}"),
                                      workspaceId = workspaceId,
                                      analysisType = analysisType,
                                      content = s"${analysisType.toString} analysis",
                                      filePath = s".llm4zio/${analysisType.toString}.md",
                                      generatedBy = AgentId("analysis-agent"),
                                      createdAt = now,
                                      updatedAt = now,
                                    )
                             _   <- docsRef.update(_ :+ doc)
                           yield doc
      queue         <- Queue.unbounded[WorkspaceAnalysisJob]
      runtimeState  <- Ref.Synchronized.make(Map.empty[(String, AnalysisType), WorkspaceAnalysisStatus])
      service        = WorkspaceAnalysisSchedulerLive(
                         runner = runner,
                         repository = repository,
                         activityHub = activityHub,
                         taskRepository = taskRepository,
                         boardRepository = boardRepo,
                         workspaceRepository = workspaceRepo,
                         projectStorageService = StubProjectStorageService,
                         controlPlane = NoOpControlPlane,
                         queue = queue,
                         runtimeState = runtimeState,
                       )
      releaseAll     = ZIO.foreachDiscard(blockers.values)(_.succeed(()).unit)
    yield Harness(service, countsRef, docsRef, activityRef, boardRef, runEventsRef, releaseAll)

  private def processQueued(service: WorkspaceAnalysisSchedulerLive, jobs: Int = 3): UIO[Unit] =
    ZIO.foreachDiscard(1 to jobs)(_ => service.worker)

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
                         controlPlane = NoOpControlPlane,
                         queue = queue,
                         runtimeState = runtimeState,
                       )
      releaseAll     = ZIO.unit
    yield Harness(service, countsRef, docsRef, activityRef, boardRef, runEventsRef, releaseAll)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceAnalysisSchedulerSpec")(
    test("auto trigger queues all three analyses and emits start/complete activity events") {
      for
        harness <- makeHarness()
        _       <- harness.service.triggerForWorkspaceEvent("ws-1")
        _       <- processQueued(harness.service)
        counts  <- harness.countsRef.get
        events  <- harness.activityRef.get
      yield assertTrue(
        counts.getOrElse(AnalysisType.CodeReview, 0) == 1,
        counts.getOrElse(AnalysisType.Architecture, 0) == 1,
        counts.getOrElse(AnalysisType.Security, 0) == 1,
        events.count(_.eventType == ActivityEventType.AnalysisStarted) == 3,
        events.count(_.eventType == ActivityEventType.AnalysisCompleted) == 3,
      )
    },
    test("cooldown suppresses rapid retriggers until it expires") {
      for
        harness <- makeHarness(settings = Map(WorkspaceAnalysisScheduler.cooldownMinutesSettingKey -> "60"))
        _       <- TestClock.adjust(1.second)
        _       <- harness.service.triggerForWorkspaceEvent("ws-1")
        _       <- processQueued(harness.service)
        first   <- harness.countsRef.get
        _       <- harness.service.triggerForWorkspaceEvent("ws-1")
        second  <- harness.countsRef.get
        _       <- TestClock.adjust(61.minutes)
        _       <- harness.service.triggerForWorkspaceEvent("ws-1")
        _       <- processQueued(harness.service)
        third   <- harness.countsRef.get
      yield assertTrue(
        first.values.sum == 3,
        second.values.sum == 3,
        third.values.sum == 6,
      )
    },
    test("manual trigger bypasses cooldown") {
      for
        harness <- makeHarness(settings = Map(WorkspaceAnalysisScheduler.cooldownMinutesSettingKey -> "60"))
        _       <- harness.service.triggerForWorkspaceEvent("ws-1")
        _       <- processQueued(harness.service)
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        counts  <- harness.countsRef.get
      yield assertTrue(counts.values.sum == 6)
    },
    test("status reports running and then completed timestamps") {
      for
        harness  <- makeHarness(blockTypes = WorkspaceAnalysisScheduler.trackedTypes.toSet)
        _        <- harness.service.triggerManual("ws-1")
        fibers   <- ZIO.foreach(1 to 3)(_ => harness.service.worker.fork)
        _        <- Live.live(ZIO.sleep(200.millis))
        running  <- harness.service.statusForWorkspace("ws-1")
        _        <- harness.releaseAll
        _        <- ZIO.foreachDiscard(fibers)(_.join)
        complete <- harness.service.statusForWorkspace("ws-1")
      yield assertTrue(
        running.exists(_.state == WorkspaceAnalysisState.Running),
        complete.count(_.state == WorkspaceAnalysisState.Completed) == 3,
        complete.forall(_.completedAt.nonEmpty),
      )
    },
    test("completed analyses create one analysis review board issue when none exists for the workspace") {
      for
        harness <- makeHarness()
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        issues  <- harness.boardRef.get.map(_.values.toList)
        review   = issues.find(_.frontmatter.tags.contains("analysis-review"))
      yield assertTrue(
        issues.size == 1,
        review.isDefined,
        review.exists(_.column == BoardColumn.Review),
        review.exists(_.frontmatter.tags.contains("analysis-review")),
      )
    },
    test("completed analyses do not create duplicate analysis review board issues") {
      for
        harness <- makeHarness()
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        issues  <- harness.boardRef.get.map(_.values.toList)
      yield assertTrue(
        issues.count(_.frontmatter.tags.contains("analysis-review")) == 1
      )
    },
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
    test("analysis run status transitions through Running to Completed") {
      for
        harness   <- makeHarness()
        _         <- harness.service.triggerManual("ws-1")
        _         <- processQueued(harness.service)
        runEvents <- harness.runEventsRef.get
        statuses   = runEvents.collect { case e: WorkspaceRunEvent.StatusChanged => e }
        running    = statuses.filter(_.status == workspace.entity.RunStatus.Running(workspace.entity.RunSessionMode.Autonomous))
        completed  = statuses.filter(_.status == workspace.entity.RunStatus.Completed)
      yield assertTrue(
        running.size == 3,
        completed.size == 3,
      )
    },
    test("failed analysis sets run status to Failed") {
      for
        harness   <- makeFailingHarness
        _         <- harness.service.triggerManual("ws-1")
        _         <- processQueued(harness.service)
        runEvents <- harness.runEventsRef.get
        statuses   = runEvents.collect { case e: WorkspaceRunEvent.StatusChanged => e }
        failed     = statuses.filter(_.status == workspace.entity.RunStatus.Failed)
      yield assertTrue(
        failed.size == 3,
      )
    },
  )
