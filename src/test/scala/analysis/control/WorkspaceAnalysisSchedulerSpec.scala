package analysis.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import db.{ PersistenceError as DbPersistenceError, * }
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId, IssueId }

object WorkspaceAnalysisSchedulerSpec extends ZIOSpecDefault:

  final private case class Harness(
    service: WorkspaceAnalysisSchedulerLive,
    countsRef: Ref[Map[AnalysisType, Int]],
    docsRef: Ref[List[AnalysisDoc]],
    activityRef: Ref[List[ActivityEvent]],
    issuesRef: Ref[Map[IssueId, AgentIssue]],
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

  final private class StubActivityHub(activityRef: Ref[List[ActivityEvent]]) extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = activityRef.update(_ :+ event)
    override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent]

  final private class StubIssueRepository(issuesRef: Ref[Map[IssueId, AgentIssue]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      issuesRef.modify { current =>
        val updated = event match
          case created: IssueEvent.Created           =>
            current.updated(
              created.issueId,
              AgentIssue(
                id = created.issueId,
                runId = None,
                conversationId = None,
                title = created.title,
                description = created.description,
                issueType = created.issueType,
                priority = created.priority,
                requiredCapabilities = created.requiredCapabilities,
                state = IssueState.Backlog(created.occurredAt),
                tags = Nil,
                contextPath = "",
                sourceFolder = "",
              ),
            )
          case tagsUpdated: IssueEvent.TagsUpdated   =>
            current.updatedWith(tagsUpdated.issueId)(_.map(_.copy(tags = tagsUpdated.tags)))
          case linked: IssueEvent.WorkspaceLinked    =>
            current.updatedWith(linked.issueId)(_.map(_.copy(workspaceId = Some(linked.workspaceId))))
          case moved: IssueEvent.MovedToHumanReview  =>
            current.updatedWith(moved.issueId)(_.map(_.copy(state = IssueState.HumanReview(moved.movedAt))))
          case attached: IssueEvent.AnalysisAttached =>
            current.updatedWith(attached.issueId)(_.map(_.copy(analysisDocIds = attached.analysisDocIds)))
          case _                                     =>
            current
        ((), updated)
      }

    override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
      issuesRef.get.flatMap(issues =>
        ZIO.fromOption(issues.get(id)).orElseFail(PersistenceError.NotFound("issue", id.value))
      )

    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
      ZIO.succeed(Nil)

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      issuesRef.get.map(_.values.toList.filter(issue =>
        filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(issue.state))
      ))

    override def delete(id: IssueId): IO[PersistenceError, Unit] =
      issuesRef.update(_ - id).unit

  final private class StubTaskRepository(settings: Map[String, String]) extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[DbPersistenceError, Long]                           =
      ZIO.fail(DbPersistenceError.QueryFailed("createRun", "unused"))
    override def updateRun(run: TaskRunRow): IO[DbPersistenceError, Unit]                           =
      ZIO.fail(DbPersistenceError.QueryFailed("updateRun", "unused"))
    override def getRun(id: Long): IO[DbPersistenceError, Option[TaskRunRow]]                       =
      ZIO.fail(DbPersistenceError.QueryFailed("getRun", "unused"))
    override def listRuns(offset: Int, limit: Int): IO[DbPersistenceError, List[TaskRunRow]]        =
      ZIO.fail(DbPersistenceError.QueryFailed("listRuns", "unused"))
    override def deleteRun(id: Long): IO[DbPersistenceError, Unit]                                  =
      ZIO.fail(DbPersistenceError.QueryFailed("deleteRun", "unused"))
    override def saveReport(report: TaskReportRow): IO[DbPersistenceError, Long]                    =
      ZIO.fail(DbPersistenceError.QueryFailed("saveReport", "unused"))
    override def getReport(reportId: Long): IO[DbPersistenceError, Option[TaskReportRow]]           =
      ZIO.fail(DbPersistenceError.QueryFailed("getReport", "unused"))
    override def getReportsByTask(taskRunId: Long): IO[DbPersistenceError, List[TaskReportRow]]     =
      ZIO.fail(DbPersistenceError.QueryFailed("getReportsByTask", "unused"))
    override def saveArtifact(artifact: TaskArtifactRow): IO[DbPersistenceError, Long]              =
      ZIO.fail(DbPersistenceError.QueryFailed("saveArtifact", "unused"))
    override def getArtifactsByTask(taskRunId: Long): IO[DbPersistenceError, List[TaskArtifactRow]] =
      ZIO.fail(DbPersistenceError.QueryFailed("getArtifactsByTask", "unused"))
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           =
      ZIO.succeed(settings.toList.map((key, value) => SettingRow(key, value, Instant.EPOCH)))
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                =
      ZIO.succeed(settings.get(key).map(value => SettingRow(key, value, Instant.EPOCH)))
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            =
      ZIO.fail(DbPersistenceError.QueryFailed("upsertSetting", "unused"))

  private def makeHarness(blockTypes: Set[AnalysisType] = Set.empty, settings: Map[String, String] = Map.empty): ZIO[
    Scope,
    Nothing,
    Harness,
  ] =
    for
      countsRef     <- Ref.make(Map.empty[AnalysisType, Int])
      docsRef       <- Ref.make(List.empty[AnalysisDoc])
      activityRef   <- Ref.make(List.empty[ActivityEvent])
      issuesRef     <- Ref.make(Map.empty[IssueId, AgentIssue])
      blockers      <-
        ZIO.foreach(blockTypes)(analysisType => Promise.make[Nothing, Unit].map(analysisType -> _)).map(_.toMap)
      repository     = StubAnalysisRepository(docsRef)
      activityHub    = StubActivityHub(activityRef)
      taskRepository = StubTaskRepository(settings)
      issueRepo      = StubIssueRepository(issuesRef)
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
                         issueRepository = issueRepo,
                         queue = queue,
                         runtimeState = runtimeState,
                       )
      releaseAll     = ZIO.foreachDiscard(blockers.values)(_.succeed(()).unit)
    yield Harness(service, countsRef, docsRef, activityRef, issuesRef, releaseAll)

  private def processQueued(service: WorkspaceAnalysisSchedulerLive, jobs: Int = 3): UIO[Unit] =
    ZIO.foreachDiscard(1 to jobs)(_ => service.worker)

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
        _        <- ZIO.yieldNow.repeatN(20)
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
    test("completed analyses backfill latest docs onto existing HumanReview issues in the same workspace") {
      val issueId = IssueId("issue-1")
      for
        harness <- makeHarness()
        _       <- harness.issuesRef.set(
                     Map(
                       issueId -> AgentIssue(
                         id = issueId,
                         runId = None,
                         conversationId = None,
                         title = "Needs review",
                         description = "desc",
                         issueType = "Task",
                         priority = "medium",
                         requiredCapabilities = Nil,
                         state = IssueState.HumanReview(Instant.EPOCH),
                         tags = Nil,
                         contextPath = "",
                         sourceFolder = "",
                         workspaceId = Some("ws-1"),
                       )
                     )
                   )
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        issues  <- harness.issuesRef.get
      yield assertTrue(
        issues(issueId).analysisDocIds.size == 3
      )
    },
    test("completed analyses create a HumanReview issue card when none exists for the workspace") {
      for
        harness <- makeHarness()
        _       <- harness.service.triggerManual("ws-1")
        _       <- processQueued(harness.service)
        issues  <- harness.issuesRef.get.map(_.values.toList)
        review   = issues.headOption
      yield assertTrue(
        issues.size == 1,
        review.exists(_.workspaceId.contains("ws-1")),
        review.exists(_.tags.contains("analysis-review")),
        review.exists(_.state.isInstanceOf[IssueState.HumanReview]),
        review.exists(_.analysisDocIds.size == 3),
      )
    },
  )
