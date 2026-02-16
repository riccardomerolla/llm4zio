package gateway.telegram

import zio.*
import zio.stream.ZStream

import gateway.*
import gateway.models.{ MessageDirection, MessageRole, NormalizedMessage, SessionKey, SessionScopeStrategy }

final case class TelegramChannel(
  name: String,
  scopeStrategy: SessionScopeStrategy,
  client: TelegramClient,
  workflowNotifier: WorkflowNotifier,
  showMoreRef: Ref[Map[String, String]],
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
      formatted    = ResponseFormatter.format(message)
      _           <- ZIO.foreachDiscard(formatted.continuationToken.zip(formatted.remaining)) {
                       case (token, remaining) =>
                         showMoreRef.update(_ + (token -> remaining))
                     }
      telegramMsg <- client
                       .sendMessage(
                         TelegramSendMessage(
                           chat_id = chatId,
                           text = formatted.text,
                           parse_mode = formatted.parseMode,
                           disable_web_page_preview =
                             message.metadata.get("telegram.disable_web_page_preview").flatMap(_.toBooleanOption),
                           reply_to_message_id = replyTo,
                           reply_markup = formatted.replyMarkup,
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
    update.callback_query match
      case Some(callback) => handleCallbackQuery(update, callback).as(None)
      case None           => ingestMessageUpdate(update)

  private def ingestMessageUpdate(update: TelegramUpdate): IO[MessageChannelError, Option[NormalizedMessage]] =
    extractMessage(update) match
      case None          => ZIO.none
      case Some(message) =>
        val sessionKey = sessionForChat(message.chat.id)
        val content    = message.text.map(_.trim).filter(_.nonEmpty)
        content match
          case None       => ZIO.none
          case Some(text) =>
            for
              _       <- open(sessionKey)
              now     <- Clock.instant
              metadata = TelegramMetadata.toMap(TelegramMetadata.inboundToMetadata(update, message))
              _       <- routingRef.update { current =>
                           val state = current.getOrElse(sessionKey, TelegramRoutingState())
                           current.updated(
                             sessionKey,
                             state.copy(lastInboundMessage = TelegramMetadata.fromMap(metadata)),
                           )
                         }
              routed  <- routeInboundOrCommand(
                           sessionKey = sessionKey,
                           update = update,
                           message = message,
                           content = text,
                           metadata = metadata,
                           now = now,
                         )
            yield routed

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

  private def routeInboundOrCommand(
    sessionKey: SessionKey,
    update: TelegramUpdate,
    message: TelegramMessage,
    content: String,
    metadata: Map[String, String],
    now: java.time.Instant,
  ): IO[MessageChannelError, Option[NormalizedMessage]] =
    CommandParser.parse(content) match
      case Right(command)                         =>
        (sendInlineControlsIfNeeded(message.chat.id, message.message_id, command) *>
          workflowNotifier
            .notifyCommand(
              chatId = message.chat.id,
              replyToMessageId = Some(message.message_id),
              command = command,
            ))
          .catchAll(err => ZIO.logWarning(s"telegram command handling failed: $err"))
          .as(None)
      case Left(CommandParseError.NotACommand(_)) =>
        val normalized = NormalizedMessage(
          id = s"telegram:${update.update_id}:${message.message_id}",
          channelName = name,
          sessionKey = sessionKey,
          direction = MessageDirection.Inbound,
          role = MessageRole.User,
          content = content,
          metadata = metadata,
          timestamp = now,
        )
        inboundQueue.offer(normalized).unit.as(Some(normalized))
      case Left(parseError)                       =>
        workflowNotifier
          .notifyParseError(
            chatId = message.chat.id,
            replyToMessageId = Some(message.message_id),
            error = parseError,
          )
          .catchAll(err => ZIO.logWarning(s"telegram parse error notification failed: $err"))
          .as(None)

  private def handleCallbackQuery(
    update: TelegramUpdate,
    callback: TelegramCallbackQuery,
  ): IO[MessageChannelError, Unit] =
    callback.message match
      case None          =>
        ZIO.logWarning(s"telegram callback query has no message (update_id=${update.update_id})")
      case Some(message) =>
        val sessionKey = sessionForChat(message.chat.id)
        val data       = callback.data.map(_.trim).filter(_.nonEmpty).getOrElse("")
        for
          _ <- open(sessionKey)
          _ <-
            if data.startsWith("more:") then
              handleShowMoreCallback(
                chatId = message.chat.id,
                replyToMessageId = message.message_id,
                token = data.stripPrefix("more:").trim,
              )
            else
              InlineKeyboards.parseCallbackData(data) match
                case Left(error)      =>
                  sendCallbackFeedback(
                    chatId = message.chat.id,
                    replyToMessageId = message.message_id,
                    text = s"Invalid callback payload: $error",
                    markup = None,
                  )
                case Right(keyAction) =>
                  routeCallbackAction(
                    chatId = message.chat.id,
                    replyToMessageId = message.message_id,
                    action = keyAction,
                  )
        yield ()

  private def handleShowMoreCallback(
    chatId: Long,
    replyToMessageId: Long,
    token: String,
  ): IO[MessageChannelError, Unit] =
    for
      maybeRemaining <- showMoreRef.modify(current => (current.get(token), current - token))
      _              <- maybeRemaining match
                          case None            =>
                            sendCallbackFeedback(
                              chatId = chatId,
                              replyToMessageId = replyToMessageId,
                              text = "No additional content is available.",
                              markup = None,
                            )
                          case Some(remaining) =>
                            val formatted = ResponseFormatter.formatContinuation(token, remaining)
                            ZIO.foreachDiscard(formatted.continuationToken.zip(formatted.remaining)) {
                              case (nextToken, tail) => showMoreRef.update(_ + (nextToken -> tail))
                            } *>
                              sendCallbackFeedback(
                                chatId = chatId,
                                replyToMessageId = replyToMessageId,
                                text = formatted.text,
                                markup = formatted.replyMarkup,
                                parseMode = formatted.parseMode,
                              )
    yield ()

  private def routeCallbackAction(
    chatId: Long,
    replyToMessageId: Long,
    action: InlineKeyboardAction,
  ): IO[MessageChannelError, Unit] =
    action.action.toLowerCase match
      case "details" =>
        sendInlineControls(chatId, replyToMessageId, action.runId, action.paused) *>
          workflowNotifier
            .notifyCommand(chatId, Some(replyToMessageId), BotCommand.Status(action.runId))
            .mapError(notifierError)
      case "cancel"  =>
        sendInlineControls(chatId, replyToMessageId, action.runId, action.paused) *>
          workflowNotifier
            .notifyCommand(chatId, Some(replyToMessageId), BotCommand.Cancel(action.runId))
            .mapError(notifierError)
      case "retry"   =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Retry requested for run ${action.runId}. Use /status ${action.runId} for latest updates.",
          markup = Some(InlineKeyboards.workflowControls(action.runId, paused = false)),
        )
      case "toggle"  =>
        val nextPaused = !action.paused
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text =
            s"${if nextPaused then "Pause" else "Resume"} requested for run ${action.runId}. " +
              "Pause/Resume is not available yet.",
          markup = Some(InlineKeyboards.workflowControls(action.runId, paused = nextPaused)),
        )
      case other     =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Unsupported action '$other'.",
          markup = None,
        )

  private def sendInlineControlsIfNeeded(
    chatId: Long,
    replyToMessageId: Long,
    command: BotCommand,
  ): IO[MessageChannelError, Unit] =
    command match
      case BotCommand.Status(runId) =>
        sendInlineControls(chatId, replyToMessageId, runId, paused = false)
      case BotCommand.Logs(runId)   =>
        sendInlineControls(chatId, replyToMessageId, runId, paused = false)
      case _                        =>
        ZIO.unit

  private def sendInlineControls(
    chatId: Long,
    replyToMessageId: Long,
    runId: Long,
    paused: Boolean,
  ): IO[MessageChannelError, Unit] =
    sendCallbackFeedback(
      chatId = chatId,
      replyToMessageId = replyToMessageId,
      text = s"Workflow controls for run $runId:",
      markup = Some(InlineKeyboards.workflowControls(runId, paused)),
    )

  private def sendCallbackFeedback(
    chatId: Long,
    replyToMessageId: Long,
    text: String,
    markup: Option[TelegramInlineKeyboardMarkup],
    parseMode: Option[String] = None,
  ): IO[MessageChannelError, Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = text,
          parse_mode = parseMode,
          reply_to_message_id = Some(replyToMessageId),
          reply_markup = markup,
        )
      )
      .unit
      .mapError(sendError)

  private def sendError(error: TelegramClientError): MessageChannelError =
    MessageChannelError.InvalidMessage(s"telegram send failed: $error")

  private def notifierError(error: WorkflowNotifierError): MessageChannelError =
    MessageChannelError.InvalidMessage(s"telegram workflow notifier failed: $error")

object TelegramChannel:
  def make(
    client: TelegramClient,
    workflowNotifier: WorkflowNotifier = WorkflowNotifier.noop,
    name: String = "telegram",
    scopeStrategy: SessionScopeStrategy = SessionScopeStrategy.PerConversation,
  ): UIO[TelegramChannel] =
    for
      sessions <- Ref.make(Set.empty[SessionKey])
      showMore <- Ref.make(Map.empty[String, String])
      inbound  <- Queue.unbounded[NormalizedMessage]
      outbound <- Ref.make(Map.empty[SessionKey, Queue[NormalizedMessage]])
      routing  <- Ref.make(Map.empty[SessionKey, TelegramRoutingState])
    yield TelegramChannel(
      name = name,
      scopeStrategy = scopeStrategy,
      client = client,
      workflowNotifier = workflowNotifier,
      showMoreRef = showMore,
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
