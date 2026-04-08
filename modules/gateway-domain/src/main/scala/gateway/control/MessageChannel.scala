package gateway.control

import zio.*
import zio.stream.ZStream

import gateway.entity.{ ChannelStatus as EntityChannelStatus, * }

enum MessageChannelError:
  case ChannelNotFound(name: String)
  case UnsupportedSession(channelName: String, sessionKey: SessionKey)
  case SessionNotConnected(channelName: String, sessionKey: SessionKey)
  case ChannelClosed(channelName: String)
  case InvalidMessage(reason: String)

trait MessageChannel extends Channel:
  def name: String
  def scopeStrategy: SessionScopeStrategy

  def open(sessionKey: SessionKey): IO[MessageChannelError, Unit]
  def close(sessionKey: SessionKey): UIO[Unit]
  def closeAll: UIO[Unit]

  def receive(message: NormalizedMessage): IO[MessageChannelError, Unit]
  def send(message: NormalizedMessage): IO[MessageChannelError, Unit]

  def inbound: ZStream[Any, MessageChannelError, NormalizedMessage]
  def outbound(sessionKey: SessionKey): ZStream[Any, MessageChannelError, NormalizedMessage]

  def activeSessions: UIO[Set[SessionKey]]

  override def start: UIO[Unit] = ZIO.unit

  override def stop: UIO[Unit] = closeAll

  override def status: UIO[EntityChannelStatus] =
    activeSessions.map { sessions =>
      EntityChannelStatus(
        name = name,
        reachability = if sessions.nonEmpty then ChannelReachability.Reachable else ChannelReachability.Unknown,
        running = sessions.nonEmpty,
        activeSessions = sessions.size,
      )
    }

object MessageChannel:
  def open(sessionKey: SessionKey): ZIO[MessageChannel, MessageChannelError, Unit] =
    ZIO.serviceWithZIO[MessageChannel](_.open(sessionKey))

  def close(sessionKey: SessionKey): ZIO[MessageChannel, Nothing, Unit] =
    ZIO.serviceWithZIO[MessageChannel](_.close(sessionKey))

  def receive(message: NormalizedMessage): ZIO[MessageChannel, MessageChannelError, Unit] =
    ZIO.serviceWithZIO[MessageChannel](_.receive(message))

  def send(message: NormalizedMessage): ZIO[MessageChannel, MessageChannelError, Unit] =
    ZIO.serviceWithZIO[MessageChannel](_.send(message))

  def activeSessions: ZIO[MessageChannel, Nothing, Set[SessionKey]] =
    ZIO.serviceWithZIO[MessageChannel](_.activeSessions)
