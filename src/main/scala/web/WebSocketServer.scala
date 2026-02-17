package web

import zio.*
import zio.http.*
import zio.http.ChannelEvent.{ ExceptionCaught, Read, UserEvent, UserEventTriggered }
import zio.json.*
import zio.stream.*

import core.{ HealthMonitor, LogLevel, LogTailer, LogTailerError, Logger }
import db.{ MigrationRepository, MigrationRunRow, PersistenceError }
import gateway.ChannelRegistry
import gateway.models.SessionScopeStrategy
import models.*
import orchestration.{ MigrationOrchestrator, OrchestratorControlPlane, WorkflowService, WorkflowServiceError }
import web.views.HtmlViews
import web.ws.{ ClientMessage, ServerMessage, SubscriptionTopic }

trait WebSocketServer:
  def routes: Routes[Any, Response]

object WebSocketServer:

  def routes: ZIO[WebSocketServer, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[WebSocketServer](_.routes)

  val live: ZLayer[
    MigrationOrchestrator & MigrationRepository & WorkflowService & ChannelRegistry & StreamAbortRegistry &
      ActivityHub & LogTailer & HealthMonitor & OrchestratorControlPlane,
    Nothing,
    WebSocketServer,
  ] = ZLayer.fromFunction(WebSocketServerLive.apply)

final case class WebSocketServerLive(
  orchestrator: MigrationOrchestrator,
  repository: MigrationRepository,
  workflowService: WorkflowService,
  channelRegistry: ChannelRegistry,
  streamAbortRegistry: StreamAbortRegistry,
  activityHub: ActivityHub,
  logTailer: LogTailer,
  healthMonitor: HealthMonitor,
  controlPlane: OrchestratorControlPlane,
) extends WebSocketServer:

  private val HeartbeatInterval = 30.seconds

  private val DefaultWorkflowSteps = List(
    MigrationStep.Discovery,
    MigrationStep.Analysis,
    MigrationStep.Mapping,
    MigrationStep.Transformation,
    MigrationStep.Validation,
    MigrationStep.Documentation,
  )

  private case class AgentMonitorWsPayload(
    snapshot: AgentMonitorSnapshot,
    history: List[AgentExecutionEvent],
  ) derives zio.json.JsonCodec

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "ws" / "console" -> handler(handleWebSocket.toResponse),
    Method.GET / "ws" / "logs"    -> handler { (req: Request) =>
      handleLogsWebSocket(req).toResponse
    },
  )

  private def handleWebSocket: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      for
        subscriptions <- Ref.make(Set.empty[String])
        activeFeeds   <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, Unit]])
        _             <- channel.receiveAll {
                           case Read(WebSocketFrame.Text(text))                 =>
                             handleClientMessage(channel, subscriptions, activeFeeds, text)
                           case Read(WebSocketFrame.Close(_, _))                =>
                             cleanup(activeFeeds)
                           case UserEventTriggered(UserEvent.HandshakeComplete) =>
                             startHeartbeat(channel).forkDaemon.unit
                           case ExceptionCaught(cause)                          =>
                             Logger.warn(s"WebSocket error: ${cause.getMessage}")
                           case _                                               => ZIO.unit
                         }
      yield ()
    }

  private def handleClientMessage(
    channel: WebSocketChannel,
    subscriptions: Ref[Set[String]],
    activeFeeds: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    raw: String,
  ): UIO[Unit] =
    raw.fromJson[ClientMessage] match
      case Left(err)  =>
        sendError(channel, "parse_error", s"Invalid message: $err")
      case Right(msg) =>
        msg match
          case ClientMessage.Subscribe(topic, _) =>
            handleSubscribe(channel, subscriptions, activeFeeds, topic)
          case ClientMessage.Unsubscribe(topic)  =>
            handleUnsubscribe(channel, subscriptions, activeFeeds, topic)
          case ClientMessage.Ping(ts)            =>
            sendRaw(channel, ServerMessage.Pong(ts))
          case ClientMessage.AbortChat(convId)   =>
            handleAbortChat(channel, convId)

  private def handleAbortChat(channel: WebSocketChannel, conversationId: Long): UIO[Unit] =
    streamAbortRegistry.abort(conversationId).flatMap { aborted =>
      if aborted then sendEvent(channel, s"chat:$conversationId:stream", "chat-aborted", "")
      else sendError(channel, "no_active_stream", s"No active stream for conversation $conversationId")
    }

  private def handleSubscribe(
    channel: WebSocketChannel,
    subscriptions: Ref[Set[String]],
    activeFeeds: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    topic: String,
  ): UIO[Unit] =
    SubscriptionTopic.parse(topic) match
      case Left(err)     =>
        sendError(channel, "invalid_topic", err)
      case Right(parsed) =>
        for
          alreadySubscribed <- subscriptions.get.map(_.contains(topic))
          _                 <- ZIO.unless(alreadySubscribed) {
                                 for
                                   fiber <- startFeed(channel, topic, parsed).forkDaemon
                                   _     <- subscriptions.update(_ + topic)
                                   _     <- activeFeeds.update(_ + (topic -> fiber))
                                   ts    <- nowMs
                                   _     <- sendRaw(channel, ServerMessage.Subscribed(topic, ts))
                                 yield ()
                               }
        yield ()

  private def handleUnsubscribe(
    channel: WebSocketChannel,
    subscriptions: Ref[Set[String]],
    activeFeeds: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]],
    topic: String,
  ): UIO[Unit] =
    for
      feeds <- activeFeeds.modify { current =>
                 val fiber = current.get(topic)
                 (fiber, current - topic)
               }
      _     <- ZIO.foreachDiscard(feeds.toList)(_.interrupt)
      _     <- subscriptions.update(_ - topic)
      ts    <- nowMs
      _     <- sendRaw(channel, ServerMessage.Unsubscribed(topic, ts))
    yield ()

  private def cleanup(
    activeFeeds: Ref[Map[String, Fiber.Runtime[Nothing, Unit]]]
  ): UIO[Unit] =
    activeFeeds.modify(current => (current.values.toList, Map.empty)).flatMap { fibers =>
      ZIO.foreachDiscard(fibers)(_.interrupt)
    }

  // --- Feed implementations ---

  private def startFeed(
    channel: WebSocketChannel,
    topic: String,
    parsed: SubscriptionTopic,
  ): UIO[Unit] =
    val feed = parsed match
      case SubscriptionTopic.RunProgress(runId)           => runProgressFeed(channel, topic, runId)
      case SubscriptionTopic.DashboardRecentRuns          => recentRunsFeed(channel, topic)
      case SubscriptionTopic.ChatMessages(conversationId) => chatMessagesFeed(channel, topic, conversationId)
      case SubscriptionTopic.ChatStream(conversationId)   => chatStreamFeed(channel, topic, conversationId)
      case SubscriptionTopic.ActivityFeed                 => activityFeed(channel, topic)
      case SubscriptionTopic.HealthMetrics                => healthFeed(channel, topic)
      case SubscriptionTopic.AgentsActivity               => agentsActivityFeed(channel, topic)
    feed.catchAll(err => Logger.warn(s"Feed error for $topic: $err")).unit

  private def runProgressFeed(channel: WebSocketChannel, topic: String, runId: Long): IO[Any, Unit] =
    for
      run      <- orchestrator
                    .getRunStatus(runId)
                    .someOrFail(PersistenceError.NotFound("migration_runs", runId))
      workflow <- workflowForRun(run)
      queue    <- orchestrator.subscribeToProgress(runId)
      _        <- ZStream
                    .fromQueue(queue)
                    .mapZIO { _ =>
                      ZIO
                        .foreach(knownPhasesForWorkflow(workflow))(phase => repository.getProgress(runId, phase))
                        .map(_.flatten)
                        .flatMap { phaseRows =>
                          val progressHtml = HtmlViews.phaseProgressFragment(phaseRows)
                          val diagramHtml  = HtmlViews.runWorkflowDiagramFragment(workflow, phaseRows)
                          sendEvent(channel, topic, "phase-progress", progressHtml) *>
                            sendEvent(channel, topic, "workflow-diagram", diagramHtml)
                        }
                        .catchAll(err => Logger.warn(s"Skipping WS progress for run $runId: $err"))
                    }
                    .runDrain
    yield ()

  private def recentRunsFeed(channel: WebSocketChannel, topic: String): IO[Any, Unit] =
    ZStream
      .repeatWithSchedule((), Schedule.spaced(5.seconds))
      .mapZIO { _ =>
        repository
          .listRuns(offset = 0, limit = 10)
          .map(runs => HtmlViews.recentRunsFragment(runs))
          .flatMap(html => sendEvent(channel, topic, "recent-runs", html))
          .catchAll(err => Logger.warn(s"recent-runs feed error: $err"))
      }
      .runDrain

  private def chatMessagesFeed(
    channel: WebSocketChannel,
    topic: String,
    conversationId: Long,
  ): IO[Any, Unit] =
    val sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
    channelRegistry
      .get("websocket")
      .flatMap { wsChannel =>
        wsChannel.open(sessionKey).catchAll(_ => ZIO.unit) *>
          wsChannel
            .outbound(sessionKey)
            .mapZIO { normalized =>
              sendEvent(channel, topic, "chat-message", normalized.content)
            }
            .runDrain
      }
      .catchAll(err => Logger.warn(s"chat feed error: $err"))
      .unit

  private def chatStreamFeed(
    channel: WebSocketChannel,
    topic: String,
    conversationId: Long,
  ): IO[Any, Unit] =
    val sessionKey = SessionScopeStrategy.PerConversation.build("websocket", conversationId.toString)
    channelRegistry
      .get("websocket")
      .flatMap { wsChannel =>
        wsChannel.open(sessionKey).catchAll(_ => ZIO.unit) *>
          wsChannel
            .outbound(sessionKey)
            .filter(_.metadata.contains("streamEventType"))
            .mapZIO { normalized =>
              sendEvent(
                channel,
                topic,
                normalized.metadata.getOrElse("streamEventType", "chat-chunk"),
                normalized.content,
              )
            }
            .runDrain
      }
      .catchAll(err => Logger.warn(s"chat stream feed error: $err"))
      .unit

  private def activityFeed(channel: WebSocketChannel, topic: String): IO[Any, Unit] =
    activityHub.subscribe.flatMap { queue =>
      ZStream
        .fromQueue(queue)
        .mapZIO { event =>
          sendEvent(channel, topic, event.eventType.toString, event.toJson)
        }
        .runDrain
    }

  private def healthFeed(channel: WebSocketChannel, topic: String): IO[Any, Unit] =
    healthMonitor.stream(2.seconds).mapZIO { snapshot =>
      sendEvent(channel, topic, "health-snapshot", snapshot.toJson)
    }.runDrain

  private def agentsActivityFeed(channel: WebSocketChannel, topic: String): IO[Any, Unit] =
    ZStream
      .repeatWithSchedule((), Schedule.spaced(2.seconds))
      .mapZIO { _ =>
        for
          snapshot <- controlPlane.getAgentMonitorSnapshot.orElseFail(
                        ControlPlaneError.EventBroadcastFailed("cannot load agent monitor snapshot")
                      )
          history  <- controlPlane.getAgentExecutionHistory(120).orElseFail(
                        ControlPlaneError.EventBroadcastFailed("cannot load agent monitor history")
                      )
          payload   = AgentMonitorWsPayload(snapshot = snapshot, history = history).toJson
          _        <- sendEvent(channel, topic, "agent-monitor", payload)
        yield ()
      }
      .runDrain

  private def handleLogsWebSocket(req: Request): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      val rawPath = req.queryParam("path").filter(_.nonEmpty).getOrElse("logs/app.log")
      val levels  = parseLevels(req.queryParam("levels"))
      val search  = req.queryParam("search").map(_.trim).filter(_.nonEmpty)
      val tailIO  =
        resolveLogPath(rawPath).flatMap { path =>
          logTailer
            .tail(path, levels, search)
            .mapZIO { event =>
              channel.send(Read(WebSocketFrame.text(event.toJson))).catchAll(_ => ZIO.unit)
            }
            .runDrain
        }

      for
        fiber <- tailIO.catchAll(err => sendLogError(channel, err)).forkDaemon
        _     <- channel.receiveAll {
                   case Read(WebSocketFrame.Close(_, _)) =>
                     fiber.interrupt.unit
                   case ExceptionCaught(cause)           =>
                     Logger.warn(s"Logs websocket error: ${cause.getMessage}")
                   case _                                =>
                     ZIO.unit
                 }.ensuring(fiber.interrupt)
      yield ()
    }

  // --- Utilities ---

  private def parseLevels(raw: Option[String]): Set[LogLevel] =
    raw match
      case Some(value) =>
        val parsed = value.split(",").toList.flatMap(LogLevel.parse).toSet
        if parsed.nonEmpty then parsed else LogLevel.all
      case None        =>
        LogLevel.all

  private def resolveLogPath(rawPath: String): IO[LogTailerError, java.nio.file.Path] =
    ZIO
      .attempt(java.nio.file.Path.of(rawPath))
      .mapError(err =>
        LogTailerError.InvalidPath(rawPath, Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
      )

  private def sendLogError(channel: WebSocketChannel, err: LogTailerError): UIO[Unit] =
    val payload = err match
      case LogTailerError.InvalidPath(path, reason) =>
        s"""{"type":"error","message":"Invalid log path '$path': ${escapeJson(reason)}"}"""
      case LogTailerError.ReadFailed(path, reason)  =>
        s"""{"type":"error","message":"Cannot read '$path': ${escapeJson(reason)}"}"""
    channel.send(Read(WebSocketFrame.text(payload))).catchAll(_ => ZIO.unit)

  private def escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def startHeartbeat(channel: WebSocketChannel): UIO[Unit] =
    ZStream
      .repeatWithSchedule((), Schedule.spaced(HeartbeatInterval))
      .mapZIO(_ => channel.send(Read(WebSocketFrame.Ping)).catchAll(_ => ZIO.unit))
      .runDrain

  private def sendRaw(channel: WebSocketChannel, msg: ServerMessage): UIO[Unit] =
    channel.send(Read(WebSocketFrame.text(msg.toJson))).catchAll(_ => ZIO.unit)

  private def sendEvent(channel: WebSocketChannel, topic: String, eventType: String, payload: String): UIO[Unit] =
    nowMs.flatMap(ts => sendRaw(channel, ServerMessage.Event(topic, eventType, payload, ts)))

  private def sendError(channel: WebSocketChannel, code: String, message: String): UIO[Unit] =
    nowMs.flatMap(ts => sendRaw(channel, ServerMessage.Error(code, message, ts)))

  private def workflowForRun(run: MigrationRunRow): IO[PersistenceError | WorkflowServiceError, WorkflowDefinition] =
    run.workflowId match
      case None     => ZIO.succeed(WorkflowDefinition.default)
      case Some(id) =>
        workflowService
          .getWorkflow(id)
          .map {
            case Some(workflow) => workflow
            case None           => WorkflowDefinition.default.copy(name = s"Workflow #$id")
          }

  private def knownPhasesForWorkflow(workflow: WorkflowDefinition): List[String] =
    val steps = if workflow.steps.nonEmpty then workflow.steps else DefaultWorkflowSteps
    steps.map(stepToPhase)

  private def stepToPhase(step: MigrationStep): String = step match
    case MigrationStep.Discovery      => "discovery"
    case MigrationStep.Analysis       => "analysis"
    case MigrationStep.Mapping        => "mapping"
    case MigrationStep.Transformation => "transformation"
    case MigrationStep.Validation     => "validation"
    case MigrationStep.Documentation  => "documentation"

  private def nowMs: UIO[Long] =
    Clock.instant.map(_.toEpochMilli)
