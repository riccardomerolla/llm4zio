package gateway.boundary.telegram

import zio.*
import zio.test.*

import gateway.control.*
import gateway.entity.NormalizedMessage

object TelegramPollingServiceSpec extends ZIOSpecDefault:

  final private case class StubTelegramClient(updatesRef: Ref[List[TelegramUpdate]]) extends TelegramClient:
    override def getUpdates(
      offset: Option[Long],
      limit: Int,
      timeoutSeconds: Int,
      timeout: Duration,
    ): IO[TelegramClientError, List[TelegramUpdate]] =
      updatesRef.modify(updates => (updates.take(limit), List.empty[TelegramUpdate]))

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

  final private case class OffsetAwareTelegramClient(updates: List[TelegramUpdate]) extends TelegramClient:
    override def getUpdates(
      offset: Option[Long],
      limit: Int,
      timeoutSeconds: Int,
      timeout: Duration,
    ): IO[TelegramClientError, List[TelegramUpdate]] =
      ZIO.succeed(updates.filter(_.update_id >= offset.getOrElse(Long.MinValue)).take(limit))

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

  final private case class CapturingGateway(messagesRef: Ref[List[NormalizedMessage]]) extends GatewayService:
    override def enqueueInbound(message: NormalizedMessage): UIO[Unit]  = ZIO.unit
    override def enqueueOutbound(message: NormalizedMessage): UIO[Unit] = ZIO.unit

    override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
      messagesRef.update(_ :+ message)

    override def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]] =
      ZIO.succeed(List(message))

    override def metrics: UIO[GatewayMetricsSnapshot] =
      ZIO.succeed(GatewayMetricsSnapshot())

  private def mkUpdate(id: Long): TelegramUpdate =
    TelegramUpdate(
      update_id = id,
      message = Some(
        TelegramMessage(
          message_id = id * 10L,
          date = 1710000000L,
          chat = TelegramChat(id = 42L, `type` = "private"),
          text = Some(s"message-$id"),
        )
      ),
    )

  private def mkCommandUpdate(id: Long, text: String): TelegramUpdate =
    TelegramUpdate(
      update_id = id,
      message = Some(
        TelegramMessage(
          message_id = id * 10L,
          date = 1710000000L,
          chat = TelegramChat(id = 42L, `type` = "private"),
          text = Some(text),
        )
      ),
    )

  private def makeDeps(initialUpdates: List[TelegramUpdate])
    : UIO[(ChannelRegistry, CapturingGateway, Ref[List[NormalizedMessage]])] =
    for
      updatesRef  <- Ref.make(initialUpdates)
      client       = StubTelegramClient(updatesRef)
      telegram    <- TelegramChannel.make(client)
      channelsRef <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
      runtimeRef  <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
      registry     = ChannelRegistryLive(channelsRef, runtimeRef)
      _           <- registry.register(telegram)
      messagesRef <- Ref.make(List.empty[NormalizedMessage])
      gateway      = CapturingGateway(messagesRef)
    yield (registry, gateway, messagesRef)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TelegramPollingServiceSpec")(
    test("runOnce polls updates and routes them to gateway") {
      for
        deps                    <- makeDeps(List(mkUpdate(1L), mkUpdate(2L)))
        (registry, gateway, ref) = deps
        offsetRef               <- Ref.make(Option.empty[Long])
        service                  = TelegramPollingServiceLive(
                                     channelRegistry = registry,
                                     gatewayService = gateway,
                                     config0 = TelegramPollingConfig(enabled = true, batchSize = 10),
                                     offsetRef = offsetRef,
                                   )
        processed               <- service.runOnce
        captured                <- ref.get
      yield assertTrue(
        processed == 2,
        captured.length == 2,
        captured.map(_.content) == List("message-1", "message-2"),
      )
    },
    test("layer starts continuous loop when enabled") {
      for
        deps                    <- makeDeps(List(mkUpdate(7L)))
        (registry, gateway, ref) = deps
        config                   = TelegramPollingConfig(
                                     enabled = true,
                                     pollInterval = 1.second,
                                     batchSize = 10,
                                     timeoutSeconds = 1,
                                     requestTimeout = 2.seconds,
                                   )
        layer                    = (ZLayer.succeed(registry) ++ ZLayer.succeed(gateway)) >>> TelegramPollingService
                                     .layer(config)
        env                     <- layer.build
        _                       <- ZIO.succeed(env.get[TelegramPollingService])
        _                       <- TestClock.adjust(2.seconds)
        captured                <- ref.get
      yield assertTrue(
        captured.nonEmpty,
        captured.head.content == "message-7",
      )
    },
    test("runOnce advances offset for command-only updates and avoids loops") {
      val client = OffsetAwareTelegramClient(List(mkCommandUpdate(100L, "/help")))
      for
        telegram    <- TelegramChannel.make(client)
        channelsRef <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        runtimeRef  <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
        registry     = ChannelRegistryLive(channelsRef, runtimeRef)
        _           <- registry.register(telegram)
        messagesRef <- Ref.make(List.empty[NormalizedMessage])
        gateway      = CapturingGateway(messagesRef)
        offsetRef   <- Ref.make(Option.empty[Long])
        service      = TelegramPollingServiceLive(
                         channelRegistry = registry,
                         gatewayService = gateway,
                         config0 = TelegramPollingConfig(enabled = true, batchSize = 10),
                         offsetRef = offsetRef,
                       )
        first       <- service.runOnce
        second      <- service.runOnce
        offset      <- offsetRef.get
        captured    <- messagesRef.get
      yield assertTrue(
        first == 0,
        second == 0,
        offset.contains(101L),
        captured.isEmpty,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(10.seconds)
