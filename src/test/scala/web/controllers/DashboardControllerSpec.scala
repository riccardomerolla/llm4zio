package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import db.*
import issues.entity.*
import shared.ids.Ids.{ AgentId, IssueId }
import taskrun.boundary.DashboardControllerLive

object DashboardControllerSpec extends ZIOSpecDefault:
  private val sampleRun = TaskRunRow(
    id = 2L,
    sourceDir = "/src",
    outputDir = "/out",
    status = RunStatus.Completed,
    startedAt = Instant.parse("2026-02-08T00:00:00Z"),
    completedAt = None,
    totalFiles = 3,
    processedFiles = 3,
    successfulConversions = 3,
    failedConversions = 0,
    currentPhase = Some("completed"),
    errorMessage = None,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DashboardControllerSpec")(
    test("GET / renders dashboard") {
      for
        repo      <- TestRepository.make
        issueRepo <- TestIssueRepository.make
        activity  <- TestActivityRepository.make
        controller = DashboardControllerLive(repo, issueRepo, activity)
        response  <- controller.routes.runZIO(Request.get("/"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Command Center"),
        body.contains("Pipeline Summary"),
        body.contains("Live Agent Ops"),
        body.contains("Active Runs"),
        body.contains("Recent Activity"),
        body.contains(">2<"), // open
        body.contains(">1<"), // claimed / running / completed / failed
      )
    },
    test("GET /api/tasks/recent returns runs fragment") {
      for
        repo      <- TestRepository.make
        issueRepo <- TestIssueRepository.make
        activity  <- TestActivityRepository.make
        controller = DashboardControllerLive(repo, issueRepo, activity)
        response  <- controller.routes.runZIO(Request.get("/api/tasks/recent"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("#2"),
      )
    },
  )

  final private case class TestRepository() extends TaskRepository:

    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]] =
      ZIO.succeed(List(sampleRun))

    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.none
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit

  private object TestRepository:
    def make: UIO[TestRepository] = ZIO.succeed(TestRepository())

  final private case class TestIssueRepository() extends IssueRepository:
    private val now = Instant.parse("2026-02-08T10:00:00Z")

    override def list(filter: IssueFilter): IO[shared.errors.PersistenceError, List[AgentIssue]] =
      ZIO.succeed(
        List(
          mkIssue("1", IssueState.Open(now)),
          mkIssue("2", IssueState.Open(now)),
          mkIssue("3", IssueState.Assigned(AgentId("agent-a"), now)),
          mkIssue("4", IssueState.InProgress(AgentId("agent-b"), now)),
          mkIssue("5", IssueState.Completed(AgentId("agent-c"), now, "ok")),
          mkIssue("6", IssueState.Failed(AgentId("agent-d"), now, "boom")),
          mkIssue("7", IssueState.Skipped(now, "not needed")),
        )
      )

    override def append(event: IssueEvent): IO[shared.errors.PersistenceError, Unit]        =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def get(id: IssueId): IO[shared.errors.PersistenceError, AgentIssue]           =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def history(id: IssueId): IO[shared.errors.PersistenceError, List[IssueEvent]] =
      ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[shared.errors.PersistenceError, Unit]              =
      ZIO.dieMessage("unused in DashboardControllerSpec")

    private def mkIssue(id: String, state: IssueState): AgentIssue =
      AgentIssue(
        id = IssueId(id),
        runId = None,
        conversationId = None,
        title = s"Issue $id",
        description = "desc",
        issueType = "task",
        priority = "medium",
        requiredCapabilities = Nil,
        state = state,
        tags = Nil,
        contextPath = "",
        sourceFolder = "",
      )

  private object TestIssueRepository:
    def make: UIO[TestIssueRepository] = ZIO.succeed(TestIssueRepository())

  final private case class TestActivityRepository() extends ActivityRepository:
    override def createEvent(event: ActivityEvent): IO[PersistenceError, shared.ids.Ids.EventId] =
      ZIO.dieMessage("unused in DashboardControllerSpec")

    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] =
      ZIO.succeed(
        List(
          ActivityEvent(
            id = shared.ids.Ids.EventId("evt-1"),
            eventType = ActivityEventType.RunCompleted,
            source = "test-suite",
            runId = Some(shared.ids.Ids.TaskRunId("2")),
            conversationId = None,
            agentName = None,
            summary = "Run #2 completed",
            payload = None,
            createdAt = Instant.parse("2026-02-08T10:00:00Z"),
          )
        ).take(limit.max(0))
      )

  private object TestActivityRepository:
    def make: UIO[TestActivityRepository] = ZIO.succeed(TestActivityRepository())
