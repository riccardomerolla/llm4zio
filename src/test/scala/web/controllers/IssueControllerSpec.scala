package web.controllers

import java.time.{ Duration, Instant }

import scala.annotation.unused

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import _root_.config.entity.{ ProviderConfig, SettingRow }
import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import board.control.BoardOrchestrator
import board.entity.*
import conversation.entity.ChatRepository
import conversation.entity.api.{ ChatConversation, ConversationEntry, SessionContextLink }
import decision.control.DecisionInbox
import decision.entity.{ Decision, DecisionFilter, DecisionResolutionKind }
import issues.boundary.IssueControllerLive
import issues.control.IssueTemplateServiceLive
import issues.entity.*
import issues.entity.api.AutoAssignIssueResponse
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import orchestration.control.{ AgentConfigResolver, IssueAssignmentOrchestrator, IssueDispatchStatusService }
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId, BoardIssueId, IssueId }
import shared.testfixtures.*
import taskrun.entity.{ TaskArtifactRow, TaskReportRow, TaskRepository, TaskRunRow }
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
    projectId = shared.ids.Ids.ProjectId("test-project"),
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

  private object StubProjectStorageService extends ProjectStorageService:
    override def initProjectStorage(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def projectRoot(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                         =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def boardPath(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                           =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/.board"))
    override def workspaceAnalysisPath(projectId: shared.ids.Ids.ProjectId, workspaceId: String)
      : UIO[java.nio.file.Path] =
      ZIO.succeed(
        java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/workspaces/$workspaceId/.llm4zio/analysis")
      )

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

  private def unlinkedIssueSeedEvents(issueId: IssueId, title: String): List[IssueEvent] =
    List(
      IssueEvent.Created(
        issueId = issueId,
        title = title,
        description = s"$title description",
        issueType = "bug",
        priority = "medium",
        occurredAt = now,
      )
    )

  final private class InMemoryIssueRepository(ref: Ref[Map[IssueId, List[IssueEvent]]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      ref.update(current =>
        current.updatedWith(event.issueId) {
          case Some(events) => Some(events :+ event)
          case None         => Some(List(event))
        }
      )

    override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
      ref.get.flatMap(_.get(id) match
        case Some(events) =>
          ZIO
            .fromEither(AgentIssue.fromEvents(events))
            .mapError(err => PersistenceError.SerializationFailed(s"issue:${id.value}", err))
        case None         => ZIO.fail(PersistenceError.NotFound("issue", id.value)))

    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
      ref.get.map(_.getOrElse(id, Nil))

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ref.get.flatMap(eventsByIssue =>
        ZIO.foreach(eventsByIssue.values.toList)(events =>
          ZIO
            .fromEither(AgentIssue.fromEvents(events))
            .mapError(err => PersistenceError.SerializationFailed("issue:list", err))
        )
      )

    override def delete(id: IssueId): IO[PersistenceError, Unit] =
      ref.update(_ - id)

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
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.dieMessage("unused")

  private object StubAgentRepository extends AgentRepository:
    override def append(event: AgentEvent): IO[PersistenceError, Unit]                    = ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[PersistenceError, Agent]                            = ZIO.succeed(sampleAgent)
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(List(sampleAgent))
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]            =
      ZIO.succeed(List(sampleAgent).find(_.name == name))

  private object StubDispatchStatusService extends IssueDispatchStatusService:
    override def statusFor(issueId: IssueId): IO[PersistenceError, issues.entity.api.DispatchStatusResponse] =
      ZIO.succeed(issues.entity.api.DispatchStatusResponse())
    override def statusesFor(
      issueIds: List[IssueId]
    ): IO[PersistenceError, Map[IssueId, issues.entity.api.DispatchStatusResponse]] =
      ZIO.succeed(Map.empty)

  private object StubDecisionInbox extends DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision]              =
      ZIO.dieMessage("unused")
    override def resolve(
      id: shared.ids.Ids.DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision] = ZIO.dieMessage("unused")
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def escalate(id: shared.ids.Ids.DecisionId, reason: String): IO[PersistenceError, Decision] =
      ZIO.dieMessage("unused")
    override def get(id: shared.ids.Ids.DecisionId): IO[PersistenceError, Decision]                      =
      ZIO.dieMessage("unused")
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]                      = ZIO.succeed(Nil)
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]                      = ZIO.succeed(Nil)

  private object StubBoardOrchestrator extends BoardOrchestrator:
    override def dispatchCycle(workspacePath: String): IO[BoardError, board.entity.DispatchResult]                  =
      ZIO.succeed(board.entity.DispatchResult(Nil, Nil))
    override def assignIssue(workspacePath: String, issueId: BoardIssueId, agentName: String): IO[BoardError, Unit] =
      ZIO.unit
    override def markIssueStarted(
      workspacePath: String,
      issueId: BoardIssueId,
      agentName: String,
      branchName: String,
    ): IO[BoardError, Unit] =
      ZIO.unit
    override def completeIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      success: Boolean,
      details: String,
    ): IO[BoardError, Unit] =
      ZIO.unit

    override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] = ZIO.unit

    override def abortIssueRuns(workspaceId: String, issueId: BoardIssueId): IO[BoardError, Int] =
      ZIO.succeed(0)

  private object StubBoardRepository extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit]                                   = ZIO.unit
    override def readBoard(workspacePath: String): IO[BoardError, Board]                                  =
      ZIO.fail(BoardError.BoardNotFound(workspacePath))
    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue]      =
      ZIO.fail(BoardError.IssueNotFound(issueId.value))
    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      ZIO.fail(BoardError.BoardNotFound(workspacePath))
    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ZIO.fail(BoardError.IssueNotFound(issueId.value))
    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ZIO.fail(BoardError.IssueNotFound(issueId.value))
    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]          = ZIO.unit
    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      ZIO.succeed(Nil)
    override def invalidateWorkspace(workspacePath: String): UIO[Unit]                                    = ZIO.unit

  private object StubAnalysisRepository extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                        = ZIO.dieMessage("unused")
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       = ZIO.dieMessage("unused")
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   =
      ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
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
    override def resolveConfig(agentName: String): IO[PersistenceError, ProviderConfig] =
      ZIO.succeed(ProviderConfig())

  private object StubLlmService extends LlmService:
    def execute(@unused prompt: String): IO[LlmError, LlmResponse]                                      = ZIO.dieMessage("unused")
    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]                        = ZStream.dieMessage("unused")
    def executeWithHistory(@unused messages: List[Message]): IO[LlmError, LlmResponse]                  = ZIO.dieMessage("unused")
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
    val workspaceRepository = StubWorkspaceRepository(List(sampleWorkspace))
    val orchestratorLayer   =
      (ZLayer.succeed(StubChatRepository) ++
        ZLayer.succeed(StubTaskRepository) ++
        ZLayer.succeed(StubLlmService) ++
        ZLayer.succeed(StubAgentConfigResolver) ++
        ZLayer.succeed(NoOpActivityHub) ++
        ZLayer.succeed(issueRepo) ++
        ZLayer.succeed(StubBoardOrchestrator) ++
        ZLayer.succeed(workspaceRepository) ++
        ZLayer.succeed(StubProjectStorageService)) >>> IssueAssignmentOrchestrator.live

    ZIO
      .service[IssueAssignmentOrchestrator]
      .provide(orchestratorLayer)
      .map(orchestrator =>
        IssueControllerLive(
          chatRepository = StubChatRepository,
          taskRepository = StubTaskRepository,
          configRepository = StubConfigRepository.empty,
          agentRepository = StubAgentRepository,
          issueAssignmentOrchestrator = orchestrator,
          issueRepository = issueRepo,
          workspaceRepository = workspaceRepository,
          workspaceRunService = workspaceRunService,
          activityHub = NoOpActivityHub,
          issueDispatchStatusService = StubDispatchStatusService,
          boardOrchestrator = StubBoardOrchestrator,
          boardRepository = StubBoardRepository,
          decisionInbox = StubDecisionInbox,
          analysisRepository = StubAnalysisRepository,
          issueWorkReportProjection = StubWorkReportProjection,
          projectStorageService = StubProjectStorageService,
          templateService = IssueTemplateServiceLive(StubConfigRepository.empty),
        ).routes
      )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueControllerSpec")(
      test(
        "POST /api/issues/:id/auto-assign creates workspace run without persisting issue state events for workspace issues"
      ) {
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
          history     <- issueRepo.history(issueId)
          requests    <- runRequests.get
        yield assertTrue(
          resp.status == Status.Ok,
          parsed.assigned,
          requests.map(_.issueRef) == List("#issue-1"),
          !history.exists(_.isInstanceOf[IssueEvent.Assigned]),
          !history.exists(_.isInstanceOf[IssueEvent.Started]),
        )
      },
      test(
        "POST /api/issues/:id/auto-assign does not append assignment/start events when workspace run creation fails"
      ) {
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
          history     <- issueRepo.history(issueId)
        yield assertTrue(
          resp.status == Status.InternalServerError,
          !history.exists(_.isInstanceOf[IssueEvent.Assigned]),
          !history.exists(_.isInstanceOf[IssueEvent.Started]),
        )
      },
      test("canceled issues remain visible in the board fragment after a board status update") {
        val canceledIssueId = IssueId("issue-canceled")
        for
          issueEvents  <- Ref.make(Map(canceledIssueId -> unlinkedIssueSeedEvents(canceledIssueId, "Canceled issue")))
          runRequests  <- Ref.make(List.empty[AssignRunRequest])
          issueRepo     = InMemoryIssueRepository(issueEvents)
          routes       <- makeRoutes(issueRepo, StubWorkspaceRunService(runRequests, failAssign = false))
          updateReq     = Request.patch(
                            s"/api/issues/${canceledIssueId.value}/status",
                            Body.fromString("""{"status":"Canceled","reason":"Canceled from board"}"""),
                          )
          updateResp   <- routes.runZIO(
                            updateReq.addHeaders(Headers(Header.ContentType(MediaType.application.json)))
                          )
          fragmentResp <- routes.runZIO(Request.get("/board/fragment"))
          fragmentBody <- fragmentResp.body.asString
          serverTiming  = fragmentResp.headers.headers
                            .find(_.headerName.toString.equalsIgnoreCase("Server-Timing"))
                            .map(_.renderedValue)
          renderMs      = fragmentResp.headers.headers
                            .find(_.headerName.toString.equalsIgnoreCase("X-Board-Render-Ms"))
                            .map(_.renderedValue)
        yield assertTrue(
          updateResp.status == Status.Ok,
          fragmentResp.status == Status.Ok,
          serverTiming.exists(_.contains("board_total")),
          renderMs.exists(_.nonEmpty),
          fragmentBody.contains("Canceled"),
          fragmentBody.contains("Canceled issue"),
          fragmentBody.contains("""data-column-status="canceled""""),
        )
      },
      test("POST /issues/:id/status accepts legacy completed alias through canonical status parsing") {
        val legacyIssueId = IssueId("issue-legacy-completed")
        for
          issueEvents <-
            Ref.make(Map(legacyIssueId -> unlinkedIssueSeedEvents(legacyIssueId, "Legacy completed issue")))
          runRequests <- Ref.make(List.empty[AssignRunRequest])
          issueRepo    = InMemoryIssueRepository(issueEvents)
          routes      <- makeRoutes(issueRepo, StubWorkspaceRunService(runRequests, failAssign = false))
          request      = Request.post(
                           URL.decode(s"/issues/${legacyIssueId.value}/status").toOption.get,
                           Body.fromString("status=completed"),
                         ).addHeaders(Headers(Header.ContentType(MediaType.application.`x-www-form-urlencoded`)))
          response    <- routes.runZIO(request)
          history     <- issueRepo.history(legacyIssueId)
        yield assertTrue(
          response.status == Status.SeeOther,
          history.exists(_.isInstanceOf[IssueEvent.MarkedDone]),
        )
      },
    )
