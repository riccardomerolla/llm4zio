package gateway.telegram

import zio.*
import zio.stream.ZStream

import gateway.*
import gateway.models.{ MessageDirection, MessageRole, NormalizedMessage, SessionKey, SessionScopeStrategy }

final case class TelegramChannel(
  name: String,
  scopeStrategy: SessionScopeStrategy,
  client: TelegramClient,
  sessionsRef: Ref[Set[SessionKey]],
  inboundQueue: Queue[NormalizedMessage],
  outboundQueuesRef: Ref[Map[SessionKey, Queue[NormalizedMessage]]],
  routingRef: Ref[Map[SessionKey, TelegramRoutingState]],
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
      _     <- sessionsRef.update(_ - sessionKey)
      queue <- outboundQueuesRef.modify(current => (current.get(sessionKey), current - sessionKey))
      _     <- routingRef.update(_ - sessionKey)
      _     <- ZIO.foreachDiscard(queue.toList)(_.shutdown)
    yield ()

  override def closeAll: UIO[Unit] =
    for
      _      <- sessionsRef.set(Set.empty)
      queues <-
        outboundQueuesRef.modify(current => (current.values.toList, Map.empty[SessionKey, Queue[NormalizedMessage]]))
      _      <- routingRef.set(Map.empty)
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
      _           <- validateMessage(message)
      _           <- ensureConnected(message.sessionKey)
      chatId      <- resolveChatId(message.sessionKey, message.metadata)
      replyTo     <- resolveReplyTo(message.sessionKey, message.metadata)
      telegramMsg <- client
                       .sendMessage(
                         TelegramSendMessage(
                           chat_id = chatId,
                           text = message.content,
                           parse_mode = message.metadata.get("telegram.parse_mode"),
                           disable_web_page_preview =
                             message.metadata.get("telegram.disable_web_page_preview").flatMap(_.toBooleanOption),
                           reply_to_message_id = replyTo,
                         )
                       )
                       .mapError(err => MessageChannelError.InvalidMessage(s"telegram send failed: $err"))
      metadata     = TelegramMetadata.toMap(TelegramMetadata.outboundToMetadata(telegramMsg))
      normalized   = message.copy(metadata = message.metadata ++ metadata)
      _           <- routingRef.update { current =>
                       val state = current.getOrElse(message.sessionKey, TelegramRoutingState())
                       current.updated(
                         message.sessionKey,
                         state.copy(lastOutboundMessage = TelegramMetadata.fromMap(normalized.metadata)),
                       )
                     }
      _           <- offerToSessionQueue(message.sessionKey, normalized)
    yield ()

  override def inbound: ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromQueue(inboundQueue).mapError(_ => MessageChannelError.ChannelClosed(name))

  override def outbound(sessionKey: SessionKey): ZStream[Any, MessageChannelError, NormalizedMessage] =
    ZStream.fromZIO(ensureConnected(sessionKey)).drain ++
      ZStream.unwrap(outboundQueue(sessionKey).map(queue => ZStream.fromQueue(queue)))
        .mapError(_ => MessageChannelError.ChannelClosed(name))

  override def activeSessions: UIO[Set[SessionKey]] = sessionsRef.get

  def ingestUpdate(update: TelegramUpdate): IO[MessageChannelError, Option[NormalizedMessage]] =
    extractMessage(update) match
      case None          => ZIO.none
      case Some(message) =>
        val sessionKey = sessionForChat(message.chat.id)
        val content    = message.text.map(_.trim).filter(_.nonEmpty)
        content match
          case None       => ZIO.none
          case Some(text) =>
            for
              _         <- open(sessionKey)
              now       <- Clock.instant
              metadata   = TelegramMetadata.toMap(TelegramMetadata.inboundToMetadata(update, message))
              normalized = NormalizedMessage(
                             id = s"telegram:${update.update_id}:${message.message_id}",
                             channelName = name,
                             sessionKey = sessionKey,
                             direction = MessageDirection.Inbound,
                             role = MessageRole.User,
                             content = text,
                             metadata = metadata,
                             timestamp = now,
                           )
              _         <- inboundQueue.offer(normalized).unit
              _         <- routingRef.update { current =>
                             val state = current.getOrElse(sessionKey, TelegramRoutingState())
                             current.updated(
                               sessionKey,
                               state.copy(lastInboundMessage = TelegramMetadata.fromMap(normalized.metadata)),
                             )
                           }
            yield Some(normalized)

  def pollInbound(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): IO[MessageChannelError, List[NormalizedMessage]] =
    for
      updates <- client
                   .getUpdates(offset = offset, limit = limit, timeoutSeconds = timeoutSeconds, timeout = timeout)
                   .mapError(err => MessageChannelError.InvalidMessage(s"telegram polling failed: $err"))
      mapped  <- ZIO.foreach(updates)(ingestUpdate)
    yield mapped.flatten

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

  private def sessionForChat(chatId: Long): SessionKey =
    scopeStrategy.build(name, chatId.toString)

  private def resolveChatId(
    sessionKey: SessionKey,
    metadata: Map[String, String],
  ): IO[MessageChannelError, Long] =
    metadata
      .get("telegram.chat_id")
      .flatMap(_.toLongOption)
      .orElse(sessionValueChatId(sessionKey))
      .map(ZIO.succeed(_))
      .getOrElse(ZIO.fail(
        MessageChannelError.InvalidMessage(s"cannot resolve telegram chat id for ${sessionKey.asString}")
      ))

  private def resolveReplyTo(
    sessionKey: SessionKey,
    metadata: Map[String, String],
  ): IO[MessageChannelError, Option[Long]] =
    metadata.get(TelegramMetadata.ReplyToMessageIdKey).flatMap(_.toLongOption) match
      case some @ Some(_) => ZIO.succeed(some)
      case None           =>
        routingRef.get.map(_.get(sessionKey).flatMap(_.lastInboundMessage.map(_.telegramMessageId)))

  private def sessionValueChatId(sessionKey: SessionKey): Option[Long] =
    sessionKey.value.split(":").lastOption.flatMap(_.toLongOption)

  private def extractMessage(update: TelegramUpdate): Option[TelegramMessage] =
    update.message.orElse(update.edited_message)

object TelegramChannel:
  def make(
    client: TelegramClient,
    name: String = "telegram",
    scopeStrategy: SessionScopeStrategy = SessionScopeStrategy.PerConversation,
  ): UIO[TelegramChannel] =
    for
      sessions <- Ref.make(Set.empty[SessionKey])
      inbound  <- Queue.unbounded[NormalizedMessage]
      outbound <- Ref.make(Map.empty[SessionKey, Queue[NormalizedMessage]])
      routing  <- Ref.make(Map.empty[SessionKey, TelegramRoutingState])
    yield TelegramChannel(
      name = name,
      scopeStrategy = scopeStrategy,
      client = client,
      sessionsRef = sessions,
      inboundQueue = inbound,
      outboundQueuesRef = outbound,
      routingRef = routing,
    )

  val live: ZLayer[TelegramClient, Nothing, MessageChannel] =
    ZLayer.fromZIO {
      for
        client  <- ZIO.service[TelegramClient]
        channel <- make(client)
      yield channel
    }
