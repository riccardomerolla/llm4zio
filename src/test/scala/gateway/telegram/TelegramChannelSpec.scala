package gateway.telegram

import java.time.Instant

import zio.*
import zio.test.*

import gateway.models.*

object TelegramChannelSpec extends ZIOSpecDefault:

  final private case class StubTelegramClient(
    updatesRef: Ref[List[TelegramUpdate]],
    sentRef: Ref[List[TelegramSendMessage]],
    response: TelegramSendMessage => TelegramMessage,
  ) extends TelegramClient:

    override def getUpdates(
      offset: Option[Long],
      limit: Int,
      timeoutSeconds: Int,
      timeout: Duration,
    ): IO[TelegramClientError, List[TelegramUpdate]] =
      updatesRef.get

    override def sendMessage(
      request: TelegramSendMessage,
      timeout: Duration,
    ): IO[TelegramClientError, TelegramMessage] =
      sentRef.update(_ :+ request) *> ZIO.succeed(response(request))

  final private case class CapturingNotifier(
    commandsRef: Ref[List[(Long, Option[Long], BotCommand)]],
    parseErrorsRef: Ref[List[(Long, Option[Long], CommandParseError)]],
  ) extends WorkflowNotifier:
    override def notifyCommand(
      chatId: Long,
      replyToMessageId: Option[Long],
      command: BotCommand,
    ): IO[WorkflowNotifierError, Unit] =
      commandsRef.update(_ :+ (chatId, replyToMessageId, command)).unit

    override def notifyParseError(
      chatId: Long,
      replyToMessageId: Option[Long],
      error: CommandParseError,
    ): IO[WorkflowNotifierError, Unit] =
      parseErrorsRef.update(_ :+ (chatId, replyToMessageId, error)).unit

  private def update(
    updateId: Long,
    chatId: Long,
    messageId: Long,
    text: Option[String],
  ): TelegramUpdate =
    TelegramUpdate(
      update_id = updateId,
      message = Some(
        TelegramMessage(
          message_id = messageId,
          date = 1710000000L,
          chat = TelegramChat(id = chatId, `type` = "private"),
          text = text,
          from = Some(TelegramUser(id = 1L, is_bot = false, first_name = "Alice", username = Some("alice"))),
        )
      ),
    )

  private def callbackUpdate(
    updateId: Long,
    chatId: Long,
    messageId: Long,
    data: String,
  ): TelegramUpdate =
    TelegramUpdate(
      update_id = updateId,
      callback_query = Some(
        TelegramCallbackQuery(
          id = s"cb-$updateId",
          from = TelegramUser(id = 1L, is_bot = false, first_name = "Alice", username = Some("alice")),
          message = Some(
            TelegramMessage(
              message_id = messageId,
              date = 1710000000L,
              chat = TelegramChat(id = chatId, `type` = "private"),
              text = Some("controls"),
              from = Some(TelegramUser(id = 1L, is_bot = false, first_name = "Alice", username = Some("alice"))),
            )
          ),
          data = Some(data),
        )
      ),
    )

  private def outboundMessage(
    session: SessionKey,
    content: String,
    metadata: Map[String, String] = Map.empty,
  ): NormalizedMessage =
    NormalizedMessage(
      id = "out-1",
      channelName = "telegram",
      sessionKey = session,
      direction = MessageDirection.Outbound,
      role = MessageRole.Assistant,
      content = content,
      metadata = metadata,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TelegramChannelSpec")(
    test("ingestUpdate normalizes inbound message and opens scoped session") {
      for
        updatesRef <- Ref.make(List.empty[TelegramUpdate])
        sentRef    <- Ref.make(List.empty[TelegramSendMessage])
        client      = StubTelegramClient(
                        updatesRef = updatesRef,
                        sentRef = sentRef,
                        response = req =>
                          TelegramMessage(
                            message_id = 500L,
                            date = 1710000000L,
                            chat = TelegramChat(id = req.chat_id, `type` = "private"),
                            text = Some(req.text),
                          ),
                      )
        channel    <- TelegramChannel.make(client)
        normalized <- channel.ingestUpdate(update(updateId = 10L, chatId = 42L, messageId = 100L, text = Some("hello")))
        sessions   <- channel.activeSessions
      yield assertTrue(
        normalized.exists(_.sessionKey.value == "conversation:42"),
        normalized.exists(_.direction == MessageDirection.Inbound),
        normalized.exists(_.role == MessageRole.User),
        normalized.exists(_.metadata.get("telegram.chat_id").contains("42")),
        normalized.exists(_.metadata.get("telegram.message_id").contains("100")),
        sessions.contains(SessionScopeStrategy.PerConversation.build("telegram", "42")),
      )
    },
    test("send resolves chat and reply routing from inbound metadata") {
      for
        updatesRef <- Ref.make(List.empty[TelegramUpdate])
        sentRef    <- Ref.make(List.empty[TelegramSendMessage])
        client      = StubTelegramClient(
                        updatesRef = updatesRef,
                        sentRef = sentRef,
                        response = req =>
                          TelegramMessage(
                            message_id = 777L,
                            date = 1710000000L,
                            chat = TelegramChat(id = req.chat_id, `type` = "private"),
                            text = Some(req.text),
                          ),
                      )
        channel    <- TelegramChannel.make(client)
        _          <- channel.ingestUpdate(update(updateId = 11L, chatId = 99L, messageId = 321L, text = Some("question")))
        session     = SessionScopeStrategy.PerConversation.build("telegram", "99")
        fiber      <- channel.outbound(session).take(1).runCollect.fork
        _          <- ZIO.yieldNow.repeatN(20)
        _          <- channel.send(outboundMessage(session, "answer"))
        sent       <- sentRef.get
        out        <- fiber.join
      yield assertTrue(
        sent.length == 1,
        sent.head.chat_id == 99L,
        sent.head.reply_to_message_id.contains(321L),
        out.length == 1,
        out.head.metadata.get("telegram.message_id").contains("777"),
      )
    },
    test("pollInbound ingests only updates that contain non-empty text") {
      for
        updatesRef <- Ref.make(
                        List(
                          update(updateId = 1L, chatId = 10L, messageId = 100L, text = Some("hi")),
                          update(updateId = 2L, chatId = 10L, messageId = 101L, text = Some("   ")),
                          TelegramUpdate(update_id = 3L, message = None),
                        )
                      )
        sentRef    <- Ref.make(List.empty[TelegramSendMessage])
        client      = StubTelegramClient(
                        updatesRef = updatesRef,
                        sentRef = sentRef,
                        response = req =>
                          TelegramMessage(
                            message_id = 400L,
                            date = 1710000000L,
                            chat = TelegramChat(id = req.chat_id, `type` = "private"),
                            text = Some(req.text),
                          ),
                      )
        channel    <- TelegramChannel.make(client)
        messages   <- channel.pollInbound()
      yield assertTrue(
        messages.length == 1,
        messages.head.content == "hi",
        messages.head.sessionKey.value == "conversation:10",
      )
    },
    test("ingestUpdate routes commands to notifier and does not enqueue inbound message") {
      for
        updatesRef     <- Ref.make(List.empty[TelegramUpdate])
        sentRef        <- Ref.make(List.empty[TelegramSendMessage])
        commandsRef    <- Ref.make(List.empty[(Long, Option[Long], BotCommand)])
        parseErrorsRef <- Ref.make(List.empty[(Long, Option[Long], CommandParseError)])
        notifier        = CapturingNotifier(commandsRef, parseErrorsRef)
        client          = StubTelegramClient(
                            updatesRef = updatesRef,
                            sentRef = sentRef,
                            response = req =>
                              TelegramMessage(
                                message_id = 900L,
                                date = 1710000000L,
                                chat = TelegramChat(id = req.chat_id, `type` = "private"),
                                text = Some(req.text),
                              ),
                          )
        channel        <- TelegramChannel.make(client, workflowNotifier = notifier)
        normalized     <-
          channel.ingestUpdate(update(updateId = 20L, chatId = 55L, messageId = 701L, text = Some("/status 12")))
        commands       <- commandsRef.get
        parseErrors    <- parseErrorsRef.get
        sent           <- sentRef.get
      yield assertTrue(
        normalized.isEmpty,
        commands == List((55L, Some(701L), BotCommand.Status(12L))),
        parseErrors.isEmpty,
        sent.exists(_.reply_markup.nonEmpty),
        sent.exists(_.text.contains("Workflow controls for run 12")),
      )
    },
    test("callback query actions route to notifier and update keyboard state") {
      for
        updatesRef     <- Ref.make(List.empty[TelegramUpdate])
        sentRef        <- Ref.make(List.empty[TelegramSendMessage])
        commandsRef    <- Ref.make(List.empty[(Long, Option[Long], BotCommand)])
        parseErrorsRef <- Ref.make(List.empty[(Long, Option[Long], CommandParseError)])
        notifier        = CapturingNotifier(commandsRef, parseErrorsRef)
        client          = StubTelegramClient(
                            updatesRef = updatesRef,
                            sentRef = sentRef,
                            response = req =>
                              TelegramMessage(
                                message_id = 901L,
                                date = 1710000000L,
                                chat = TelegramChat(id = req.chat_id, `type` = "private"),
                                text = Some(req.text),
                              ),
                          )
        channel        <- TelegramChannel.make(client, workflowNotifier = notifier)
        _              <- channel.ingestUpdate(callbackUpdate(30L, 77L, 801L, "wf:details:90:running"))
        _              <- channel.ingestUpdate(callbackUpdate(31L, 77L, 802L, "wf:cancel:90:running"))
        _              <- channel.ingestUpdate(callbackUpdate(32L, 77L, 803L, "wf:toggle:90:running"))
        _              <- channel.ingestUpdate(callbackUpdate(33L, 77L, 804L, "wf:retry:90:paused"))
        commands       <- commandsRef.get
        sent           <- sentRef.get
      yield assertTrue(
        commands.contains((77L, Some(801L), BotCommand.Status(90L))),
        commands.contains((77L, Some(802L), BotCommand.Cancel(90L))),
        sent.exists(_.text.contains("Pause requested for run 90")),
        sent.exists(_.text.contains("Retry requested for run 90")),
        sent.exists(msg => msg.reply_markup.exists(_.inline_keyboard.flatten.exists(_.text == "Resume"))),
      )
    },
    test("send formats long response and supports show more callback") {
      for
        updatesRef <- Ref.make(List.empty[TelegramUpdate])
        sentRef    <- Ref.make(List.empty[TelegramSendMessage])
        client      = StubTelegramClient(
                        updatesRef = updatesRef,
                        sentRef = sentRef,
                        response = req =>
                          TelegramMessage(
                            message_id = req.reply_to_message_id.getOrElse(950L),
                            date = 1710000000L,
                            chat = TelegramChat(id = req.chat_id, `type` = "private"),
                            text = Some(req.text),
                          ),
                      )
        channel    <- TelegramChannel.make(client, workflowNotifier = WorkflowNotifier.noop)
        _          <- channel.ingestUpdate(update(updateId = 41L, chatId = 88L, messageId = 911L, text = Some("hello")))
        session     = SessionScopeStrategy.PerConversation.build("telegram", "88")
        _          <- channel.send(outboundMessage(session, "line\n" * 300))
        sent1      <- sentRef.get
        showMore    = sent1.lastOption.flatMap(_.reply_markup).flatMap(_.inline_keyboard.flatten.headOption)
        token       = showMore.map(_.callback_data.stripPrefix("more:")).getOrElse("missing")
        _          <- channel.ingestUpdate(callbackUpdate(42L, 88L, 912L, s"more:$token"))
        sent2      <- sentRef.get
      yield assertTrue(
        sent1.nonEmpty,
        showMore.exists(_.text == "Show More"),
        sent2.length >= 2,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
