package gateway

import java.time.Instant

import zio.*
import zio.test.*

import gateway.models.*

object ChannelRegistrySpec extends ZIOSpecDefault:

  private def msg(
    id: String,
    session: SessionKey,
    direction: MessageDirection,
    content: String,
  ): NormalizedMessage =
    NormalizedMessage(
      id = id,
      channelName = "websocket",
      sessionKey = session,
      direction = direction,
      role = MessageRole.User,
      content = content,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChannelRegistrySpec")(
    test("register/get/list manage channels") {
      for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        _        <- registry.register(channel)
        listed   <- registry.list
        loaded   <- registry.get("websocket")
      yield assertTrue(
        listed.map(_.name) == List("websocket"),
        loaded.name == "websocket",
      )
    },
    test("publish delegates outbound send to target channel") {
      for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "42")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- ZIO.yieldNow.repeatN(20)
        _        <- registry.publish("websocket", msg("m1", session, MessageDirection.Outbound, "hello"))
        out      <- fiber.join
      yield assertTrue(
        out.length == 1,
        out.head.content == "hello",
      )
    },
    test("inboundMerged merges inbound messages from registered channels") {
      for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "100")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        stream   <- registry.inboundMerged.take(1).runCollect.fork
        _        <- ZIO.yieldNow.repeatN(20)
        _        <- channel.receive(msg("m-in", session, MessageDirection.Inbound, "incoming"))
        merged   <- stream.join
      yield assertTrue(
        merged.length == 1,
        merged.head.id == "m-in",
      )
    },
    test("session strategy builds scoped keys") {
      val runKey  = SessionScopeStrategy.PerRun.build("websocket", "7")
      val userKey = SessionScopeStrategy.PerUser.build("websocket", "alice")
      assertTrue(
        runKey.value == "run:7",
        userKey.value == "user:alice",
        runKey.asString == "websocket:run:7",
      )
    },
  ).provideLayer(ChannelRegistry.empty) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
