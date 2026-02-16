package web.controllers

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import _root_.models.TelegramMode
import gateway.*
import gateway.models.*
import gateway.telegram.*

object TelegramControllerSpec extends ZIOSpecDefault:

  final private case class CapturingGateway(messagesRef: Ref[List[NormalizedMessage]]) extends GatewayService:
    override def enqueueInbound(message: NormalizedMessage): UIO[Unit]  = ZIO.unit
    override def enqueueOutbound(message: NormalizedMessage): UIO[Unit] = ZIO.unit

    override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
      messagesRef.update(_ :+ message)

    override def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]] =
      ZIO.succeed(List(message))

    override def metrics: UIO[GatewayMetricsSnapshot] =
      ZIO.succeed(GatewayMetricsSnapshot())

  private val noopTelegramClient: TelegramClient =
    new TelegramClient:
      override def getUpdates(
        offset: Option[Long],
        limit: Int,
        timeoutSeconds: Int,
        timeout: Duration,
      ): IO[TelegramClientError, List[TelegramUpdate]] =
        ZIO.succeed(Nil)

      override def sendMessage(
        request: TelegramSendMessage,
        timeout: Duration,
      ): IO[TelegramClientError, TelegramMessage] =
        ZIO.fail(TelegramClientError.Network("unused in webhook test"))

  private def makeController(
    expectedBotToken: Option[String],
    expectedSecretToken: Option[String],
  ): UIO[(TelegramController, Ref[List[NormalizedMessage]])] =
    for
      messagesRef <- Ref.make(List.empty[NormalizedMessage])
      gateway      = CapturingGateway(messagesRef)
      channelsRef <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
      registry     = ChannelRegistryLive(channelsRef)
      telegram    <- TelegramChannel.make(noopTelegramClient)
      _           <- registry.register(telegram)
      controller  <- TelegramController.make(
                       gatewayService = gateway,
                       channelRegistry = registry,
                       expectedBotToken = expectedBotToken,
                       expectedSecretToken = expectedSecretToken,
                       enabled = true,
                       mode = TelegramMode.Webhook,
                     )
    yield (controller, messagesRef)

  private def sampleUpdateJson(updateId: Long = 11L): String =
    TelegramUpdate(
      update_id = updateId,
      message = Some(
        TelegramMessage(
          message_id = 22L,
          date = 1710000000L,
          chat = TelegramChat(id = 99L, `type` = "private"),
          text = Some("hello from telegram"),
        )
      ),
    ).toJson

  private def webhookRequest(pathToken: String, secret: String, body: String): Request =
    Request
      .post(s"/webhook/telegram/$pathToken", Body.fromString(body))
      .addHeader(Header.Custom("X-Telegram-Bot-Api-Secret-Token", secret))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TelegramControllerSpec")(
    test("rejects invalid bot token and secret token") {
      for
        tuple          <- makeController(Some("bot-123"), Some("secret-123"))
        (controller, _) = tuple
        badPathReq      = webhookRequest("wrong-bot", "secret-123", sampleUpdateJson())
        badSecretReq    = webhookRequest("bot-123", "wrong-secret", sampleUpdateJson())
        badPathResp    <- controller.routes.runZIO(badPathReq)
        badSecretResp  <- controller.routes.runZIO(badSecretReq)
      yield assertTrue(
        badPathResp.status == Status.Unauthorized,
        badSecretResp.status == Status.Forbidden,
      )
    },
    test("deduplicates update_id and routes normalized message once") {
      for
        tuple                    <- makeController(Some("bot-123"), Some("secret-123"))
        (controller, messagesRef) = tuple
        request                   = webhookRequest("bot-123", "secret-123", sampleUpdateJson(updateId = 77L))
        firstResp                <- controller.routes.runZIO(request)
        secondResp               <- controller.routes.runZIO(request)
        captured                 <- messagesRef.get
      yield assertTrue(
        firstResp.status == Status.Ok,
        secondResp.status == Status.Ok,
        captured.length == 1,
        captured.head.channelName == "telegram",
        captured.head.content == "hello from telegram",
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
