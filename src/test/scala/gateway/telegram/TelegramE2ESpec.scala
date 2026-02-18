package gateway.telegram

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

import zio.*
import zio.test.*

import agents.AgentRegistry
import gateway.MessageChannelError
import gateway.models.*

object TelegramE2ESpec extends ZIOSpecDefault:

  final private case class TestBotClient(
    updatesRef: Ref[List[TelegramUpdate]],
    sentMessagesRef: Ref[List[TelegramSendMessage]],
    sentDocumentsRef: Ref[List[TelegramSendDocument]],
    nextMessageIdRef: Ref[Long],
    failSendMessage: TelegramSendMessage => Option[TelegramClientError] = _ => None,
    failSendDocument: TelegramSendDocument => Option[TelegramClientError] = _ => None,
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
      failSendMessage(request) match
        case Some(error) => ZIO.fail(error)
        case None        =>
          for
            _  <- sentMessagesRef.update(_ :+ request)
            id <- nextMessageIdRef.modify(current => (current, current + 1L))
          yield TelegramMessage(
            message_id = id,
            date = 1710000000L,
            chat = TelegramChat(id = request.chat_id, `type` = "private"),
            text = Some(request.text),
          )

    override def sendDocument(
      request: TelegramSendDocument,
      timeout: Duration,
    ): IO[TelegramClientError, TelegramMessage] =
      failSendDocument(request) match
        case Some(error) => ZIO.fail(error)
        case None        =>
          for
            _  <- sentDocumentsRef.update(_ :+ request)
            id <- nextMessageIdRef.modify(current => (current, current + 1L))
          yield TelegramMessage(
            message_id = id,
            date = 1710000000L,
            chat = TelegramChat(id = request.chat_id, `type` = "private"),
            caption = request.caption,
          )

  final private case class Harness(
    channel: TelegramChannel,
    client: TestBotClient,
    sentMessagesRef: Ref[List[TelegramSendMessage]],
    sentDocumentsRef: Ref[List[TelegramSendDocument]],
  )

  private def makeHarness(
    notifierFactory: (TelegramClient, AgentRegistry) => WorkflowNotifier = (_, _) =>
      WorkflowNotifier.noop,
    failSendMessage: TelegramSendMessage => Option[TelegramClientError] = _ => None,
    failSendDocument: TelegramSendDocument => Option[TelegramClientError] = _ => None,
  ): ZIO[Scope, Nothing, Harness] =
    for
      updatesRef       <- Ref.make(List.empty[TelegramUpdate])
      sentMessagesRef  <- Ref.make(List.empty[TelegramSendMessage])
      sentDocumentsRef <- Ref.make(List.empty[TelegramSendDocument])
      msgCounter       <- Ref.make(1000L)
      client            = TestBotClient(
                            updatesRef = updatesRef,
                            sentMessagesRef = sentMessagesRef,
                            sentDocumentsRef = sentDocumentsRef,
                            nextMessageIdRef = msgCounter,
                            failSendMessage = failSendMessage,
                            failSendDocument = failSendDocument,
                          )
      agentRegistry    <- AgentRegistry.live.build.map(_.get[AgentRegistry])
      channel          <- TelegramChannel.make(
                            client = client,
                            workflowNotifier = notifierFactory(client, agentRegistry),
                          )
    yield Harness(channel, client, sentMessagesRef, sentDocumentsRef)

  private def textUpdate(
    updateId: Long,
    chatId: Long,
    messageId: Long,
    text: String,
  ): TelegramUpdate =
    TelegramUpdate(
      update_id = updateId,
      message = Some(
        TelegramMessage(
          message_id = messageId,
          date = 1710000000L,
          chat = TelegramChat(id = chatId, `type` = "private"),
          text = Some(text),
          from = Some(TelegramUser(id = 10L, is_bot = false, first_name = "Dev", username = Some("dev"))),
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
          from = TelegramUser(id = 10L, is_bot = false, first_name = "Dev", username = Some("dev")),
          message = Some(
            TelegramMessage(
              message_id = messageId,
              date = 1710000000L,
              chat = TelegramChat(id = chatId, `type` = "private"),
              text = Some("controls"),
            )
          ),
          data = Some(data),
        )
      ),
    )

  private def documentUpdate(
    updateId: Long,
    chatId: Long,
    messageId: Long,
    fileName: String,
    caption: Option[String],
  ): TelegramUpdate =
    TelegramUpdate(
      update_id = updateId,
      message = Some(
        TelegramMessage(
          message_id = messageId,
          date = 1710000000L,
          chat = TelegramChat(id = chatId, `type` = "private"),
          caption = caption,
          document = Some(
            TelegramDocument(
              file_id = s"file-$updateId",
              file_unique_id = s"unique-$updateId",
              file_name = Some(fileName),
              mime_type = Some("application/pdf"),
              file_size = Some(256L),
            )
          ),
          from = Some(TelegramUser(id = 10L, is_bot = false, first_name = "Dev", username = Some("dev"))),
        )
      ),
    )

  private def outbound(
    session: SessionKey,
    text: String,
    metadata: Map[String, String] = Map.empty,
  ): NormalizedMessage =
    NormalizedMessage(
      id = s"out-${UUID.randomUUID().toString.take(8)}",
      channelName = "telegram",
      sessionKey = session,
      direction = MessageDirection.Outbound,
      role = MessageRole.Assistant,
      content = text,
      metadata = metadata,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TelegramE2ESpec")(
    test("scenario 1: start conversation -> progress updates -> results download") {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val report = Files.createTempFile("tg-e2e-report", ".pdf")
          Files.writeString(report, "pdf-content", StandardCharsets.UTF_8)
          report
        }.orDie
      )(path => ZIO.attemptBlocking(Files.deleteIfExists(path)).ignore).flatMap { reportPath =>
        for
          harness    <- makeHarness()
          normalized <- harness.channel.ingestUpdate(textUpdate(1L, 501L, 10L, "start migration for payroll module"))
          session     = SessionScopeStrategy.PerConversation.build("telegram", "501")
          _          <- harness.channel.send(outbound(session, "Migration started: discovery in progress"))
          _          <- harness.channel.send(outbound(session, "Progress: 50% (analysis completed)"))
          _          <- harness.channel.send(
                          outbound(
                            session,
                            "Migration completed. Downloading generated report.",
                            metadata = Map("workflow.report" -> reportPath.toString),
                          )
                        )
          sentMsgs   <- harness.sentMessagesRef.get
          sentDocs   <- harness.sentDocumentsRef.get
        yield assertTrue(
          normalized.exists(_.content.contains("start migration")),
          sentMsgs.exists(_.text.contains("Migration started")),
          sentMsgs.exists(_.text.contains("Progress: 50%")),
          sentDocs.nonEmpty,
          sentDocs.head.caption.exists(_.contains("Generated files archive")),
        )
      }
    },
    test("scenario 2: pause -> resume -> completion") {
      for
        harness  <- makeHarness(notifierFactory = WorkflowNotifierLive.apply)
        _        <- harness.channel.ingestUpdate(textUpdate(2L, 502L, 20L, "/status 91"))
        _        <- harness.channel.ingestUpdate(callbackUpdate(3L, 502L, 21L, "wf:toggle:91:running"))
        _        <- harness.channel.ingestUpdate(callbackUpdate(4L, 502L, 22L, "wf:toggle:91:paused"))
        session   = SessionScopeStrategy.PerConversation.build("telegram", "502")
        _        <- harness.channel.send(outbound(session, "Run 91 completed successfully"))
        sentMsgs <- harness.sentMessagesRef.get
      yield assertTrue(
        sentMsgs.exists(_.text.contains("Workflow controls for run 91")),
        sentMsgs.exists(_.text.contains("Pause requested for run 91")),
        sentMsgs.exists(_.text.contains("Resume requested for run 91")),
        sentMsgs.exists(_.text.contains("Run 91 completed successfully")),
      )
    },
    test("scenario 3: error -> retry -> success") {
      for
        harness  <- makeHarness(notifierFactory = WorkflowNotifierLive.apply)
        _        <- harness.channel.ingestUpdate(textUpdate(5L, 503L, 30L, "/status not-a-number"))
        _        <- harness.channel.ingestUpdate(callbackUpdate(6L, 503L, 31L, "wf:retry:77:paused"))
        session   = SessionScopeStrategy.PerConversation.build("telegram", "503")
        _        <- harness.channel.send(outbound(session, "Retry execution finished successfully"))
        sentMsgs <- harness.sentMessagesRef.get
      yield assertTrue(
        sentMsgs.exists(_.text.contains("Invalid run id")),
        sentMsgs.exists(_.text.contains("Retry requested for run 77")),
        sentMsgs.exists(_.text.contains("Retry execution finished successfully")),
      )
    },
    test("scenario 4: file upload -> analysis -> report generation") {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val report = Files.createTempFile("tg-e2e-analysis", ".pdf")
          Files.writeString(report, "analysis-pdf", StandardCharsets.UTF_8)
          report
        }.orDie
      )(path => ZIO.attemptBlocking(Files.deleteIfExists(path)).ignore).flatMap { reportPath =>
        for
          harness    <- makeHarness()
          normalized <- harness.channel.ingestUpdate(
                          documentUpdate(
                            updateId = 7L,
                            chatId = 504L,
                            messageId = 40L,
                            fileName = "customer-master.cob",
                            caption = Some("please analyze this"),
                          )
                        )
          session     = SessionScopeStrategy.PerConversation.build("telegram", "504")
          _          <- harness.channel.send(
                          outbound(
                            session,
                            "Analysis completed, report generated.",
                            metadata = Map("analysis.report" -> reportPath.toString),
                          )
                        )
          sentDocs   <- harness.sentDocumentsRef.get
        yield assertTrue(
          normalized.exists(_.metadata.get("telegram.document.file_name").contains("customer-master.cob")),
          normalized.exists(_.content.contains("please analyze this")),
          sentDocs.nonEmpty,
        )
      }
    },
    test("scenario 5: concurrent multi-session interactions") {
      val users = List(601L, 602L, 603L, 604L)
      for
        harness <- makeHarness()
        _       <- ZIO.foreachParDiscard(users.zipWithIndex) {
                     case (chatId, idx) =>
                       val update = textUpdate(100L + idx.toLong, chatId, 700L + idx.toLong, s"message-$chatId")
                       for
                         _      <- harness.channel.ingestUpdate(update)
                         session = SessionScopeStrategy.PerConversation.build("telegram", chatId.toString)
                         _      <- harness.channel.send(outbound(session, s"ack-$chatId"))
                       yield ()
                   }
        sent    <- harness.sentMessagesRef.get
      yield assertTrue(
        sent.count(_.text.startsWith("ack-")) == users.length,
        users.forall(id => sent.exists(msg => msg.chat_id == id && msg.text == s"ack-$id")),
      )
    },
    test("error handling: rate limits, timeout, parse errors") {
      for
        harnessRate <- makeHarness(
                         failSendMessage = req =>
                           if req.text.contains("trigger-rate-limit") then
                             Some(TelegramClientError.RateLimited(Some(1.second), "429 too many requests"))
                           else None
                       )
        session      = SessionScopeStrategy.PerConversation.build("telegram", "701")
        _           <- harnessRate.channel.open(session)
        sendResult  <- harnessRate.channel.send(outbound(session, "trigger-rate-limit")).either

        harnessParse <- makeHarness(notifierFactory = WorkflowNotifierLive.apply)
        _            <- harnessParse.channel.ingestUpdate(textUpdate(8L, 702L, 50L, "/unknowncmd"))
        parseMsgs    <- harnessParse.sentMessagesRef.get

        timeoutClient = new TelegramClient:
                          override def getUpdates(
                            offset: Option[Long],
                            limit: Int,
                            timeoutSeconds: Int,
                            timeout: Duration,
                          ): IO[TelegramClientError, List[TelegramUpdate]] =
                            ZIO.fail(TelegramClientError.Timeout(timeout))

                          override def sendMessage(
                            request: TelegramSendMessage,
                            timeout: Duration,
                          ): IO[TelegramClientError, TelegramMessage] =
                            ZIO.fail(TelegramClientError.Network("unused"))

                          override def sendDocument(
                            request: TelegramSendDocument,
                            timeout: Duration,
                          ): IO[TelegramClientError, TelegramMessage] =
                            ZIO.fail(TelegramClientError.Network("unused"))

        timeoutChannel <- TelegramChannel.make(client = timeoutClient)
        pollResult     <- timeoutChannel.pollInbound().either
      yield assertTrue(
        sendResult.left.exists {
          case MessageChannelError.InvalidMessage(message) => message.contains("RateLimited")
          case _                                           => false
        },
        parseMsgs.exists(_.text.contains("Unknown command")),
        pollResult.left.exists {
          case MessageChannelError.InvalidMessage(message) => message.contains("Timeout")
          case _                                           => false
        },
      )
    },
    test("performance: processes batch updates with bounded latency") {
      val batchSize = 200
      for
        harness   <- makeHarness()
        startNs   <- Clock.nanoTime
        processed <- ZIO.foreach(0 until batchSize) { idx =>
                       val chatId   = 900L + (idx % 10).toLong
                       val updateId = 1000L + idx.toLong
                       harness.channel.ingestUpdate(textUpdate(updateId, chatId, 2000L + idx.toLong, s"payload-$idx"))
                     }
        endNs     <- Clock.nanoTime
        elapsedMs  = (endNs - startNs).toDouble / 1000000d
      yield assertTrue(
        processed.count(_.nonEmpty) == batchSize,
        elapsedMs >= 0d,
        elapsedMs < 5000d,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
