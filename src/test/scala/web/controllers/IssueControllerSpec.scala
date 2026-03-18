package web.controllers

import java.time.{ Duration, Instant }

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import _root_.config.entity.AIProviderConfig
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import conversation.entity.api.{ ChatConversation, ConversationEntry, SessionContextLink }
import db.*
import issues.boundary.IssueControllerLive
import issues.entity.*
import issues.entity.api.AutoAssignIssueResponse
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import orchestration.control.{ AgentConfigResolver, IssueAssignmentOrchestrator, IssueDispatchStatusService }
import shared.errors.PersistenceError as SharedPersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId, IssueId }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object IssueControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-18T09:00:00Z")

  private val sampleAgent = Agent(
    id = AgentId("scala-agent"),
    name = "scala-agent",
    description = "Scala agent",
    cliTool = "codex",
    capabilities = List("scala", "zio"),
    defaultModel = None,
    systemPrompt = None,
    maxConcurrentRuns = 1,
    envVars = Map.empty,
    timeout = Duration.ofMinutes(10),
    enabled = true,
    createdAt = now,
    updatedAt = now,
  )

  private val sampleWorkspace = Workspace(
    id = "ws-1",
    name = "gateway",
    localPath = "/tmp/gateway",
    defaultAgent = Some("scala-agent"),
    description = Some("Gateway workspace"),
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "codex",
    createdAt = now,
    updatedAt = now,
  )

  private val issueId = IssueId("issue-1")

  private def issueSeedEvents: List[IssueEvent] =
    List(
      IssueEvent.Created(
        issueId = issueId,
        title = "Fix drag-to-todo dispatch",
        description = "Ensure drag to Todo creates a workspace run",
        issueType = "bug",
        priority = "high",
        occurredAt = now,
        requiredCapabilities = List("scala"),
      ),
      IssueEvent.WorkspaceLinked(issueId = issueId, workspaceId = sampleWorkspace.id, occurredAt = now),
      IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now),
    )

  final private class InMemoryIssueRepository(ref: Ref[Map[IssueId, List[IssueEvent]]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[SharedPersistenceError, Unit] =
      ref.update(current =>
        current.updatedWith(event.issueId) {
          case Some(events) => Some(events :+ event)
          case None         => Some(List(event))
        }
      )

    override def get(id: IssueId): IO[SharedPersistenceError, AgentIssue] =
      ref.get.flatMap(_.get(id) match
        case Some(events) =>
          ZIO
            .fromEither(AgentIssue.fromEvents(events))
            .mapError(err => SharedPersistenceError.SerializationFailed(s"issue:${id.value}", err))
        case None         => ZIO.fail(SharedPersistenceError.NotFound("issue", id.value)))

    override def history(id: IssueId): IO[SharedPersistenceError, List[IssueEvent]] =
      ref.get.map(_.getOrElse(id, Nil))

    override def list(filter: IssueFilter): IO[SharedPersistenceError, List[AgentIssue]] =
      ref.get.flatMap(eventsByIssue =>
        ZIO.foreach(eventsByIssue.values.toList)(events =>
          ZIO
            .fromEither(AgentIssue.fromEvents(events))
            .mapError(err => SharedPersistenceError.SerializationFailed("issue:list", err))
        )
      )

    override def delete(id: IssueId): IO[SharedPersistenceError, Unit] =
      ref.update(_ - id)

  final private class StubWorkspaceRepository(workspaces: List[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[SharedPersistenceError, Unit]                      = ZIO.dieMessage("unused")
    override def list: IO[SharedPersistenceError, List[Workspace]]                                    = ZIO.succeed(workspaces)
    override def get(id: String): IO[SharedPersistenceError, Option[Workspace]]                       =
      ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[SharedPersistenceError, Unit]                                 = ZIO.dieMessage("unused")
    override def appendRun(event: WorkspaceRunEvent): IO[SharedPersistenceError, Unit]                = ZIO.dieMessage("unused")
    override def listRuns(workspaceId: String): IO[SharedPersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[SharedPersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[SharedPersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  final private class StubWorkspaceRunService(
    runRequests: Ref[List[AssignRunRequest]],
    failAssign: Boolean,
  ) extends WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      if failAssign then ZIO.fail(WorkspaceError.NotFound(workspaceId))
      else
        for
          _ <- runRequests.update(_ :+ req)
        yield WorkspaceRun(
          id = "run-1",
          workspaceId = workspaceId,
          parentRunId = None,
          issueRef = req.issueRef,
          agentName = req.agentName,
          prompt = req.prompt,
          conversationId = "42",
          worktreePath = "/tmp/worktree",
          branchName = "agent/scala-agent-issue-1",
          status = workspace.entity.RunStatus.Running(RunSessionMode.Autonomous),
          attachedUsers = Set.empty,
          controllerUserId = None,
          createdAt = now,
          updatedAt = now,
        )

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String],
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.dieMessage("unused")

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
      ZIO.dieMessage("unused")

  private object StubChatRepository extends ChatRepository:
    override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]                        =
      ZIO.dieMessage("unused")
    override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                             =
      ZIO.dieMessage("unused")
    override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]              =
      ZIO.dieMessage("unused")
    override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]]          =
      ZIO.dieMessage("unused")
    override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]                     =
      ZIO.dieMessage("unused")
    override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]                        =
      ZIO.dieMessage("unused")
    override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                              =
      ZIO.dieMessage("unused")
    override def addMessage(message: ConversationEntry): IO[PersistenceError, Long]                                    =
      ZIO.dieMessage("unused")
    override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]]                      =
      ZIO.dieMessage("unused")
    override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
      ZIO.dieMessage("unused")
    override def getSessionContextByConversation(conversationId: Long)
      : IO[PersistenceError, Option[SessionContextLink]] =
      ZIO.dieMessage("unused")
    override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]]       =
      ZIO.dieMessage("unused")

  private object StubTaskRepository extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           = ZIO.dieMessage("unused")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           = ZIO.dieMessage("unused")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       = ZIO.dieMessage("unused")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]        = ZIO.dieMessage("unused")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  = ZIO.dieMessage("unused")
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    = ZIO.dieMessage("unused")
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           = ZIO.dieMessage("unused")
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     = ZIO.dieMessage("unused")
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              = ZIO.dieMessage("unused")
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      ZIO.dieMessage("unused")
    override def getAllSettings: IO[PersistenceError, List[db.SettingRow]]                        = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[db.SettingRow]]             = ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.dieMessage("unused")

  private object StubConfigRepository extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[db.SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[db.SettingRow]]                = ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]               = ZIO.dieMessage("unused")
    override def deleteSetting(key: String): IO[PersistenceError, Unit]                              = ZIO.dieMessage("unused")
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]                  = ZIO.dieMessage("unused")
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                   = ZIO.dieMessage("unused")
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                    = ZIO.dieMessage("unused")
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]          = ZIO.dieMessage("unused")
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                              = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                   = ZIO.dieMessage("unused")
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                                = ZIO.dieMessage("unused")
    override def createCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Long]             = ZIO.dieMessage("unused")
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[db.CustomAgentRow]]           = ZIO.dieMessage("unused")
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[db.CustomAgentRow]] =
      ZIO.dieMessage("unused")
    override def listCustomAgents: IO[PersistenceError, List[db.CustomAgentRow]]                     = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: db.CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                             = ZIO.dieMessage("unused")

  private object StubAgentRepository extends AgentRepository:
    override def append(event: AgentEvent): IO[SharedPersistenceError, Unit]                    = ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[SharedPersistenceError, Agent]                            = ZIO.succeed(sampleAgent)
    override def list(includeDeleted: Boolean = false): IO[SharedPersistenceError, List[Agent]] =
      ZIO.succeed(List(sampleAgent))
    override def findByName(name: String): IO[SharedPersistenceError, Option[Agent]]            =
      ZIO.succeed(List(sampleAgent).find(_.name == name))

  private object StubActivityHub extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
    override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent]

  private object StubDispatchStatusService extends IssueDispatchStatusService:
    override def statusFor(issueId: IssueId): IO[SharedPersistenceError, issues.entity.api.DispatchStatusResponse] =
      ZIO.succeed(issues.entity.api.DispatchStatusResponse())
    override def statusesFor(
      issueIds: List[IssueId]
    ): IO[SharedPersistenceError, Map[IssueId, issues.entity.api.DispatchStatusResponse]] =
      ZIO.succeed(Map.empty)

  private object StubAnalysisRepository extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[SharedPersistenceError, Unit]                        = ZIO.dieMessage("unused")
    override def get(id: AnalysisDocId): IO[SharedPersistenceError, AnalysisDoc]                       = ZIO.dieMessage("unused")
    override def listByWorkspace(workspaceId: String): IO[SharedPersistenceError, List[AnalysisDoc]]   =
      ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[SharedPersistenceError, List[AnalysisDoc]] =
      ZIO.succeed(Nil)

  private object StubWorkReportProjection extends IssueWorkReportProjection:
    override def get(issueId: IssueId): UIO[Option[IssueWorkReport]]                                            = ZIO.none
    override def getAll: UIO[Map[IssueId, IssueWorkReport]]                                                     = ZIO.succeed(Map.empty)
    override def updateWalkthrough(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                   = ZIO.unit
    override def updateAgentSummary(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                  = ZIO.unit
    override def updateDiffStats(issueId: IssueId, stats: issues.entity.IssueDiffStats, at: Instant): UIO[Unit] =
      ZIO.unit
    override def updatePrLink(issueId: IssueId, prUrl: String, status: issues.entity.IssuePrStatus, at: Instant)
      : UIO[Unit] = ZIO.unit
    override def updateCiStatus(issueId: IssueId, status: issues.entity.IssueCiStatus, at: Instant): UIO[Unit]  =
      ZIO.unit
    override def updateTokenUsage(issueId: IssueId, usage: issues.entity.TokenUsage, runtimeSeconds: Long, at: Instant)
      : UIO[Unit] = ZIO.unit
    override def addReport(issueId: IssueId, report: issues.entity.IssueReport, at: Instant): UIO[Unit]         = ZIO.unit
    override def addArtifact(issueId: IssueId, artifact: issues.entity.IssueArtifact, at: Instant): UIO[Unit]   =
      ZIO.unit

  private object StubAgentConfigResolver extends AgentConfigResolver:
    override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
      ZIO.succeed(AIProviderConfig())

  private object StubLlmService extends LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse]                                     = ZIO.dieMessage("unused")
    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]                        = ZStream.dieMessage("unused")
    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]                 = ZIO.dieMessage("unused")
    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk]    =
      ZStream.dieMessage("unused")
    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    override def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private def makeRoutes(
    issueRepo: IssueRepository,
    workspaceRunService: WorkspaceRunService,
  ): ZIO[Scope, Nothing, Routes[Any, Response]] =
    val orchestratorLayer =
      (ZLayer.succeed(StubChatRepository) ++
        ZLayer.succeed(StubTaskRepository) ++
        ZLayer.succeed(StubLlmService) ++
        ZLayer.succeed(StubAgentConfigResolver) ++
        ZLayer.succeed(StubActivityHub) ++
        ZLayer.succeed(issueRepo)) >>> IssueAssignmentOrchestrator.live

    ZIO
      .service[IssueAssignmentOrchestrator]
      .provide(orchestratorLayer)
      .map(orchestrator =>
        IssueControllerLive(
          chatRepository = StubChatRepository,
          taskRepository = StubTaskRepository,
          configRepository = StubConfigRepository,
          agentRepository = StubAgentRepository,
          issueAssignmentOrchestrator = orchestrator,
          issueRepository = issueRepo,
          workspaceRepository = StubWorkspaceRepository(List(sampleWorkspace)),
          workspaceRunService = workspaceRunService,
          activityHub = StubActivityHub,
          issueDispatchStatusService = StubDispatchStatusService,
          analysisRepository = StubAnalysisRepository,
          issueWorkReportProjection = StubWorkReportProjection,
        ).routes
      )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueControllerSpec")(
      test("POST /api/issues/:id/auto-assign starts the issue only after workspace run creation succeeds") {
        for
          issueEvents <- Ref.make(Map(issueId -> issueSeedEvents))
          runRequests <- Ref.make(List.empty[AssignRunRequest])
          issueRepo    = InMemoryIssueRepository(issueEvents)
          routes      <- makeRoutes(issueRepo, StubWorkspaceRunService(runRequests, failAssign = false))
          req          = Request(
                           method = Method.POST,
                           url = URL(Path.decode(s"/api/issues/${issueId.value}/auto-assign")),
                           body = Body.fromString(""),
                         )
          resp        <- routes.runZIO(req)
          body        <- resp.body.asString
          parsed      <- ZIO
                           .fromEither(body.fromJson[AutoAssignIssueResponse])
                           .orElseFail(new RuntimeException(s"invalid json: $body"))
          updated     <- issueRepo.get(issueId)
          requests    <- runRequests.get
        yield assertTrue(
          resp.status == Status.Ok,
          parsed.assigned,
          requests.map(_.issueRef) == List("#issue-1"),
          updated.state.isInstanceOf[IssueState.InProgress],
        )
      },
      test("POST /api/issues/:id/auto-assign does not move the issue to InProgress when workspace run creation fails") {
        for
          issueEvents <- Ref.make(Map(issueId -> issueSeedEvents))
          runRequests <- Ref.make(List.empty[AssignRunRequest])
          issueRepo    = InMemoryIssueRepository(issueEvents)
          routes      <- makeRoutes(issueRepo, StubWorkspaceRunService(runRequests, failAssign = true))
          req          = Request(
                           method = Method.POST,
                           url = URL(Path.decode(s"/api/issues/${issueId.value}/auto-assign")),
                           body = Body.fromString(""),
                         )
          resp        <- routes.runZIO(req)
          updated     <- issueRepo.get(issueId)
          history     <- issueRepo.history(issueId)
        yield assertTrue(
          resp.status == Status.InternalServerError,
          updated.state.isInstanceOf[IssueState.Assigned],
          !history.exists(_.isInstanceOf[IssueEvent.Started]),
        )
      },
    )
