package conversation.boundary

import java.util.concurrent.TimeUnit

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.json.*
import zio.stream.ZStream

import activity.control.ActivityHub
import app.control.HealthMonitor
import gateway.control.{ ChannelRegistry, MessageChannelError }
import gateway.entity.SessionScopeStrategy
import orchestration.control.OrchestratorControlPlane
import shared.web.StreamAbortRegistry
import shared.web.ws.{ ClientMessage, ServerMessage, SubscriptionTopic }
import workspace.control.RunSessionManager

trait WebSocketController:
  def routes: Routes[Any, Response]

object WebSocketController:

  def routes: ZIO[WebSocketController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[WebSocketController](_.routes)

  val live
    : ZLayer[
      ChannelRegistry & StreamAbortRegistry & HealthMonitor & OrchestratorControlPlane & ActivityHub & RunSessionManager,
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
  runSessionManager: RunSessionManager,
) extends WebSocketController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "ws" / "console" -> handler {
      Handler.webSocket { socket =>
        ZIO.scoped {
          for
            userId        <- Random.nextUUID.map(uuid => s"ws-user-${uuid.toString.take(8)}")
            subscriptions <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, Unit]])
            _             <- channelRegistry.markConnected("websocket")
            _             <- socket.receiveAll(event => handleChannelEvent(socket, userId, subscriptions, event))
                               .ensuring(stopAllSubscriptions(subscriptions))
                               .ensuring(channelRegistry.markDisconnected("websocket"))
          yield ()
        }
      }.toResponse
    }
  )

  private def handleChannelEvent(
    socket: zio.http.WebSocketChannel,
    userId: String,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    event: ChannelEvent[WebSocketFrame],
  ): UIO[Unit] =
    event match
      case Read(WebSocketFrame.Text(text))     =>
        handleClientMessage(socket, userId, subscriptions, text)
      case Read(WebSocketFrame.Close(_, _))    =>
        stopAllSubscriptions(subscriptions)
      case ChannelEvent.Unregistered           =>
        stopAllSubscriptions(subscriptions)
      case ChannelEvent.ExceptionCaught(cause) =>
        withNow(ts =>
          sendServerMessage(
            socket,
            ServerMessage.Error("ws_exception", Option(cause.getMessage).getOrElse(cause.toString), ts),
          )
        )
      case _                                   =>
        ZIO.unit

  private def handleClientMessage(
    socket: zio.http.WebSocketChannel,
    userId: String,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    raw: String,
  ): UIO[Unit] =
    ZIO.fromEither(raw.fromJson[ClientMessage]).foldZIO(
      err => withNow(ts => sendServerMessage(socket, ServerMessage.Error("invalid_message", err, ts))),
      {
        case ClientMessage.Ping(ts)                       =>
          sendServerMessage(socket, ServerMessage.Pong(ts))
        case ClientMessage.AbortChat(convId)              =>
          handleAbortChat(socket, convId)
        case ClientMessage.Subscribe(topic, _)            =>
          subscribe(socket, subscriptions, topic)
        case ClientMessage.Unsubscribe(topic)             =>
          unsubscribe(socket, subscriptions, topic)
        case ClientMessage.AttachToRun(runId)             =>
          handleAttachToRun(socket, userId, runId)
        case ClientMessage.DetachFromRun(runId)           =>
          handleDetachFromRun(socket, userId, runId)
        case ClientMessage.InterruptRun(runId)            =>
          handleInterruptRun(socket, userId, runId)
        case ClientMessage.ContinueRun(runId, prompt)     =>
          handleContinueRun(socket, userId, runId, prompt)
        case ClientMessage.SendRunMessage(runId, content) =>
          handleSendRunMessage(socket, userId, runId, content)
      },
    )

  private def handleAttachToRun(socket: zio.http.WebSocketChannel, userId: String, runId: String): UIO[Unit] =
    (for
      oldState <- runSessionManager.getSession(runId).map(_.status.toString)
      session  <- runSessionManager.attach(runId, userId)
      _        <- withNow(ts =>
                    sendServerMessage(
                      socket,
                      ServerMessage.RunStateChanged(runId, oldState, session.status.toString, ts),
                    )
                  )
    yield ()).foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.RunInputRejected(runId, s"Attach failed: ${workspaceError(err)}", ts))
        ),
      _ => ZIO.unit,
    )

  private def handleDetachFromRun(socket: zio.http.WebSocketChannel, userId: String, runId: String): UIO[Unit] =
    (for
      oldState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- runSessionManager.detach(runId, userId)
      newState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- withNow(ts =>
                    sendServerMessage(
                      socket,
                      ServerMessage.RunStateChanged(runId, oldState, newState, ts),
                    )
                  )
    yield ()).foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.RunInputRejected(runId, s"Detach failed: ${workspaceError(err)}", ts))
        ),
      _ => ZIO.unit,
    )

  private def handleInterruptRun(socket: zio.http.WebSocketChannel, userId: String, runId: String): UIO[Unit] =
    (for
      oldState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- runSessionManager.interrupt(runId, userId)
      newState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- withNow(ts =>
                    sendServerMessage(
                      socket,
                      ServerMessage.RunStateChanged(runId, oldState, newState, ts),
                    )
                  )
    yield ()).foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(
            socket,
            ServerMessage.RunInputRejected(runId, s"Interrupt failed: ${workspaceError(err)}", ts),
          )
        ),
      _ => ZIO.unit,
    )

  private def handleContinueRun(
    socket: zio.http.WebSocketChannel,
    userId: String,
    runId: String,
    prompt: String,
  ): UIO[Unit] =
    (for
      oldState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- runSessionManager.resume(runId, userId, prompt)
      newState <- runSessionManager.getSession(runId).map(_.status.toString)
      _        <- withNow(ts =>
                    sendServerMessage(
                      socket,
                      ServerMessage.RunStateChanged(runId, oldState, newState, ts),
                    )
                  )
    yield ()).foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(
            socket,
            ServerMessage.RunInputRejected(runId, s"Continue failed: ${workspaceError(err)}", ts),
          )
        ),
      _ => ZIO.unit,
    )

  private def handleSendRunMessage(
    socket: zio.http.WebSocketChannel,
    userId: String,
    runId: String,
    content: String,
  ): UIO[Unit] =
    runSessionManager.sendMessage(runId, userId, content).foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.RunInputRejected(runId, workspaceError(err), ts))
        ),
      {
        case Right(accepted) =>
          withNow(ts =>
            sendServerMessage(socket, ServerMessage.RunInputAccepted(runId, accepted.messageId.toString, ts))
          )
        case Left(rejected)  =>
          withNow(ts =>
            sendServerMessage(socket, ServerMessage.RunInputRejected(runId, rejected.reason, ts))
          )
      },
    )

  private def handleAbortChat(
    socket: zio.http.WebSocketChannel,
    conversationId: Long,
  ): UIO[Unit] =
    streamAbortRegistry.abort(conversationId).flatMap { aborted =>
      if aborted then
        withNow(ts =>
          sendServerMessage(
            socket,
            ServerMessage.Event(
              topic = s"chat:$conversationId:stream",
              eventType = "chat-aborted",
              payload = """{"type":"chat-aborted","delta":""}""",
              ts = ts,
            ),
          )
        )
      else
        withNow(ts =>
          sendServerMessage(
            socket,
            ServerMessage.Error("abort_not_found", s"No active stream for conversation $conversationId", ts),
          )
        )
    }

  private def subscribe(
    socket: zio.http.WebSocketChannel,
    subscriptions: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    topic: String,
  ): UIO[Unit] =
    subscriptions.get.flatMap { current =>
      if current.contains(topic) then
        withNow(ts => sendServerMessage(socket, ServerMessage.Subscribed(topic, ts)))
      else
        SubscriptionTopic.parse(topic) match
          case Left(err)     =>
            withNow(ts => sendServerMessage(socket, ServerMessage.Error("invalid_topic", err, ts)))
          case Right(parsed) =>
            startSubscription(socket, topic, parsed).flatMap { fiber =>
              subscriptions.update(_ + (topic -> fiber)) *>
                withNow(ts => sendServerMessage(socket, ServerMessage.Subscribed(topic, ts)))
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
      withNow(ts => sendServerMessage(socket, ServerMessage.Unsubscribed(topic, ts)))

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
              withNow(ts => sendServerMessage(socket, ServerMessage.Event(topic, "agents-activity", payload, ts)))
            case Left(err)                  =>
              withNow(ts => sendServerMessage(socket, ServerMessage.Error("agents_activity_error", err.toString, ts)))
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
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.Error("unsupported_topic", s"Topic not wired yet: $topic", ts))
        ) *>
          ZIO.never.forkDaemon
      case SubscriptionTopic.DashboardRecentRuns          =>
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.Error("unsupported_topic", s"Topic not wired yet: $topic", ts))
        ) *>
          ZIO.never.forkDaemon

  private def startChatSubscription(
    socket: zio.http.WebSocketChannel,
    topic: String,
    conversationId: Long,
  ): UIO[Fiber.Runtime[Nothing, Unit]] =
    val sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
    channelRegistry.get("websocket").foldZIO(
      err =>
        withNow(ts =>
          sendServerMessage(socket, ServerMessage.Error("channel_error", err.toString, ts))
        ) *> ZIO.never.forkDaemon,
      channel =>
        (for
          _   <- channel.open(sessionKey)
          now <- Clock.instant
          _   <- channelRegistry.markActivity("websocket", now)
          _   <- channel.outbound(sessionKey)
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
            case MessageChannelError.ChannelClosed(name)       =>
              withNow(ts =>
                sendServerMessage(socket, ServerMessage.Error("channel_closed", s"Channel $name is closed", ts))
              )
            case MessageChannelError.SessionNotConnected(_, _) =>
              withNow(ts =>
                sendServerMessage(
                  socket,
                  ServerMessage.Error("session_not_connected", s"Session not connected for $topic", ts),
                )
              )
            case err                                           =>
              withNow(ts => sendServerMessage(socket, ServerMessage.Error("stream_error", err.toString, ts)))
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

  private def nowMillis: UIO[Long] =
    Clock.currentTime(TimeUnit.MILLISECONDS)

  private def withNow(effect: Long => UIO[Unit]): UIO[Unit] =
    nowMillis.flatMap(effect)

  private def workspaceError(err: workspace.entity.WorkspaceError): String =
    err match
      case workspace.entity.WorkspaceError.NotFound(id)                                   => s"Run not found: $id"
      case workspace.entity.WorkspaceError.Disabled(id)                                   => s"Workspace disabled: $id"
      case workspace.entity.WorkspaceError.WorktreeError(message)                         => message
      case workspace.entity.WorkspaceError.InvalidRunState(_, _, actual)                  => s"Invalid run state: $actual"
      case workspace.entity.WorkspaceError.ControllerConflict(_, controller, requestedBy) =>
        s"Run is controlled by $controller (requested by $requestedBy)"
      case workspace.entity.WorkspaceError.InteractiveProcessUnavailable(runId)           =>
        s"Interactive process unavailable for $runId"
      case other                                                                          => other.toString
