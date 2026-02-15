package gateway

import zio.*
import zio.stream.ZStream

import gateway.models.{ NormalizedMessage, SessionKey, SessionScopeStrategy }

final case class WebSocketChannel(
  name: String,
  scopeStrategy: SessionScopeStrategy = SessionScopeStrategy.PerConversation,
  sessionsRef: Ref[Set[SessionKey]],
  inboundQueue: Queue[NormalizedMessage],
  outboundQueuesRef: Ref[Map[SessionKey, Queue[NormalizedMessage]]],
) extends MessageChannel:

  override def open(sessionKey: SessionKey): IO[MessageChannelError, Unit] =
    if sessionKey.channelName != name then ZIO.fail(MessageChannelError.UnsupportedSession(name, sessionKey))
    else
      for
        _ <- sessionsRef.update(_ + sessionKey)
        _ <-
          outboundQueuesRef.get.flatMap { queues =>
            if queues.contains(sessionKey) then ZIO.unit
            else
              Queue.unbounded[NormalizedMessage].flatMap(queue => outboundQueuesRef.update(_ + (sessionKey -> queue)))
          }
      yield ()

  override def close(sessionKey: SessionKey): UIO[Unit] =
    for
      _      <- sessionsRef.update(_ - sessionKey)
      queues <- outboundQueuesRef.modify(current => (current.get(sessionKey), current - sessionKey))
      _      <- ZIO.foreachDiscard(queues.toList)(_.shutdown)
    yield ()

  override def closeAll: UIO[Unit] =
    for
      _      <- sessionsRef.set(Set.empty)
      queues <-
        outboundQueuesRef.modify(current => (current.values.toList, Map.empty[SessionKey, Queue[NormalizedMessage]]))
      _      <- ZIO.foreachDiscard(queues)(_.shutdown)
    yield ()

  override def receive(message: NormalizedMessage): IO[MessageChannelError, Unit] =
    for
      _ <- validateMessage(message)
      _ <- ensureConnected(message.sessionKey)
      _ <- inboundQueue.offer(message).unit
    yield ()

  override def send(message: NormalizedMessage): IO[MessageChannelError, Unit] =
    for
      _ <- validateMessage(message)
      _ <- offerToSessionQueue(message.sessionKey, message)
    yield ()

  override def inbound: ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromQueue(inboundQueue).mapError(_ => MessageChannelError.ChannelClosed(name))

  override def outbound(sessionKey: SessionKey): ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromZIO(ensureConnected(sessionKey)).drain ++
      ZStream.unwrap(outboundQueue(sessionKey).map(queue => ZStream.fromQueue(queue)))
        .mapError(_ => MessageChannelError.ChannelClosed(name))

  override def activeSessions: UIO[Set[SessionKey]] =
    sessionsRef.get

  private def ensureConnected(sessionKey: SessionKey): IO[MessageChannelError, Unit] =
    sessionsRef.get.flatMap { sessions =>
      if sessions.contains(sessionKey) then ZIO.unit
      else ZIO.fail(MessageChannelError.SessionNotConnected(name, sessionKey))
    }

  private def outboundQueue(sessionKey: SessionKey): IO[MessageChannelError, Queue[NormalizedMessage]] =
    ensureConnected(sessionKey) *>
      outboundQueuesRef.get.flatMap { queues =>
        queues.get(sessionKey) match
          case Some(queue) => ZIO.succeed(queue)
          case None        => ZIO.fail(MessageChannelError.SessionNotConnected(name, sessionKey))
      }

  private def offerToSessionQueue(sessionKey: SessionKey, message: NormalizedMessage): IO[MessageChannelError, Unit] =
    outboundQueue(sessionKey).flatMap(_.offer(message).unit)

  private def validateMessage(message: NormalizedMessage): IO[MessageChannelError, Unit] =
    if message.channelName != name then
      ZIO.fail(MessageChannelError.InvalidMessage(s"message channel ${message.channelName} does not match $name"))
    else if message.content.trim.isEmpty then
      ZIO.fail(MessageChannelError.InvalidMessage("message content cannot be empty"))
    else ZIO.unit

object WebSocketChannel:
  def make(
    name: String = "websocket",
    scopeStrategy: SessionScopeStrategy = SessionScopeStrategy.PerConversation,
  ): UIO[WebSocketChannel] =
    for
      sessions       <- Ref.make(Set.empty[SessionKey])
      inbound        <- Queue.unbounded[NormalizedMessage]
      outboundQueues <- Ref.make(Map.empty[SessionKey, Queue[NormalizedMessage]])
    yield WebSocketChannel(
      name = name,
      scopeStrategy = scopeStrategy,
      sessionsRef = sessions,
      inboundQueue = inbound,
      outboundQueuesRef = outboundQueues,
    )

  val live: ULayer[MessageChannel] =
    ZLayer.fromZIO(make())
