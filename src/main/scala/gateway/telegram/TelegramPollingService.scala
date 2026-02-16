package gateway.telegram

import zio.*

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
  private val EnabledEnvKey        = "MIGRATION_TELEGRAM_POLLING_ENABLED"
  private val IntervalMsEnvKey     = "MIGRATION_TELEGRAM_POLL_INTERVAL_MS"
  private val BatchSizeEnvKey      = "MIGRATION_TELEGRAM_POLL_BATCH_SIZE"
  private val TimeoutSecondsEnvKey = "MIGRATION_TELEGRAM_POLL_TIMEOUT_SECONDS"
  private val RequestTimeoutMsKey  = "MIGRATION_TELEGRAM_POLL_REQUEST_TIMEOUT_MS"

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

  val live: ZLayer[ChannelRegistry & GatewayService, Nothing, TelegramPollingService] =
    ZLayer.scoped {
      for
        config   <- loadConfig
        registry <- ZIO.service[ChannelRegistry]
        gateway  <- ZIO.service[GatewayService]
        service   = TelegramPollingServiceLive(registry, gateway, config)
        _        <- service.runLoop.forkScoped.when(service.config.enabled)
      yield service
    }

  private def loadConfig: UIO[TelegramPollingConfig] =
    for
      enabledRaw        <- System.env(EnabledEnvKey).orDie
      intervalRaw       <- System.env(IntervalMsEnvKey).orDie
      batchSizeRaw      <- System.env(BatchSizeEnvKey).orDie
      timeoutSecondsRaw <- System.env(TimeoutSecondsEnvKey).orDie
      requestTimeoutRaw <- System.env(RequestTimeoutMsKey).orDie
    yield sanitizeConfig(
      TelegramPollingConfig(
        enabled = enabledRaw.exists(parseBoolean),
        pollInterval = intervalRaw
          .flatMap(_.toLongOption)
          .map(Duration.fromMillis)
          .getOrElse(1.second),
        batchSize = batchSizeRaw.flatMap(_.toIntOption).getOrElse(100),
        timeoutSeconds = timeoutSecondsRaw.flatMap(_.toIntOption).getOrElse(30),
        requestTimeout = requestTimeoutRaw
          .flatMap(_.toLongOption)
          .map(Duration.fromMillis)
          .getOrElse(70.seconds),
      )
    )

  private def parseBoolean(raw: String): Boolean =
    raw.trim.toLowerCase match
      case "1" | "true" | "yes" | "on" => true
      case _                           => false

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
