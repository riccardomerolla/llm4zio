package gateway.telegram

import zio.*

import _root_.models.{ MigrationConfig, TelegramMode }
import gateway.*

final case class TelegramPollingConfig(
  enabled: Boolean = false,
  pollInterval: Duration = 1.second,
  batchSize: Int = 100,
  timeoutSeconds: Int = 30,
  requestTimeout: Duration = 70.seconds,
)

trait TelegramPollingService:
  def config: TelegramPollingConfig

  def runOnce: UIO[Int]
  def runLoop: UIO[Nothing]

object TelegramPollingService:
  def runOnce: ZIO[TelegramPollingService, Nothing, Int] =
    ZIO.serviceWithZIO[TelegramPollingService](_.runOnce)

  def layer(config: TelegramPollingConfig): ZLayer[ChannelRegistry & GatewayService, Nothing, TelegramPollingService] =
    ZLayer.scoped {
      for
        registry <- ZIO.service[ChannelRegistry]
        gateway  <- ZIO.service[GatewayService]
        service   = TelegramPollingServiceLive(registry, gateway, sanitizeConfig(config))
        _        <- service.runLoop.forkScoped.when(service.config.enabled)
      yield service
    }

  val live: ZLayer[ChannelRegistry & GatewayService & MigrationConfig, Nothing, TelegramPollingService] =
    ZLayer.scoped {
      for
        config0  <- ZIO.service[MigrationConfig]
        registry <- ZIO.service[ChannelRegistry]
        gateway  <- ZIO.service[GatewayService]
        config    = fromMigrationConfig(config0)
        service   = TelegramPollingServiceLive(registry, gateway, config)
        _        <- service.runLoop.forkScoped.when(service.config.enabled)
      yield service
    }

  private def fromMigrationConfig(config: MigrationConfig): TelegramPollingConfig =
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
  config: TelegramPollingConfig,
) extends TelegramPollingService:

  override def runOnce: UIO[Int] =
    (for
      channel  <- channelRegistry.get("telegram")
      messages <- channel match
                    case telegram: TelegramChannel =>
                      telegram.pollInbound(
                        limit = config.batchSize,
                        timeoutSeconds = config.timeoutSeconds,
                        timeout = config.requestTimeout,
                      )
                    case _                         =>
                      ZIO.fail(MessageChannelError.InvalidMessage("telegram channel is not a TelegramChannel"))
      _        <- ZIO.foreachDiscard(messages)(message => gatewayService.processInbound(message).ignore)
    yield messages.length)
      .catchAll(err =>
        ZIO.logWarning(s"telegram polling iteration failed: $err").as(0)
      )

  override def runLoop: UIO[Nothing] =
    runOnce *> ZIO.sleep(config.pollInterval) *> runLoop
