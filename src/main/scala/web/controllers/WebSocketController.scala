package web.controllers

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.json.*
import zio.stream.ZStream

import core.HealthMonitor
import gateway.models.SessionScopeStrategy
import gateway.{ ChannelRegistry, MessageChannelError }
import orchestration.OrchestratorControlPlane
import web.{ ActivityHub, StreamAbortRegistry }
import web.ws.{ ClientMessage, ServerMessage, SubscriptionTopic }

trait WebSocketController:
  def routes: Routes[Any, Response]

object WebSocketController:

  def routes: ZIO[WebSocketController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[WebSocketController](_.routes)

  val live
    : ZLayer[
      ChannelRegistry & StreamAbortRegistry & HealthMonitor & OrchestratorControlPlane & ActivityHub,
      Nothing,
      WebSocketController,
    ] =
    ZLayer.fromFunction(WebSocketControllerLive.apply)

final case class WebSocketControllerLive(
  channelRegistry: ChannelRegistry,
  streamAbortRegistry: StreamAbortRegistry,
  healthMonitor: HealthMonitor,
  controlPlane: OrchestratorControlPlane,
  activityHub: ActivityHub,
) extends WebSocketController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "ws" / "console" -> handler {
      Handler.webSocket { socket =>
        ZIO.scoped {
          for
            subscriptions <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, Unit]])
            _             <- channelRegistry.markConnected("websocket")
            _             <- socket.receiveAll(event => handleChannelEvent(socket, subscriptions, event))
              .ensuring(stopAllSubscriptions(subscriptions))
              .ensuring(channelRegistry.markDisconnected("websocket"))
          yield ()
        }
      }.toResponse
    }
  )

  private def handleChannelEvent(
    socket: zio.http.WebSocketChannel,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    event: ChannelEvent[WebSocketFrame],
  ): UIO[Unit] =
    event match
      case Read(WebSocketFrame.Text(text)) =>
        handleClientMessage(socket, subscriptions, text)
      case Read(WebSocketFrame.Close(_, _)) =>
        stopAllSubscriptions(subscriptions)
      case ChannelEvent.Unregistered =>
        stopAllSubscriptions(subscriptions)
      case ChannelEvent.ExceptionCaught(cause) =>
        sendServerMessage(
          socket,
          ServerMessage.Error("ws_exception", Option(cause.getMessage).getOrElse(cause.toString), nowMillis)
        )
      case _                               =>
        ZIO.unit

  private def handleClientMessage(
    socket: zio.http.WebSocketChannel,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    raw: String,
  ): UIO[Unit] =
    ZIO.fromEither(raw.fromJson[ClientMessage]).foldZIO(
      err => sendServerMessage(socket, ServerMessage.Error("invalid_message", err, nowMillis)),
      {
        case ClientMessage.Ping(ts)              =>
          sendServerMessage(socket, ServerMessage.Pong(ts))
        case ClientMessage.AbortChat(convId)     =>
          handleAbortChat(socket, convId)
        case ClientMessage.Subscribe(topic, _)   =>
          subscribe(socket, subscriptions, topic)
        case ClientMessage.Unsubscribe(topic)    =>
          unsubscribe(socket, subscriptions, topic)
      },
    )

  private def handleAbortChat(
    socket: zio.http.WebSocketChannel,
    conversationId: Long,
  ): UIO[Unit] =
    streamAbortRegistry.abort(conversationId).flatMap { aborted =>
      if aborted then
        sendServerMessage(
          socket,
          ServerMessage.Event(
            topic = s"chat:$conversationId:stream",
            eventType = "chat-aborted",
            payload = """{"type":"chat-aborted","delta":""}""",
            ts = nowMillis,
          ),
        )
      else
        sendServerMessage(
          socket,
          ServerMessage.Error("abort_not_found", s"No active stream for conversation $conversationId", nowMillis)
        )
    }

  private def subscribe(
    socket: zio.http.WebSocketChannel,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    topic: String,
  ): UIO[Unit] =
    subscriptions.get.flatMap { current =>
      if current.contains(topic) then
        sendServerMessage(socket, ServerMessage.Subscribed(topic, nowMillis))
      else
        SubscriptionTopic.parse(topic) match
          case Left(err)     =>
            sendServerMessage(socket, ServerMessage.Error("invalid_topic", err, nowMillis))
          case Right(parsed) =>
            startSubscription(socket, topic, parsed).flatMap { fiber =>
              subscriptions.update(_ + (topic -> fiber)) *>
                sendServerMessage(socket, ServerMessage.Subscribed(topic, nowMillis))
            }
    }

  private def unsubscribe(
    socket: zio.http.WebSocketChannel,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    topic: String,
  ): UIO[Unit] =
    subscriptions
      .modify(current => (current.get(topic), current - topic))
      .flatMap(fiber => ZIO.foreachDiscard(fiber)(_.interrupt))
      .ignore *>
      sendServerMessage(socket, ServerMessage.Unsubscribed(topic, nowMillis))

  private def startSubscription(
    socket: zio.http.WebSocketChannel,
    topic: String,
    parsed: SubscriptionTopic,
  ): UIO[Fiber.Runtime[Nothing, Unit]] =
    parsed match
      case SubscriptionTopic.ChatStream(conversationId)   =>
        startChatSubscription(socket, topic, conversationId)
      case SubscriptionTopic.ChatMessages(conversationId) =>
        startChatSubscription(socket, topic, conversationId)
      case SubscriptionTopic.HealthMetrics                =>
        healthMonitor
          .stream(2.seconds)
          .mapZIO(snapshot =>
            sendServerMessage(
              socket,
              ServerMessage.Event(topic, "health-metrics", snapshot.toJson, snapshot.ts),
            )
          )
          .runDrain
          .forkDaemon
      case SubscriptionTopic.AgentsActivity               =>
        ZStream
          .repeatZIOWithSchedule(
            controlPlane.getAgentMonitorSnapshot.zip(controlPlane.getAgentExecutionHistory(120)).either,
            Schedule.spaced(2.seconds),
          )
          .mapZIO {
            case Right((snapshot, history)) =>
              val payload = s"""{"snapshot":${snapshot.toJson},"history":${history.toJson}}"""
              sendServerMessage(
                socket,
                ServerMessage.Event(topic, "agents-activity", payload, nowMillis),
              )
            case Left(err)                 =>
              sendServerMessage(
                socket,
                ServerMessage.Error("agents_activity_error", err.toString, nowMillis),
              )
          }
          .runDrain
          .forkDaemon
      case SubscriptionTopic.ActivityFeed                 =>
        ZIO.scoped {
          for
            queue <- activityHub.subscribe
            _     <- ZStream
                       .fromQueue(queue)
                       .mapZIO(event =>
                         sendServerMessage(
                           socket,
                           ServerMessage.Event(topic, "activity-feed", event.toJson, event.createdAt.toEpochMilli),
                         )
                       )
                       .runDrain
          yield ()
        }.forkDaemon
      case SubscriptionTopic.RunProgress(_)               =>
        sendServerMessage(
          socket,
          ServerMessage.Error("unsupported_topic", s"Topic not wired yet: $topic", nowMillis),
        ) *> ZIO.never.forkDaemon
      case SubscriptionTopic.DashboardRecentRuns          =>
        sendServerMessage(
          socket,
          ServerMessage.Error("unsupported_topic", s"Topic not wired yet: $topic", nowMillis),
        ) *> ZIO.never.forkDaemon

  private def startChatSubscription(
    socket: zio.http.WebSocketChannel,
    topic: String,
    conversationId: Long,
  ): UIO[Fiber.Runtime[Nothing, Unit]] =
    val sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
    channelRegistry.get("websocket").foldZIO(
      err =>
        sendServerMessage(
          socket,
          ServerMessage.Error("channel_error", err.toString, nowMillis),
        ) *> ZIO.never.forkDaemon,
      channel =>
        (for
          _ <- channel.open(sessionKey)
          now <- Clock.instant
          _   <- channelRegistry.markActivity("websocket", now)
          _ <- channel.outbound(sessionKey)
                 .mapZIO(msg =>
                   sendServerMessage(
                     socket,
                     ServerMessage.Event(
                       topic = topic,
                       eventType = msg.metadata.getOrElse("streamEventType", "chat-message"),
                       payload = msg.content,
                       ts = msg.timestamp.toEpochMilli,
                     ),
                   )
                 )
                 .runDrain
        yield ())
          .catchAll {
            case MessageChannelError.ChannelClosed(name) =>
              sendServerMessage(
                socket,
                ServerMessage.Error("channel_closed", s"Channel $name is closed", nowMillis),
              )
            case MessageChannelError.SessionNotConnected(_, _) =>
              sendServerMessage(
                socket,
                ServerMessage.Error("session_not_connected", s"Session not connected for $topic", nowMillis),
              )
            case err                                    =>
              sendServerMessage(socket, ServerMessage.Error("stream_error", err.toString, nowMillis))
          }
          .ensuring(channel.close(sessionKey))
          .forkDaemon,
    )

  private def stopAllSubscriptions(
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]]
  ): UIO[Unit] =
    subscriptions.modify(current => (current.values.toList, Map.empty[String, Fiber.Runtime[Nothing, Unit]])).flatMap {
      fibers =>
        ZIO.foreachDiscard(fibers)(_.interrupt)
    }

  private def sendServerMessage(
    socket: zio.http.WebSocketChannel,
    message: ServerMessage,
  ): UIO[Unit] =
    socket.send(Read(WebSocketFrame.text(message.toJson))).unit.ignore

  private def nowMillis: Long =
    java.lang.System.currentTimeMillis()
