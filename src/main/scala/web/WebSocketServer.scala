package web

import zio.*
import zio.http.*
import zio.http.ChannelEvent.{ ExceptionCaught, Read, UserEvent, UserEventTriggered }
import zio.json.*
import zio.stream.*

import core.Logger
import db.{ MigrationRepository, MigrationRunRow, PersistenceError }
import gateway.ChannelRegistry
import gateway.models.SessionScopeStrategy
import models.*
import orchestration.{ MigrationOrchestrator, WorkflowService, WorkflowServiceError }
import web.views.HtmlViews
import web.ws.{ ClientMessage, ServerMessage, SubscriptionTopic }

trait WebSocketServer:
  def routes: Routes[Any, Response]

object WebSocketServer:

  def routes: ZIO[WebSocketServer, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[WebSocketServer](_.routes)

  val live: ZLayer[
    MigrationOrchestrator & MigrationRepository & WorkflowService & ChannelRegistry,
    Nothing,
    WebSocketServer,
  ] = ZLayer.fromFunction(WebSocketServerLive.apply)

final case class WebSocketServerLive(
  orchestrator: MigrationOrchestrator,
  repository: MigrationRepository,
  workflowService: WorkflowService,
  channelRegistry: ChannelRegistry,
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

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "ws" / "console" -> handler(handleWebSocket.toResponse)
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

  // --- Utilities ---

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
