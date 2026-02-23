package gateway.boundary.telegram

import zio.*

import _root_.models.{ MigrationConfig, TelegramMode }
import gateway.control.*

final case class TelegramPollingConfig(
  enabled: Boolean = false,
  pollInterval: Duration = 1.second,
  batchSize: Int = 100,
  timeoutSeconds: Int = 30,
  requestTimeout: Duration = 70.seconds,
)

trait TelegramPollingService:
  def config: UIO[TelegramPollingConfig]

  def runOnce: UIO[Int]
  def runLoop: UIO[Nothing]

object TelegramPollingService:
  def runOnce: ZIO[TelegramPollingService, Nothing, Int] =
    ZIO.serviceWithZIO[TelegramPollingService](_.runOnce)

  def layer(config: TelegramPollingConfig): ZLayer[ChannelRegistry & GatewayService, Nothing, TelegramPollingService] =
    ZLayer.scoped {
      for
        registry  <- ZIO.service[ChannelRegistry]
        gateway   <- ZIO.service[GatewayService]
        offsetRef <- Ref.make(Option.empty[Long])
        service    = TelegramPollingServiceLive(registry, gateway, sanitizeConfig(config), offsetRef)
        _         <- service.config.flatMap(cfg => service.runLoop.forkScoped.when(cfg.enabled))
      yield service
    }

  val live: ZLayer[ChannelRegistry & GatewayService & Ref[MigrationConfig], Nothing, TelegramPollingService] =
    ZLayer.scoped {
      for
        configRef <- ZIO.service[Ref[MigrationConfig]]
        registry  <- ZIO.service[ChannelRegistry]
        gateway   <- ZIO.service[GatewayService]
        offsetRef <- Ref.make(Option.empty[Long])
        service    = TelegramPollingServiceDynamicLive(registry, gateway, configRef, offsetRef)
        _         <- service.runLoop.forkScoped
      yield service
    }

  private[telegram] def fromMigrationConfig(config: MigrationConfig): TelegramPollingConfig =
    val telegram = config.telegram
    sanitizeConfig(
      TelegramPollingConfig(
        enabled = telegram.enabled && telegram.mode == TelegramMode.Polling,
        pollInterval = telegram.polling.interval,
        batchSize = telegram.polling.batchSize,
        timeoutSeconds = telegram.polling.timeoutSeconds,
        requestTimeout = telegram.polling.requestTimeout,
      )
    )

  private[telegram] def pollOnce(
    channelRegistry: ChannelRegistry,
    gatewayService: GatewayService,
    offsetRef: Ref[Option[Long]],
    config: TelegramPollingConfig,
  ): UIO[Int] =
    (for
      offset  <- offsetRef.get
      channel <- channelRegistry.get("telegram")
      batch   <- channel match
                   case telegram: TelegramChannel =>
                     telegram.pollInboundBatch(
                       offset = offset,
                       limit = config.batchSize,
                       timeoutSeconds = config.timeoutSeconds,
                       timeout = config.requestTimeout,
                     )
                   case _                         =>
                     ZIO.fail(MessageChannelError.InvalidMessage("telegram channel is not a TelegramChannel"))
      _       <- ZIO.foreachDiscard(batch.messages)(message => gatewayService.processInbound(message).ignore)
      _       <- offsetRef.update(current => batch.nextOffset.orElse(current))
      now     <- Clock.instant
      _       <- channelRegistry.markActivity("telegram", now)
      _       <- channelRegistry.markConnected("telegram")
    yield batch.messages.length)
      .catchAll(err =>
        channelRegistry.markError("telegram", err.toString) *>
          ZIO.logWarning(s"telegram polling iteration failed: $err").as(0)
      )

  private def sanitizeConfig(config: TelegramPollingConfig): TelegramPollingConfig =
    config.copy(
      pollInterval = if config.pollInterval <= Duration.Zero then 1.second else config.pollInterval,
      batchSize = if config.batchSize <= 0 then 100 else config.batchSize,
      timeoutSeconds = if config.timeoutSeconds <= 0 then 30 else config.timeoutSeconds,
      requestTimeout = if config.requestTimeout <= Duration.Zero then 70.seconds else config.requestTimeout,
    )

final case class TelegramPollingServiceLive(
  channelRegistry: ChannelRegistry,
  gatewayService: GatewayService,
  config0: TelegramPollingConfig,
  offsetRef: Ref[Option[Long]],
) extends TelegramPollingService:

  override def config: UIO[TelegramPollingConfig] =
    ZIO.succeed(config0)

  override def runOnce: UIO[Int] =
    TelegramPollingService.pollOnce(channelRegistry, gatewayService, offsetRef, config0)

  override def runLoop: UIO[Nothing] =
    runOnce *> ZIO.sleep(config0.pollInterval) *> runLoop

final case class TelegramPollingServiceDynamicLive(
  channelRegistry: ChannelRegistry,
  gatewayService: GatewayService,
  configRef: Ref[MigrationConfig],
  offsetRef: Ref[Option[Long]],
) extends TelegramPollingService:

  override def config: UIO[TelegramPollingConfig] =
    configRef.get.map(TelegramPollingService.fromMigrationConfig)

  override def runOnce: UIO[Int] =
    config.flatMap { cfg =>
      if cfg.enabled then TelegramPollingService.pollOnce(channelRegistry, gatewayService, offsetRef, cfg)
      else channelRegistry.markNotConfigured("telegram").as(0)
    }

  override def runLoop: UIO[Nothing] =
    config.flatMap { cfg =>
      runOnce *> ZIO.sleep(cfg.pollInterval)
    } *> runLoop
