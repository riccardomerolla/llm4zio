package web.controllers

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import _root_.config.entity.AIProviderConfig
import activity.control.ActivityHubLive
import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import conversation.boundary.ChatControllerLive
import conversation.entity.api.*
import db.*
import gateway.control.*
import gateway.entity.*
import issues.entity.api.AgentIssueView
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }
import memory.entity.*
import orchestration.control.{ IssueAssignmentOrchestrator, * }
import shared.web.StreamAbortRegistryLive

object ChatControllerGatewaySpec extends ZIOSpecDefault:

  private def appLayer
    : ZLayer[Any, Any, ChatRepository & TaskRepository & GatewayService & ChannelRegistry & LlmService] =
    ZLayer.make[ChatRepository & TaskRepository & GatewayService & ChannelRegistry & LlmService](
      InMemoryChatRepo.layer,
      InMemoryTaskRepo.layer,
      InMemoryConfigRepo.layer,
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          channel  <- WebSocketChannel.make("websocket")
          _        <- registry.register(channel)
        yield ()
      },
      AgentRegistry.live,
      MessageRouter.live,
      EmptyMemoryRepo.layer,
      GatewayService.live,
      TestLlm.layer,
    )

  private object TestLlm:
    val layer: ULayer[LlmService] = ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse(content = s"echo:$prompt", metadata = Map("provider" -> "test")))

        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.succeed(LlmChunk(delta = s"echo:$prompt", finishReason = Some("stop")))

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse(content = "history"))

        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.succeed(ToolCallResponse(content = Some("ok"), toolCalls = Nil, finishReason = "stop"))

        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("unused in test"))

        override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    )

  private object EmptyMemoryRepo:
    val layer: ULayer[MemoryRepository] = ZLayer.succeed(
      new MemoryRepository:
        override def save(entry: MemoryEntry): IO[Throwable, Unit] = ZIO.unit

        override def searchRelevant(
          userId: UserId,
          query: String,
          limit: Int,
          filter: MemoryFilter,
        ): IO[Throwable, List[ScoredMemory]] = ZIO.succeed(Nil)

        override def listForUser(
          userId: UserId,
          filter: MemoryFilter,
          page: Int,
          pageSize: Int,
        ): IO[Throwable, List[MemoryEntry]] = ZIO.succeed(Nil)

        override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] = ZIO.unit

        override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit] = ZIO.unit
    )

  private val testIssueAssignment: IssueAssignmentOrchestrator =
    new IssueAssignmentOrchestrator:
      override def assignIssue(issueId: String, agentName: String): IO[PersistenceError, AgentIssueView] =
        ZIO.fail(PersistenceError.QueryFailed("issue", s"Not found: $issueId"))

  private val stubActivityRepo: ActivityRepository = new ActivityRepository:
    override def createEvent(
      event: ActivityEvent
    ): IO[PersistenceError, _root_.shared.ids.Ids.EventId] = ZIO.succeed(event.id)
    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[java.time.Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] = ZIO.succeed(Nil)

  private val testConfigResolver: AgentConfigResolver =
    new AgentConfigResolver:
      override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
        ZIO.succeed(AIProviderConfig.withDefaults(AIProviderConfig()))

  private val stubHttpClient: HttpClient = new HttpClient:
    override def postJson(
      url: String,
      body: String,
      headers: Map[String, String],
      timeout: Duration,
    ): IO[LlmError, String] =
      ZIO.fail(LlmError.ProviderError("unused in tests", None))

  private val stubCliExecutor: GeminiCliExecutor = new GeminiCliExecutor:
    override def checkGeminiInstalled: IO[LlmError, Unit]                                  = ZIO.unit
    override def runGeminiProcess(prompt: String, config: LlmConfig): IO[LlmError, String] =
      ZIO.fail(LlmError.ProviderError("unused in tests", None))

  private val stubWorkspaceRepo: workspace.entity.WorkspaceRepository = new workspace.entity.WorkspaceRepository:
    override def append(event: workspace.entity.WorkspaceEvent): IO[shared.errors.PersistenceError, Unit]       =
      ZIO.unit
    override def list: IO[shared.errors.PersistenceError, List[workspace.entity.Workspace]]                     =
      ZIO.succeed(Nil)
    override def get(id: String): IO[shared.errors.PersistenceError, Option[workspace.entity.Workspace]]        =
      ZIO.none
    override def delete(id: String): IO[shared.errors.PersistenceError, Unit]                                   =
      ZIO.unit
    override def appendRun(event: workspace.entity.WorkspaceRunEvent): IO[shared.errors.PersistenceError, Unit] =
      ZIO.unit
    override def listRuns(workspaceId: String)
      : IO[shared.errors.PersistenceError, List[workspace.entity.WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[shared.errors.PersistenceError, Option[workspace.entity.WorkspaceRun]]  =
      ZIO.none

  private def newConversation(chatRepository: ChatRepository): IO[PersistenceError, Long] =
    for
      now <- Clock.instant
      id  <- chatRepository.createConversation(
               ChatConversation(
                 title = "Gateway test",
                 createdAt = now,
                 updatedAt = now,
               )
             )
    yield id

  private object InMemoryConfigRepo:
    val layer: ULayer[ConfigRepository] =
      ZLayer.fromZIO(Ref.make(Map.empty[String, String]).map(InMemoryConfigRepoLive.apply))

    final case class InMemoryConfigRepoLive(ref: Ref[Map[String, String]]) extends ConfigRepository:
      override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
        for
          now   <- Clock.instant
          state <- ref.get
        yield state.toList.sortBy(_._1).map { case (k, v) => SettingRow(k, v, now) }

      override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
        for
          now   <- Clock.instant
          state <- ref.get
        yield state.get(key).map(v => SettingRow(key, v, now))

      override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
        ref.update(_.updated(key, value))

      override def deleteSetting(key: String): IO[PersistenceError, Unit] =
        ref.update(_ - key)

      override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
        ref.update(_.filterNot(_._1.startsWith(prefix)))

      override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                =
        ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
      override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 =
        ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
      override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       =
        ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
      override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           =
        ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
      override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                =
        ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
      override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             =
        ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))
      override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
        ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
      override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
        ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
      override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
        ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
      override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
        ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
      override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
        ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
      override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
        ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  private object InMemoryTaskRepo:
    val layer: ULayer[TaskRepository] = ZLayer.succeed(
      new TaskRepository:
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
        override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
        override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.succeed(None)
        override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit
    )

  private object InMemoryChatRepo:
    final case class State(
      nextConversationId: Long,
      nextMessageId: Long,
      conversations: Map[Long, ChatConversation],
      messagesByConversation: Map[Long, List[ConversationEntry]],
      sessionContexts: Map[(String, String), SessionContextLink],
    )

    val layer: ULayer[ChatRepository] =
      ZLayer.fromZIO(
        Ref.make(State(1L, 1L, Map.empty, Map.empty, Map.empty)).map(InMemoryChatRepoLive.apply)
      )

    final case class InMemoryChatRepoLive(ref: Ref[State]) extends ChatRepository:
      override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
        ref.modify { state =>
          val id      = state.nextConversationId
          val updated = state.copy(
            nextConversationId = id + 1,
            conversations = state.conversations.updated(id, conversation.copy(id = Some(id.toString))),
          )
          (id, updated)
        }

      override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
        ref.get.map(_.conversations.get(id))

      override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
        ref.get.map(_.conversations.values.toList.sortBy(_.id.getOrElse("")).slice(offset, offset + limit))

      override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
        ZIO.succeed(Nil)

      override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] =
        ref.get.map(_.conversations.values.toList.filter(_.runId.contains(runId.toString)).sortBy(_.id.getOrElse("")))

      override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit] =
        conversation.id match
          case Some(id) if id.toLongOption.isDefined =>
            ref.update(state => state.copy(conversations = state.conversations.updated(id.toLong, conversation)))
          case Some(_)                               => ZIO.fail(PersistenceError.QueryFailed("updateConversation", "Invalid id"))
          case None                                  => ZIO.fail(PersistenceError.QueryFailed("updateConversation", "Missing id"))

      override def deleteConversation(id: Long): IO[PersistenceError, Unit] =
        ref.update(state =>
          state.copy(
            conversations = state.conversations - id,
            messagesByConversation = state.messagesByConversation - id,
          )
        )

      override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
        ZIO
          .fromOption(message.conversationId.toLongOption)
          .orElseFail(PersistenceError.QueryFailed("addMessage", s"Invalid conversationId: ${message.conversationId}"))
          .flatMap { conversationId =>
            ref.modify { state =>
              val id             = state.nextMessageId
              val updatedMessage = message.copy(id = Some(id.toString))
              val existing       = state.messagesByConversation.getOrElse(conversationId, Nil)
              val updated        = state.copy(
                nextMessageId = id + 1,
                messagesByConversation =
                  state.messagesByConversation.updated(conversationId, existing :+ updatedMessage),
              )
              (id, updated)
            }
          }

      override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
        ref.get.map(_.messagesByConversation.getOrElse(conversationId, Nil))

      override def getMessagesSince(conversationId: Long, since: java.time.Instant)
        : IO[PersistenceError, List[ConversationEntry]] =
        getMessages(conversationId).map(_.filter(_.createdAt.isAfter(since)))

      override def upsertSessionContext(
        channelName: String,
        sessionKey: String,
        contextJson: String,
        updatedAt: java.time.Instant,
      ): IO[PersistenceError, Unit] =
        ref.update(state =>
          state.copy(
            sessionContexts = state.sessionContexts.updated(
              (channelName, sessionKey),
              SessionContextLink(channelName, sessionKey, contextJson, updatedAt),
            )
          )
        )

      override def getSessionContext(
        channelName: String,
        sessionKey: String,
      ): IO[PersistenceError, Option[String]] =
        ref.get.map(_.sessionContexts.get((channelName, sessionKey)).map(_.contextJson))

      override def getSessionContextByConversation(conversationId: Long)
        : IO[PersistenceError, Option[SessionContextLink]] =
        ref.get.map(_.sessionContexts.values.find(_.contextJson.contains(s""""conversationId":$conversationId""")))

      override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
        ref.get.map(_.sessionContexts.values.find(_.contextJson.contains(s""""runId":$taskRunId""")))

      override def deleteSessionContext(
        channelName: String,
        sessionKey: String,
      ): IO[PersistenceError, Unit] =
        ref.update(state => state.copy(sessionContexts = state.sessionContexts - ((channelName, sessionKey))))

      override def listSessionContexts: IO[PersistenceError, List[SessionContextLink]] =
        ref.get.map(_.sessionContexts.values.toList)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatControllerGatewaySpec")(
    test("POST /api/chat/:id/messages preserves API behavior and publishes through gateway") {
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg   <- llm4zio.tools.ToolRegistry.make
        controller = ChatControllerLive(
                       chatRepository = chatRepo,
                       llmService = llm,
                       migrationRepository = migrRepo,
                       issueAssignmentOrchestrator = testIssueAssignment,
                       configResolver = testConfigResolver,
                       gatewayService = gateway,
                       channelRegistry = registry,
                       streamAbortRegistry = abortReg,
                       activityHub = actHub,
                       toolRegistry = toolReg,
                       httpClient = stubHttpClient,
                       cliExecutor = stubCliExecutor,
                       workspaceRepository = stubWorkspaceRepo,
                     )
        request    = Request.post(
                       s"/api/chat/$convId/messages",
                       Body.fromString(ConversationMessageRequest(content = "hello").toJson),
                     )
        response  <- controller.routes.runZIO(request)
        body      <- response.body.asString
        session    = SessionScopeStrategy.PerConversation.build("websocket", convId.toString)
        channel   <- registry.get("websocket")
        emitted   <- channel.outbound(session).take(1).runCollect
        persisted <- chatRepo.getMessages(convId)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("echo:hello"),
        emitted.length == 1,
        emitted.head.content.contains("echo:hello"),
        persisted.count(_.senderType == SenderType.User) == 1,
        persisted.count(_.senderType == SenderType.Assistant) == 1,
      )).provideSomeLayer[Scope](appLayer)
    },
    test("POST /api/chat/:id/messages strips @agent prefix before LLM prompt") {
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg   <- llm4zio.tools.ToolRegistry.make
        controller = ChatControllerLive(
                       chatRepository = chatRepo,
                       llmService = llm,
                       migrationRepository = migrRepo,
                       issueAssignmentOrchestrator = testIssueAssignment,
                       configResolver = testConfigResolver,
                       gatewayService = gateway,
                       channelRegistry = registry,
                       streamAbortRegistry = abortReg,
                       activityHub = actHub,
                       toolRegistry = toolReg,
                       httpClient = stubHttpClient,
                       cliExecutor = stubCliExecutor,
                       workspaceRepository = stubWorkspaceRepo,
                     )
        request    = Request.post(
                       s"/api/chat/$convId/messages",
                       Body.fromString(ConversationMessageRequest(content = "@code-agent fix this module").toJson),
                     )
        response  <- controller.routes.runZIO(request)
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("echo:fix this module"),
      )).provideSomeLayer[Scope](appLayer)
    },
    test("POST /api/chat/:id/messages resolves preferred agent from session context when mention is absent") {
      (for
        chatRepo       <- ZIO.service[ChatRepository]
        migrRepo       <- ZIO.service[TaskRepository]
        llm            <- ZIO.service[LlmService]
        gateway        <- ZIO.service[GatewayService]
        registry       <- ZIO.service[ChannelRegistry]
        convId         <- newConversation(chatRepo)
        now            <- Clock.instant
        _              <- chatRepo.upsertSessionContext(
                            channelName = "websocket",
                            sessionKey = "session-1",
                            contextJson = StoredSessionContext(
                              conversationId = Some(convId),
                              metadata = Map("preferredAgent" -> "task-planner"),
                            ).toJson,
                            updatedAt = now,
                          )
        resolvedAgents <- Ref.make(List.empty[String])
        resolver        = new AgentConfigResolver:
                            override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
                              resolvedAgents.update(_ :+ agentName) *>
                                ZIO.succeed(AIProviderConfig.withDefaults(AIProviderConfig()))
        abortReg       <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub         <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg        <- llm4zio.tools.ToolRegistry.make
        controller      = ChatControllerLive(
                            chatRepository = chatRepo,
                            llmService = llm,
                            migrationRepository = migrRepo,
                            issueAssignmentOrchestrator = testIssueAssignment,
                            configResolver = resolver,
                            gatewayService = gateway,
                            channelRegistry = registry,
                            streamAbortRegistry = abortReg,
                            activityHub = actHub,
                            toolRegistry = toolReg,
                            httpClient = stubHttpClient,
                            cliExecutor = stubCliExecutor,
                            workspaceRepository = stubWorkspaceRepo,
                          )
        request         = Request.post(
                            s"/api/chat/$convId/messages",
                            Body.fromString(ConversationMessageRequest(content = "plan this migration").toJson),
                          )
        response       <- controller.routes.runZIO(request)
        calledWith     <- resolvedAgents.get
      yield assertTrue(
        response.status == Status.Ok,
        calledWith.contains("task-planner"),
      )).provideSomeLayer[Scope](appLayer)
    },
    test("POST /chat/:id/messages (fragment) returns HTML with user message and streams in background") {
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg   <- llm4zio.tools.ToolRegistry.make
        controller = ChatControllerLive(
                       chatRepository = chatRepo,
                       llmService = llm,
                       migrationRepository = migrRepo,
                       issueAssignmentOrchestrator = testIssueAssignment,
                       configResolver = testConfigResolver,
                       gatewayService = gateway,
                       channelRegistry = registry,
                       streamAbortRegistry = abortReg,
                       activityHub = actHub,
                       toolRegistry = toolReg,
                       httpClient = stubHttpClient,
                       cliExecutor = stubCliExecutor,
                       workspaceRepository = stubWorkspaceRepo,
                     )
        request    = Request.post(
                       s"/chat/$convId/messages",
                       Body.fromString("content=hi+there&fragment=true"),
                     )
        response  <- controller.routes.runZIO(request)
        body      <- response.body.asString
        persisted <- chatRepo
                       .getMessages(convId)
                       .repeatUntil(_.count(_.senderType == SenderType.User) >= 1)
                       .timeoutFail(PersistenceError.QueryFailed("timeout", "user message not persisted"))(
                         10.seconds
                       )
        streamed  <- chatRepo
                       .getMessages(convId)
                       .orElseSucceed(Nil)
                       .repeatUntil(_.count(_.senderType == SenderType.Assistant) >= 1)
                       .timeout(5.seconds)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("user"),
        persisted.count(_.senderType == SenderType.User) == 1,
        streamed.forall(_.count(_.senderType == SenderType.Assistant) == 1),
      )).provideSomeLayer[Scope](appLayer)
    } @@ TestAspect.withLiveClock,
    test("GET /api/sessions ignores malformed session context payloads") {
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        now       <- Clock.instant
        _         <- chatRepo.upsertSessionContext(
                       channelName = "websocket",
                       sessionKey = "valid-session",
                       contextJson = StoredSessionContext(
                         conversationId = Some(convId),
                         runId = Some(999L),
                         metadata = Map("preferredAgent" -> "code-agent"),
                       ).toJson,
                       updatedAt = now,
                     )
        _         <- chatRepo.upsertSessionContext(
                       channelName = "websocket",
                       sessionKey = "broken-session",
                       contextJson = """{"conversationId":{"bad":"shape"}}""",
                       updatedAt = now,
                     )
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg   <- llm4zio.tools.ToolRegistry.make
        controller = ChatControllerLive(
                       chatRepository = chatRepo,
                       llmService = llm,
                       migrationRepository = migrRepo,
                       issueAssignmentOrchestrator = testIssueAssignment,
                       configResolver = testConfigResolver,
                       gatewayService = gateway,
                       channelRegistry = registry,
                       streamAbortRegistry = abortReg,
                       activityHub = actHub,
                       toolRegistry = toolReg,
                       httpClient = stubHttpClient,
                       cliExecutor = stubCliExecutor,
                       workspaceRepository = stubWorkspaceRepo,
                     )
        response  <- controller.routes.runZIO(Request.get("/api/sessions"))
        body      <- response.body.asString
        sessions  <- ZIO
                       .fromEither(body.fromJson[List[ChatSession]])
                       .mapError(err => PersistenceError.QueryFailed("json_parse", err))
      yield assertTrue(
        response.status == Status.Ok,
        sessions.exists(_.sessionId == "websocket:valid-session"),
        sessions.forall(_.sessionId != "websocket:broken-session"),
      )).provideSomeLayer[Scope](appLayer)
    } @@ TestAspect.withLiveClock,
    test("POST /api/chat/:id/messages with toolsEnabled routes through tool loop") {
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
        toolReg   <- llm4zio.tools.ToolRegistry.make
        controller = ChatControllerLive(
                       chatRepository = chatRepo,
                       llmService = llm,
                       migrationRepository = migrRepo,
                       issueAssignmentOrchestrator = testIssueAssignment,
                       configResolver = testConfigResolver,
                       gatewayService = gateway,
                       channelRegistry = registry,
                       streamAbortRegistry = abortReg,
                       activityHub = actHub,
                       toolRegistry = toolReg,
                       httpClient = stubHttpClient,
                       cliExecutor = stubCliExecutor,
                       workspaceRepository = stubWorkspaceRepo,
                     )
        request    = Request.post(
                       s"/api/chat/$convId/messages",
                       Body.fromString(
                         ConversationMessageRequest(
                           content = "test with tools",
                           metadata = Some("""{"toolsEnabled":"true"}"""),
                         ).toJson
                       ),
                     )
        response  <- controller.routes.runZIO(request)
        body      <- response.body.asString
        persisted <- chatRepo.getMessages(convId)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("ok"),
        persisted.count(_.senderType == SenderType.User) == 1,
        persisted.count(_.senderType == SenderType.Assistant) == 1,
      )).provideSomeLayer[Scope](appLayer)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
