package gateway

import java.time.Instant
import java.util.UUID

import zio.*
import zio.stream.ZStream
import zio.test.*

import agents.AgentRegistry
import db.*
import gateway.models.*
import llm4zio.core.{ LlmChunk, LlmError, LlmResponse, LlmService, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }

object GatewayServiceSpec extends ZIOSpecDefault:

  private def baseLayer(dbName: String): ZLayer[Any, Any, GatewayService & ChannelRegistry & MessageRouter] =
    ZLayer.make[GatewayService & ChannelRegistry & MessageRouter](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      ChatRepository.live,
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          web      <- WebSocketChannel.make("websocket")
          telegram <- WebSocketChannel.make("telegram")
          _        <- registry.register(web)
          _        <- registry.register(telegram)
        yield ()
      },
      AgentRegistry.live,
      TestLlm.layer,
      MessageRouter.live,
      GatewayService.live,
    )

  private def steeringLayer(dbName: String)
    : ZLayer[Any, Any, GatewayService & ChannelRegistry & Queue[NormalizedMessage]] =
    ZLayer.make[GatewayService & ChannelRegistry & Queue[NormalizedMessage]](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      ChatRepository.live,
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          web      <- WebSocketChannel.make("websocket")
          _        <- registry.register(web)
        yield ()
      },
      AgentRegistry.live,
      TestLlm.layer,
      MessageRouter.live,
      ZLayer.fromZIO(Queue.unbounded[NormalizedMessage]),
      GatewayService.liveWithSteeringQueue,
    )

  private object TestLlm:
    val layer: ULayer[LlmService] = ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse("""{"agent":"code-agent","confidence":0.93}"""))

        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse("history"))

        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.succeed(ToolCallResponse(None, Nil, "stop"))

        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("unused"))

        override def isAvailable: UIO[Boolean] =
          ZIO.succeed(true)
    )

  private def message(
    id: String,
    channelName: String,
    session: SessionKey,
    content: String,
    direction: MessageDirection = MessageDirection.Outbound,
    role: MessageRole = MessageRole.Assistant,
  ): NormalizedMessage =
    NormalizedMessage(
      id = id,
      channelName = channelName,
      sessionKey = session,
      direction = direction,
      role = role,
      content = content,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GatewayServiceSpec")(
    test("processOutbound chunks and routes channel-limited responses") {
      val dbName = s"gateway-process-${UUID.randomUUID()}"
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-1")
        _        <- channel.open(session)
        outbound  = message("m-1", "telegram", session, "a" * 4100)
        chunks   <- gateway.processOutbound(outbound)
        seen     <- channel.outbound(session).take(2).runCollect
      yield assertTrue(
        chunks.length == 2,
        seen.length == 2,
        chunks.forall(_.metadata.get("chunked").contains("true")),
      )).provideSomeLayer[Scope](baseLayer(dbName))
    },
    test("enqueueOutbound processes asynchronously and updates metrics") {
      val dbName = s"gateway-queue-${UUID.randomUUID()}"
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("websocket")
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-2")
        _        <- channel.open(session)
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- gateway.enqueueOutbound(message("m-2", "websocket", session, "hello async"))
        out      <- fiber.join
        _        <- gateway.metrics.map(_.processed).repeat(Schedule.recurWhile(_ < 1L))
        metrics  <- gateway.metrics
      yield assertTrue(
        out.length == 1,
        metrics.enqueued >= 1L,
        metrics.processed >= 1L,
      )).provideSomeLayer[Scope](baseLayer(dbName))
    },
    test("steering mode forwards messages to injected queue") {
      val dbName = s"gateway-steering-${UUID.randomUUID()}"
      (for
        gateway  <- ZIO.service[GatewayService]
        steering <- ZIO.service[Queue[NormalizedMessage]]
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-3")
        inbound   = message(
                      id = "in-1",
                      channelName = "websocket",
                      session = session,
                      content = "steer this",
                      direction = MessageDirection.Inbound,
                    )
        _        <- gateway.processInbound(inbound)
        steered  <- steering.take
        metrics  <- gateway.metrics
      yield assertTrue(
        steered.id == "in-1",
        metrics.steeringForwarded >= 1L,
      )).provideSomeLayer[Scope](steeringLayer(dbName))
    },
    test("telegram inbound natural language routes to an agent response") {
      val dbName = s"gateway-intent-route-${UUID.randomUUID()}"
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-intent-1")
        _        <- channel.open(session)
        inbound   = message(
                      id = "in-nl-1",
                      channelName = "telegram",
                      session = session,
                      content = "please analyze this cobol program",
                      direction = MessageDirection.Inbound,
                      role = MessageRole.User,
                    )
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- gateway.processInbound(inbound)
        out      <- fiber.join
      yield assertTrue(
        out.nonEmpty,
        out.head.metadata.get("intent.agent").contains("code-agent"),
      )).provideSomeLayer[Scope](baseLayer(dbName))
    },
    test("telegram multi-turn clarification routes based on follow-up answer") {
      val dbName = s"gateway-intent-clarify-${UUID.randomUUID()}"
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-intent-2")
        _        <- channel.open(session)
        first     = message(
                      id = "in-nl-2",
                      channelName = "telegram",
                      session = session,
                      content = "hello, I need some help",
                      direction = MessageDirection.Inbound,
                      role = MessageRole.User,
                    )
        second    = message(
                      id = "in-nl-3",
                      channelName = "telegram",
                      session = session,
                      content = "2",
                      direction = MessageDirection.Inbound,
                      role = MessageRole.User,
                    )
        fiber    <- channel.outbound(session).take(2).runCollect.fork
        _        <- gateway.processInbound(first)
        _        <- gateway.processInbound(second)
        out      <- fiber.join
      yield assertTrue(
        out.length == 2,
        out.head.content.contains("Routing request to"),
        out(1).metadata.get("intent.agent").contains("code-agent"),
      )).provideSomeLayer[Scope](baseLayer(dbName))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
