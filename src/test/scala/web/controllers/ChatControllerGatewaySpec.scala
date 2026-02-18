package web.controllers
import java.util.UUID

import zio.*
import zio.http.*
import zio.json.EncoderOps
import zio.stream.ZStream
import zio.test.*

import agents.AgentRegistry
import _root_.models.*
import db.*
import gateway.*
import gateway.models.*
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import orchestration.*
import web.{ ActivityHubLive, StreamAbortRegistryLive }

object ChatControllerGatewaySpec extends ZIOSpecDefault:

  private def appLayer(
    dbName: String
  ): ZLayer[Any, Any, ChatRepository & TaskRepository & GatewayService & ChannelRegistry & LlmService] =
    ZLayer.make[ChatRepository & TaskRepository & GatewayService & ChannelRegistry & LlmService](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      ChatRepository.live,
      TaskRepository.live,
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

  private val testIssueAssignment: IssueAssignmentOrchestrator =
    new IssueAssignmentOrchestrator:
      override def assignIssue(issueId: Long, agentName: String): IO[PersistenceError, AgentIssue] =
        ZIO.fail(PersistenceError.NotFound("issue", issueId))

  private val stubActivityRepo: db.ActivityRepository = new db.ActivityRepository:
    override def createEvent(event: ActivityEvent): IO[PersistenceError, Long] = ZIO.succeed(1L)
    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[java.time.Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] = ZIO.succeed(Nil)

  private val testConfigResolver: AgentConfigResolver =
    new AgentConfigResolver:
      override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
        ZIO.succeed(AIProviderConfig.withDefaults(AIProviderConfig()))

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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatControllerGatewaySpec")(
    test("POST /api/chat/:id/messages preserves API behavior and publishes through gateway") {
      val dbName = s"chat-gateway-api-${UUID.randomUUID()}"
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
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
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
    test("POST /api/chat/:id/messages strips @agent prefix before LLM prompt") {
      val dbName = s"chat-gateway-mention-${UUID.randomUUID()}"
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
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
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
    test("POST /chat/:id/messages (fragment) returns HTML with user message and streams in background") {
      val dbName = s"chat-gateway-web-${UUID.randomUUID()}"
      (for
        chatRepo  <- ZIO.service[ChatRepository]
        migrRepo  <- ZIO.service[TaskRepository]
        llm       <- ZIO.service[LlmService]
        gateway   <- ZIO.service[GatewayService]
        registry  <- ZIO.service[ChannelRegistry]
        convId    <- newConversation(chatRepo)
        abortReg  <- Ref.make(Map.empty[Long, UIO[Unit]]).map(StreamAbortRegistryLive.apply)
        actHub    <- Ref.make(Set.empty[Queue[ActivityEvent]]).map(subs => ActivityHubLive(stubActivityRepo, subs))
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
      )).provideSomeLayer[Scope](appLayer(dbName))
    } @@ TestAspect.withLiveClock,
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
