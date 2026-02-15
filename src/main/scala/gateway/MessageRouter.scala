package gateway

import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.ZStream

import _root_.models.*
import db.{ ChatRepository, PersistenceError }
import gateway.models.*
import orchestration.OrchestratorControlPlane

enum MessageRouterError:
  case Channel(error: MessageChannelError)
  case Persistence(error: PersistenceError)
  case InvalidSession(reason: String)
  case UnsupportedOperation(reason: String)

case class SessionContext(
  sessionKey: SessionKey,
  lastInboundMessageId: Option[String] = None,
  lastOutboundMessageId: Option[String] = None,
  conversationId: Option[Long] = None,
  runId: Option[Long] = None,
  metadata: Map[String, String] = Map.empty,
  updatedAt: Instant,
) derives JsonCodec

trait MessageRouter:
  def resolveSession(
    channelName: String,
    rawSessionId: String,
    strategy: SessionScopeStrategy,
  ): IO[MessageRouterError, SessionKey]

  def routeInbound(message: NormalizedMessage): IO[MessageRouterError, Unit]
  def routeOutbound(message: NormalizedMessage): IO[MessageRouterError, Unit]
  def sessionContext(sessionKey: SessionKey): IO[MessageRouterError, Option[SessionContext]]

  def attachControlPlaneRouting(
    runId: String,
    channelName: String,
    strategy: SessionScopeStrategy = SessionScopeStrategy.PerRun,
  ): ZIO[Scope & OrchestratorControlPlane, MessageRouterError, Fiber.Runtime[Nothing, Unit]]

object MessageRouter:
  def resolveSession(
    channelName: String,
    rawSessionId: String,
    strategy: SessionScopeStrategy,
  ): ZIO[MessageRouter, MessageRouterError, SessionKey] =
    ZIO.serviceWithZIO[MessageRouter](_.resolveSession(channelName, rawSessionId, strategy))

  def routeInbound(message: NormalizedMessage): ZIO[MessageRouter, MessageRouterError, Unit] =
    ZIO.serviceWithZIO[MessageRouter](_.routeInbound(message))

  def routeOutbound(message: NormalizedMessage): ZIO[MessageRouter, MessageRouterError, Unit] =
    ZIO.serviceWithZIO[MessageRouter](_.routeOutbound(message))

  def sessionContext(sessionKey: SessionKey): ZIO[MessageRouter, MessageRouterError, Option[SessionContext]] =
    ZIO.serviceWithZIO[MessageRouter](_.sessionContext(sessionKey))

  def attachControlPlaneRouting(
    runId: String,
    channelName: String,
    strategy: SessionScopeStrategy = SessionScopeStrategy.PerRun,
  ): ZIO[MessageRouter & Scope & OrchestratorControlPlane, MessageRouterError, Fiber.Runtime[Nothing, Unit]] =
    ZIO.serviceWithZIO[MessageRouter](_.attachControlPlaneRouting(runId, channelName, strategy))

  val live: ZLayer[ChannelRegistry & ChatRepository, Nothing, MessageRouter] =
    ZLayer.fromFunction(MessageRouterLive.apply)

final case class MessageRouterLive(
  registry: ChannelRegistry,
  chatRepository: ChatRepository,
) extends MessageRouter:

  override def resolveSession(
    channelName: String,
    rawSessionId: String,
    strategy: SessionScopeStrategy,
  ): IO[MessageRouterError, SessionKey] =
    val trimmed = rawSessionId.trim
    if trimmed.isEmpty then ZIO.fail(MessageRouterError.InvalidSession("Session id cannot be empty"))
    else ZIO.succeed(strategy.build(channelName, trimmed))

  override def routeInbound(message: NormalizedMessage): IO[MessageRouterError, Unit] =
    for
      _        <- registry.get(message.channelName).mapError(MessageRouterError.Channel.apply)
      existing <- loadContext(message.sessionKey)
      now      <- Clock.instant
      updated   = existing
                    .getOrElse(SessionContext(sessionKey = message.sessionKey, updatedAt = now))
                    .copy(
                      lastInboundMessageId = Some(message.id),
                      conversationId = message.metadata.get("conversationId").flatMap(_.toLongOption).orElse(
                        existing.flatMap(_.conversationId)
                      ),
                      runId = message.metadata.get("runId").flatMap(_.toLongOption).orElse(existing.flatMap(_.runId)),
                      metadata = existing.map(_.metadata).getOrElse(Map.empty) ++ message.metadata,
                      updatedAt = now,
                    )
      _        <- saveContext(updated)
    yield ()

  override def routeOutbound(message: NormalizedMessage): IO[MessageRouterError, Unit] =
    for
      _        <- registry.publish(message.channelName, message).mapError(MessageRouterError.Channel.apply)
      existing <- loadContext(message.sessionKey)
      now      <- Clock.instant
      updated   = existing
                    .getOrElse(SessionContext(sessionKey = message.sessionKey, updatedAt = now))
                    .copy(
                      lastOutboundMessageId = Some(message.id),
                      metadata = existing.map(_.metadata).getOrElse(Map.empty) ++ message.metadata,
                      updatedAt = now,
                    )
      _        <- saveContext(updated)
    yield ()

  override def sessionContext(sessionKey: SessionKey): IO[MessageRouterError, Option[SessionContext]] =
    loadContext(sessionKey)

  override def attachControlPlaneRouting(
    runId: String,
    channelName: String,
    strategy: SessionScopeStrategy,
  ): ZIO[Scope & OrchestratorControlPlane, MessageRouterError, Fiber.Runtime[Nothing, Unit]] =
    for
      sessionKey <- resolveSession(channelName, runId, strategy)
      channel    <- registry.get(channelName).mapError(MessageRouterError.Channel.apply)
      _          <- channel.open(sessionKey).mapError(MessageRouterError.Channel.apply).ignore
      queue      <- OrchestratorControlPlane.subscribeToEvents(runId)
      fiber      <- ZStream
                      .fromQueue(queue)
                      .mapZIO(event => routeOutbound(toControlPlaneMessage(event, sessionKey, channelName)).ignore)
                      .runDrain
                      .forkScoped
    yield fiber

  private def contextStoreKey(sessionKey: SessionKey): (String, String) =
    (sessionKey.channelName, sessionKey.value)

  private def loadContext(sessionKey: SessionKey): IO[MessageRouterError, Option[SessionContext]] =
    val (channel, key) = contextStoreKey(sessionKey)
    chatRepository
      .getSessionContext(channel, key)
      .mapError(MessageRouterError.Persistence.apply)
      .flatMap {
        case None      => ZIO.none
        case Some(raw) =>
          ZIO
            .fromEither(raw.fromJson[SessionContext])
            .mapError(err => MessageRouterError.InvalidSession(s"Invalid stored session context: $err"))
            .map(Some(_))
      }

  private def saveContext(context: SessionContext): IO[MessageRouterError, Unit] =
    val (channel, key) = contextStoreKey(context.sessionKey)
    chatRepository
      .upsertSessionContext(channel, key, context.toJson, context.updatedAt)
      .mapError(MessageRouterError.Persistence.apply)

  private def toControlPlaneMessage(
    event: ControlPlaneEvent,
    sessionKey: SessionKey,
    channelName: String,
  ): NormalizedMessage =
    NormalizedMessage(
      id = s"${event.correlationId}:${event.timestamp.toEpochMilli}",
      channelName = channelName,
      sessionKey = sessionKey,
      direction = MessageDirection.Outbound,
      role = MessageRole.System,
      content = event.toJson,
      metadata = Map(
        "eventType" -> event.getClass.getSimpleName,
        "runId"     -> extractRunId(event),
      ),
      timestamp = event.timestamp,
    )

  private def extractRunId(event: ControlPlaneEvent): String = event match
    case value: WorkflowStarted   => value.runId
    case value: WorkflowCompleted => value.runId
    case value: WorkflowFailed    => value.runId
    case value: StepStarted       => value.runId
    case value: StepProgress      => value.runId
    case value: StepCompleted     => value.runId
    case value: StepFailed        => value.runId
    case value: ResourceAllocated => value.runId
    case value: ResourceReleased  => value.runId
