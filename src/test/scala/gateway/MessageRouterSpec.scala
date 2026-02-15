package gateway

import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

import zio.*
import zio.test.*

import _root_.models.*
import db.*
import gateway.models.*
import orchestration.OrchestratorControlPlane

object MessageRouterSpec extends ZIOSpecDefault:

  private def appLayer(dbName: String)
    : ZLayer[Any, Any, MessageRouter & ChannelRegistry & ChatRepository & OrchestratorControlPlane] =
    ZLayer.make[MessageRouter & ChannelRegistry & ChatRepository & OrchestratorControlPlane](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      ChatRepository.live,
      ChannelRegistry.empty,
      MessageRouter.live,
      ZLayer.succeed(MigrationConfig(Paths.get("/tmp/src"), Paths.get("/tmp/out"))),
      OrchestratorControlPlane.live,
    )

  private def makeMessage(
    id: String,
    session: SessionKey,
    direction: MessageDirection,
    role: MessageRole,
    content: String,
    metadata: Map[String, String] = Map.empty,
  ): NormalizedMessage =
    NormalizedMessage(
      id = id,
      channelName = "websocket",
      sessionKey = session,
      direction = direction,
      role = role,
      content = content,
      metadata = metadata,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MessageRouterSpec")(
    test("routeInbound stores session context with conversation and run metadata") {
      val dbName = s"router-inbound-${UUID.randomUUID()}"
      (for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-42")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        _        <- MessageRouter.routeInbound(
                      makeMessage(
                        id = "in-1",
                        session = session,
                        direction = MessageDirection.Inbound,
                        role = MessageRole.User,
                        content = "hello",
                        metadata = Map("conversationId" -> "42", "runId" -> "99"),
                      )
                    )
        context  <- MessageRouter.sessionContext(session)
      yield assertTrue(
        context.flatMap(_.conversationId).contains(42L),
        context.flatMap(_.runId).contains(99L),
        context.flatMap(_.lastInboundMessageId).contains("in-1"),
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
    test("routeOutbound publishes to channel and updates session context") {
      val dbName = s"router-outbound-${UUID.randomUUID()}"
      (for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-7")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- MessageRouter.routeOutbound(
                      makeMessage(
                        id = "out-1",
                        session = session,
                        direction = MessageDirection.Outbound,
                        role = MessageRole.Assistant,
                        content = "response",
                      )
                    )
        out      <- fiber.join
        context  <- MessageRouter.sessionContext(session)
      yield assertTrue(
        out.length == 1,
        out.head.content == "response",
        context.flatMap(_.lastOutboundMessageId).contains("out-1"),
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
    test("resolveSession validates non-empty session id") {
      val dbName = s"router-resolve-${UUID.randomUUID()}"
      (for
        ok  <- MessageRouter.resolveSession("websocket", "55", SessionScopeStrategy.PerRun)
        bad <- MessageRouter.resolveSession("websocket", "   ", SessionScopeStrategy.PerRun).either
      yield assertTrue(
        ok.value == "run:55",
        bad == Left(MessageRouterError.InvalidSession("Session id cannot be empty")),
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
    test("control plane middleware forwards events as outbound normalized messages") {
      val dbName = s"router-cp-${UUID.randomUUID()}"
      (for
        registry <- ZIO.service[ChannelRegistry]
        _        <- ZIO.service[OrchestratorControlPlane]
        channel  <- WebSocketChannel.make()
        _        <- registry.register(channel)
        _        <- MessageRouter.attachControlPlaneRouting("55", "websocket")
        session   = SessionScopeStrategy.PerRun.build("websocket", "55")
        stream   <- channel.outbound(session).take(1).runCollect.fork
        _        <- OrchestratorControlPlane.publishEvent(
                      WorkflowFailed(
                        correlationId = "corr-1",
                        runId = "55",
                        error = "boom",
                        timestamp = Instant.EPOCH,
                      )
                    )
        messages <- stream.join
      yield assertTrue(
        messages.length == 1,
        messages.head.metadata.get("runId").contains("55"),
        messages.head.content.contains("\"error\":\"boom\""),
      )).provideSomeLayer[Scope](appLayer(dbName))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
