package gateway.telegram

import zio.*
import zio.json.*
import zio.stream.ZStream

import db.*
import gateway.*
import gateway.models.{ MessageDirection, MessageRole, NormalizedMessage, SessionKey, SessionScopeStrategy }
import _root_.models.*
import orchestration.TaskExecutor

final case class TelegramPollBatch(
  messages: List[NormalizedMessage],
  nextOffset: Option[Long],
)

final case class TelegramChannel(
  name: String,
  scopeStrategy: SessionScopeStrategy,
  client: TelegramClient,
  fileTransfer: FileTransfer,
  workflowNotifier: WorkflowNotifier,
  taskRepository: Option[TaskRepository],
  taskExecutor: Option[TaskExecutor],
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
      _           <- maybeSendGeneratedFiles(
                       chatId = chatId,
                       replyToMessageId = telegramMsg.message_id,
                       metadata = message.metadata,
                     )
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
        val content    = inboundContent(message)
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
                           metadata = metadata ++ documentMetadata(message),
                           now = now,
                         )
            yield routed

  def pollInbound(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): IO[MessageChannelError, List[NormalizedMessage]] =
    pollInboundBatch(offset, limit, timeoutSeconds, timeout).map(_.messages)

  def pollInboundBatch(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): IO[MessageChannelError, TelegramPollBatch] =
    for
      updates   <- client
                     .getUpdates(offset = offset, limit = limit, timeoutSeconds = timeoutSeconds, timeout = timeout)
                     .mapError(err => MessageChannelError.InvalidMessage(s"telegram polling failed: $err"))
      mapped    <- ZIO.foreach(updates)(ingestUpdate)
      nextOffset = updates.map(_.update_id).maxOption.map(_ + 1L)
    yield TelegramPollBatch(mapped.flatten, nextOffset)

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

  private def inboundContent(message: TelegramMessage): Option[String] =
    message.text
      .orElse(message.caption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse {
        message.document.map { document =>
          val filename = document.file_name.getOrElse("document")
          s"Uploaded file: $filename"
        }
      }

  private def documentMetadata(message: TelegramMessage): Map[String, String] =
    message.document match
      case None           => Map.empty
      case Some(document) =>
        Map(
          "telegram.document.file_id"        -> document.file_id,
          "telegram.document.file_unique_id" -> document.file_unique_id,
        ) ++
          document.file_name.map("telegram.document.file_name" -> _) ++
          document.mime_type.map("telegram.document.mime_type" -> _) ++
          document.file_size.map(size => "telegram.document.file_size" -> size.toString)

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
        workflowNotifier
          .notifyCommand(chatId, Some(replyToMessageId), BotCommand.Status(action.runId))
          .mapError(notifierError)
      case "cancel"  =>
        handleTaskAction(chatId, replyToMessageId, action.runId, "cancel")
      case "retry"   =>
        handleTaskAction(chatId, replyToMessageId, action.runId, "retry")
      case "toggle"  =>
        handleTaskAction(chatId, replyToMessageId, action.runId, "toggle")
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
        sendInlineControls(chatId, replyToMessageId, runId)
      case BotCommand.Logs(runId)   =>
        sendInlineControls(chatId, replyToMessageId, runId)
      case _                        =>
        ZIO.unit

  private def sendInlineControls(
    chatId: Long,
    replyToMessageId: Long,
    runId: Long,
  ): IO[MessageChannelError, Unit] =
    taskRepository match
      case None       =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Workflow controls for run $runId:",
          markup = Some(InlineKeyboards.workflowControls(runId, paused = false)),
        )
      case Some(repo) =>
        repo.getRun(runId).mapError(err => MessageChannelError.InvalidMessage(s"task lookup failed: $err")).flatMap {
          case None      =>
            sendCallbackFeedback(
              chatId = chatId,
              replyToMessageId = replyToMessageId,
              text = s"Task #$runId not found.",
              markup = None,
            )
          case Some(run) =>
            sendCallbackFeedback(
              chatId = chatId,
              replyToMessageId = replyToMessageId,
              text = s"Task controls for #$runId:",
              markup = InlineKeyboards.taskStatusKeyboard(runId, run.status),
            )
        }

  private def handleTaskAction(
    chatId: Long,
    replyToMessageId: Long,
    runId: Long,
    action: String,
  ): IO[MessageChannelError, Unit] =
    (taskRepository, taskExecutor) match
      case (Some(repo), Some(executor)) =>
        for
          runOpt <- repo
                      .getRun(runId)
                      .mapError(err => MessageChannelError.InvalidMessage(s"task lookup failed: $err"))
          _      <- runOpt match
                      case None      =>
                        sendCallbackFeedback(
                          chatId = chatId,
                          replyToMessageId = replyToMessageId,
                          text = s"Task #$runId not found.",
                          markup = None,
                        )
                      case Some(run) =>
                        action.toLowerCase match
                          case "cancel" =>
                            cancelTask(chatId, replyToMessageId, run, repo, executor)
                          case "retry"  =>
                            retryTask(chatId, replyToMessageId, run, repo, executor)
                          case "toggle" =>
                            if run.status == RunStatus.Paused then resumeTask(chatId, replyToMessageId, run, repo, executor)
                            else pauseTask(chatId, replyToMessageId, run, repo, executor)
                          case other    =>
                            sendCallbackFeedback(
                              chatId = chatId,
                              replyToMessageId = replyToMessageId,
                              text = s"Unsupported action '$other'.",
                              markup = InlineKeyboards.taskStatusKeyboard(runId, run.status),
                            )
        yield ()
      case _                            =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Task controls are unavailable for #$runId.",
          markup = None,
        )

  private def cancelTask(
    chatId: Long,
    replyToMessageId: Long,
    run: TaskRunRow,
    repository: TaskRepository,
    executor: TaskExecutor,
  ): IO[MessageChannelError, Unit] =
    run.status match
      case RunStatus.Completed | RunStatus.Cancelled =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Task #${run.id} is ${run.status.toString.toLowerCase} and cannot be cancelled.",
          markup = InlineKeyboards.taskStatusKeyboard(run.id, run.status),
        )
      case _                                         =>
        for
          now <- Clock.instant
          _   <- repository
                   .updateRun(
                     run.copy(
                       status = RunStatus.Cancelled,
                       completedAt = Some(now),
                       errorMessage = Some("Cancelled from Telegram inline action"),
                     )
                   )
                   .mapError(err => MessageChannelError.InvalidMessage(s"task update failed: $err"))
          _   <- executor.cancel(run.id)
          _   <- client
                   .editMessageReplyMarkup(
                     chatId = chatId,
                     messageId = replyToMessageId,
                     replyMarkup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Cancelled),
                   )
                   .ignore
          _   <- sendCallbackFeedback(
                   chatId = chatId,
                   replyToMessageId = replyToMessageId,
                   text = s"✓ Task #${run.id} cancelled",
                   markup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Cancelled),
                 )
        yield ()

  private def pauseTask(
    chatId: Long,
    replyToMessageId: Long,
    run: TaskRunRow,
    repository: TaskRepository,
    executor: TaskExecutor,
  ): IO[MessageChannelError, Unit] =
    run.status match
      case RunStatus.Running | RunStatus.Pending =>
        for
          _ <- executor.cancel(run.id)
          _ <- repository
                 .updateRun(
                   run.copy(
                     status = RunStatus.Paused,
                     completedAt = None,
                     errorMessage = None,
                   )
                 )
                 .mapError(err => MessageChannelError.InvalidMessage(s"task update failed: $err"))
          _ <- client
                 .editMessageReplyMarkup(
                   chatId = chatId,
                   messageId = replyToMessageId,
                   replyMarkup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Paused),
                 )
                 .ignore
          _ <- sendCallbackFeedback(
                 chatId = chatId,
                 replyToMessageId = replyToMessageId,
                 text = s"⏸ Task #${run.id} paused. Tap Resume to continue.",
                 markup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Paused),
               )
        yield ()
      case _                                  =>
        sendCallbackFeedback(
          chatId = chatId,
          replyToMessageId = replyToMessageId,
          text = s"Task #${run.id} is ${run.status.toString.toLowerCase}; pause is not available.",
          markup = InlineKeyboards.taskStatusKeyboard(run.id, run.status),
        )

  private def resumeTask(
    chatId: Long,
    replyToMessageId: Long,
    run: TaskRunRow,
    repository: TaskRepository,
    executor: TaskExecutor,
  ): IO[MessageChannelError, Unit] =
    if run.status != RunStatus.Paused then
      sendCallbackFeedback(
        chatId = chatId,
        replyToMessageId = replyToMessageId,
        text = s"Task #${run.id} is not paused.",
        markup = InlineKeyboards.taskStatusKeyboard(run.id, run.status),
      )
    else
      for
        workflow <- loadWorkflowDefinition(run, repository)
        _        <- repository
                      .updateRun(
                        run.copy(
                          status = RunStatus.Running,
                          completedAt = None,
                          errorMessage = None,
                        )
                      )
                      .mapError(err => MessageChannelError.InvalidMessage(s"task update failed: $err"))
        _        <- executor.start(run.id, workflow)
        _        <- client
                      .editMessageReplyMarkup(
                        chatId = chatId,
                        messageId = replyToMessageId,
                        replyMarkup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Running),
                      )
                      .ignore
        _        <- sendCallbackFeedback(
                      chatId = chatId,
                      replyToMessageId = replyToMessageId,
                      text = s"▶ Task #${run.id} resumed",
                      markup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Running),
                    )
      yield ()

  private def retryTask(
    chatId: Long,
    replyToMessageId: Long,
    run: TaskRunRow,
    repository: TaskRepository,
    executor: TaskExecutor,
  ): IO[MessageChannelError, Unit] =
    if run.status != RunStatus.Failed then
      sendCallbackFeedback(
        chatId = chatId,
        replyToMessageId = replyToMessageId,
        text = s"Task #${run.id} is not failed; retry is unavailable.",
        markup = InlineKeyboards.taskStatusKeyboard(run.id, run.status),
      )
    else
      for
        workflow <- loadWorkflowDefinition(run, repository)
        _        <- repository
                      .updateRun(
                        run.copy(
                          status = RunStatus.Pending,
                          completedAt = None,
                          errorMessage = None,
                        )
                      )
                      .mapError(err => MessageChannelError.InvalidMessage(s"task update failed: $err"))
        _        <- executor.start(run.id, workflow)
        _        <- client
                      .editMessageReplyMarkup(
                        chatId = chatId,
                        messageId = replyToMessageId,
                        replyMarkup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Running),
                      )
                      .ignore
        _        <- sendCallbackFeedback(
                      chatId = chatId,
                      replyToMessageId = replyToMessageId,
                      text = s"Retry requested for task #${run.id}.",
                      markup = InlineKeyboards.taskStatusKeyboard(run.id, RunStatus.Running),
                    )
      yield ()

  private def loadWorkflowDefinition(
    run: TaskRunRow,
    repository: TaskRepository,
  ): IO[MessageChannelError, WorkflowDefinition] =
    for
      workflowId <- ZIO
                      .fromOption(run.workflowId)
                      .orElseFail(MessageChannelError.InvalidMessage(s"Task #${run.id} has no workflow"))
      row        <- repository
                      .getWorkflow(workflowId)
                      .mapError(err => MessageChannelError.InvalidMessage(s"workflow lookup failed: $err"))
                      .someOrFail(MessageChannelError.InvalidMessage(s"Workflow #$workflowId not found"))
      wf         <- decodeWorkflow(row)
    yield wf

  private final case class WorkflowStoragePayload(
    steps: List[TaskStep],
    stepAgents: Map[String, String] = Map.empty,
    dynamicGraph: Option[WorkflowGraph] = None,
  ) derives JsonCodec

  private def decodeWorkflow(row: WorkflowRow): IO[MessageChannelError, WorkflowDefinition] =
    row.steps.fromJson[WorkflowStoragePayload] match
      case Right(payload) =>
        ZIO.succeed(
          WorkflowDefinition(
            id = row.id,
            name = row.name,
            description = row.description,
            steps = payload.steps,
            stepAgents = payload.stepAgents.toList.map { case (step, agent) => WorkflowStepAgent(step, agent) },
            isBuiltin = row.isBuiltin,
            dynamicGraph = payload.dynamicGraph,
          )
        )
      case Left(_)        =>
        row.steps.fromJson[List[TaskStep]] match
          case Right(steps) =>
            ZIO.succeed(
              WorkflowDefinition(
                id = row.id,
                name = row.name,
                description = row.description,
                steps = steps,
                isBuiltin = row.isBuiltin,
              )
            )
          case Left(error) =>
            ZIO.fail(MessageChannelError.InvalidMessage(s"invalid workflow payload for ${row.name}: $error"))

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

  private def maybeSendGeneratedFiles(
    chatId: Long,
    replyToMessageId: Long,
    metadata: Map[String, String],
  ): IO[MessageChannelError, Unit] =
    (for
      paths <- FileTransfer.attachmentPaths(metadata)
      _     <-
        if paths.isEmpty then ZIO.unit
        else
          notifyTransferProgress(
            chatId,
            replyToMessageId,
            FileTransferProgress("prepare", 0, "Preparing file transfer..."),
          ) *>
            fileTransfer
              .sendAsZip(
                chatId = chatId,
                replyToMessageId = Some(replyToMessageId),
                files = paths,
                caption = Some("Generated files archive"),
                onProgress = progress => notifyTransferProgress(chatId, replyToMessageId, progress),
              )
              .mapError(err => MessageChannelError.InvalidMessage(s"telegram file transfer failed: $err"))
              .unit
    yield ()).catchAll(err => ZIO.logWarning(s"telegram file transfer failed: $err"))

  private def notifyTransferProgress(
    chatId: Long,
    replyToMessageId: Long,
    progress: FileTransferProgress,
  ): UIO[Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = s"File transfer: ${progress.percentage}% (${progress.detail})",
          reply_to_message_id = Some(replyToMessageId),
        )
      )
      .unit
      .ignore

object TelegramChannel:
  def make(
    client: TelegramClient,
    workflowNotifier: WorkflowNotifier = WorkflowNotifier.noop,
    taskRepository: Option[TaskRepository] = None,
    taskExecutor: Option[TaskExecutor] = None,
    name: String = "telegram",
    scopeStrategy: SessionScopeStrategy = SessionScopeStrategy.PerConversation,
  ): UIO[TelegramChannel] =
    make(
      client = client,
      fileTransfer = FileTransferLive(client),
      workflowNotifier = workflowNotifier,
      taskRepository = taskRepository,
      taskExecutor = taskExecutor,
      name = name,
      scopeStrategy = scopeStrategy,
    )

  def make(
    client: TelegramClient,
    fileTransfer: FileTransfer,
    workflowNotifier: WorkflowNotifier,
    taskRepository: Option[TaskRepository],
    taskExecutor: Option[TaskExecutor],
    name: String,
    scopeStrategy: SessionScopeStrategy,
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
      fileTransfer = fileTransfer,
      workflowNotifier = workflowNotifier,
      taskRepository = taskRepository,
      taskExecutor = taskExecutor,
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
        transfer = FileTransferLive(client)
        channel <- make(
                     client = client,
                     fileTransfer = transfer,
                     workflowNotifier = WorkflowNotifier.noop,
                     taskRepository = None,
                     taskExecutor = None,
                     name = "telegram",
                     scopeStrategy = SessionScopeStrategy.PerConversation,
                   )
      yield channel
    }
