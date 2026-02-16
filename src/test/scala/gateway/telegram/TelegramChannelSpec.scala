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
  ) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
