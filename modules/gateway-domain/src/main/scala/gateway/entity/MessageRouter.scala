package gateway.entity

import java.time.Instant

import zio.*
import zio.json.*

import shared.errors.PersistenceError

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

/** Core routing interface. Lives in the entity layer so any domain module can depend on the trait without pulling in
  * control-plane wiring. Service accessors, the live ZLayer, and control-plane helpers live in
  * `gateway.control.MessageRouter`.
  */
trait MessageRouter:
  def resolveSession(
    channelName: String,
    rawSessionId: String,
    strategy: SessionScopeStrategy,
  ): IO[MessageRouterError, SessionKey]

  def routeInbound(message: NormalizedMessage): IO[MessageRouterError, Unit]
  def routeOutbound(message: NormalizedMessage): IO[MessageRouterError, Unit]
  def sessionContext(sessionKey: SessionKey): IO[MessageRouterError, Option[SessionContext]]
