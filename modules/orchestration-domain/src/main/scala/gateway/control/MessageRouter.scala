package gateway.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.ZStream

import conversation.entity.ChatRepository
import conversation.entity.api.StoredSessionContext
import gateway.entity.{ MessageRouter, * }
import orchestration.control.*
import orchestration.entity.*

/** Control-layer namespace for the `MessageRouter` service.
  *
  * The core trait lives in `gateway.entity.MessageRouter` so any domain module can depend on the trait without dragging
  * in control-plane wiring. This object provides:
  *
  *   - Service accessors (`resolveSession`, `routeInbound`, `routeOutbound`, `sessionContext`) — convenience wrappers
  *     around `ZIO.serviceWithZIO[MessageRouter]`.
  *   - `val live` — ZLayer for the live implementation.
  *   - `def attachControlPlaneRouting` — fans control-plane events out to the channel; composes `MessageRouter`,
  *     `ChannelRegistry`, and `OrchestratorControlPlane` in a single effect. Was previously a method on the trait, but
  *     is now a standalone helper so the trait can live in the entity layer.
  */
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

  val live: ZLayer[ChannelRegistry & ChatRepository, Nothing, MessageRouter] =
    ZLayer.fromFunction(MessageRouterLive.apply)

  /** Subscribe to control-plane events for `runId` and fan them out as outbound messages on `channelName`.
    *
    * Previously a method on the `MessageRouter` trait. Moved off the trait so the trait can live in the entity layer
    * (which must not depend on `orchestration.entity` / `orchestration.control`). The helper pulls `MessageRouter`,
    * `ChannelRegistry`, and `OrchestratorControlPlane` from the ZIO environment.
    */
  def attachControlPlaneRouting(
    runId: String,
    channelName: String,
    strategy: SessionScopeStrategy = SessionScopeStrategy.PerRun,
  ): ZIO[
    MessageRouter & ChannelRegistry & Scope & OrchestratorControlPlane,
    MessageRouterError,
    Fiber.Runtime[Nothing, Unit],
  ] =
    for
      router     <- ZIO.service[MessageRouter]
      registry   <- ZIO.service[ChannelRegistry]
      sessionKey <- router.resolveSession(channelName, runId, strategy)
      channel    <- registry.get(channelName).mapError(MessageRouterError.Channel.apply)
      _          <- channel.open(sessionKey).mapError(MessageRouterError.Channel.apply).ignore
      queue      <- OrchestratorControlPlane.subscribeToEvents(runId)
      fiber      <- ZStream
                      .fromQueue(queue)
                      .mapZIO(event =>
                        router.routeOutbound(toControlPlaneMessage(event, sessionKey, channelName)).ignore
                      )
                      .runDrain
                      .forkScoped
    yield fiber

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
      role = GatewayMessageRole.System,
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

  private def contextStoreKey(sessionKey: SessionKey): (String, String) =
    (sessionKey.channelName, sessionKey.value)

  private def loadContext(sessionKey: SessionKey): IO[MessageRouterError, Option[SessionContext]] =
    val (channel, key) = contextStoreKey(sessionKey)
    chatRepository
      .getSessionContextStateLink(channel, key)
      .mapError(MessageRouterError.Persistence.apply)
      .map(_.map(link => toRuntimeContext(sessionKey, link.context, link.updatedAt)))

  private def saveContext(context: SessionContext): IO[MessageRouterError, Unit] =
    val (channel, key) = contextStoreKey(context.sessionKey)
    chatRepository
      .upsertSessionContextState(channel, key, toStoredContext(context), context.updatedAt)
      .mapError(MessageRouterError.Persistence.apply)

  private def toStoredContext(context: SessionContext): StoredSessionContext =
    StoredSessionContext(
      lastInboundMessageId = context.lastInboundMessageId,
      lastOutboundMessageId = context.lastOutboundMessageId,
      conversationId = context.conversationId,
      runId = context.runId,
      metadata = context.metadata,
    )

  private def toRuntimeContext(
    sessionKey: SessionKey,
    context: StoredSessionContext,
    updatedAt: Instant,
  ): SessionContext =
    SessionContext(
      sessionKey = sessionKey,
      lastInboundMessageId = context.lastInboundMessageId,
      lastOutboundMessageId = context.lastOutboundMessageId,
      conversationId = context.conversationId,
      runId = context.runId,
      metadata = context.metadata,
      updatedAt = updatedAt,
    )
