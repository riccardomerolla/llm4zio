package config

import java.net.URI
import java.nio.file.{ Path, Paths }

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

import com.typesafe.config.{ Config as TypesafeConfig, ConfigFactory }
import models.{ AIProvider, AIProviderConfig, MigrationConfig, TelegramMode }

/** Configuration loader using ZIO Config with HOCON support
  *
  * Supports loading configuration from:
  *   - HOCON/JSON configuration files
  *   - Environment variables
  *   - Default values defined in MigrationConfig
  *
  * Configuration priority (highest to lowest):
  *   1. Environment variables (prefixed with MIGRATION_)
  *   2. Config file (application.conf or specified path)
  *   3. Default values
  */
object ConfigLoader:

  /** Config instances for custom types */
  given Config[Path]         = Config.string.map(str => Paths.get(str))
  given Config[zio.Duration] = Config.duration.map(d => zio.Duration.fromJava(d))

  private val LocalHosts: Set[String] = Set("localhost", "127.0.0.1", "0.0.0.0", "::1")

  private val ConfigAiPath = "migration.ai"

  /** Load configuration from default sources (application.conf + environment variables)
    *
    * @return
    *   ZIO effect that loads the configuration or fails with a configuration error
    */
  def load: IO[Config.Error, MigrationConfig] =
    ZIO.config(deriveConfig[MigrationConfig].nested("migration"))

  /** Load configuration from a specific file path
    *
    * @param configPath
    *   Path to the configuration file (HOCON or JSON)
    * @return
    *   ZIO effect that loads the configuration or fails with a configuration error
    */
  def loadFromFile(configPath: Path): IO[Config.Error, MigrationConfig] =
    ZIO
      .config(deriveConfig[MigrationConfig].nested("migration"))
      .provideLayer(
        ZLayer.succeed(ConfigProvider.fromHoconFile(configPath.toFile))
      )

  /** Load configuration with environment variable overrides
    *
    * Environment variables are prefixed with MIGRATION_ and use snake_case. For example:
    *   - MIGRATION_SOURCE_DIR
    *   - MIGRATION_GEMINI_MODEL
    *   - MIGRATION_PARALLELISM
    *
    * @return
    *   ZIO effect that loads the configuration with env var overrides
    */
  def loadWithEnvOverrides: IO[Config.Error, MigrationConfig] =
    val envProvider  = ConfigProvider.envProvider.nested("migration")
    val fileProvider = ConfigProvider.defaultProvider

    ZIO
      .config(deriveConfig[MigrationConfig])
      .provideLayer(ZLayer.succeed(envProvider.orElse(fileProvider)))

  /** Load AI provider config section from default application config.
    */
  def loadAIProviderFromDefaultConfig: IO[String, Option[AIProviderConfig]] =
    ZIO.fromEither(parseAIProviderSection(ConfigFactory.load().resolve()))

  /** Load AI provider config section from specific file.
    */
  def loadAIProviderFromFile(configPath: Path): IO[String, Option[AIProviderConfig]] =
    ZIO.fromEither(parseAIProviderSection(ConfigFactory.parseFile(configPath.toFile).resolve()))

  /** Apply environment variable overrides for AI provider settings.
    *
    * Supported variables:
    *   - MIGRATION_AI_PROVIDER
    *   - MIGRATION_AI_MODEL
    *   - MIGRATION_AI_BASE_URL
    *   - MIGRATION_AI_API_KEY
    *   - MIGRATION_AI_TIMEOUT
    *   - MIGRATION_AI_MAX_RETRIES
    *   - MIGRATION_AI_REQUESTS_PER_MINUTE
    *   - MIGRATION_AI_BURST_SIZE
    *   - MIGRATION_AI_ACQUIRE_TIMEOUT
    *   - MIGRATION_AI_TEMPERATURE
    *   - MIGRATION_AI_MAX_TOKENS
    */
  def applyAIEnvironmentOverrides(config: MigrationConfig): IO[String, MigrationConfig] =
    ZIO
      .attempt(sys.env.toMap)
      .mapError(_.getMessage)
      .flatMap(env => applyAIEnvironmentOverrides(config, env))

  /** Apply environment variable overrides from a provided map (testable overload).
    */
  def applyAIEnvironmentOverrides(config: MigrationConfig, environment: Map[String, String])
    : IO[String, MigrationConfig] =
    val env = environment.collect {
      case (k, v) if v.trim.nonEmpty => k -> v.trim
    }

    val base = config.resolvedProviderConfig

    val provider = env.get("MIGRATION_AI_PROVIDER") match
      case Some(value) => parseAIProvider(value)
      case None        => Right(base.provider)

    val timeout = env.get("MIGRATION_AI_TIMEOUT") match
      case Some(value) => parseInt("MIGRATION_AI_TIMEOUT", value).map(seconds => Duration.fromSeconds(seconds.toLong))
      case None        => Right(base.timeout)

    val maxRetries = env.get("MIGRATION_AI_MAX_RETRIES") match
      case Some(value) => parseInt("MIGRATION_AI_MAX_RETRIES", value)
      case None        => Right(base.maxRetries)

    val requestsPerMinute = env.get("MIGRATION_AI_REQUESTS_PER_MINUTE") match
      case Some(value) => parseInt("MIGRATION_AI_REQUESTS_PER_MINUTE", value)
      case None        => Right(base.requestsPerMinute)

    val burstSize = env.get("MIGRATION_AI_BURST_SIZE") match
      case Some(value) => parseInt("MIGRATION_AI_BURST_SIZE", value)
      case None        => Right(base.burstSize)

    val acquireTimeout = env.get("MIGRATION_AI_ACQUIRE_TIMEOUT") match
      case Some(value) =>
        parseInt("MIGRATION_AI_ACQUIRE_TIMEOUT", value).map(seconds => Duration.fromSeconds(seconds.toLong))
      case None        => Right(base.acquireTimeout)

    val temperature = env.get("MIGRATION_AI_TEMPERATURE") match
      case Some(value) => parseDouble("MIGRATION_AI_TEMPERATURE", value).map(Some(_))
      case None        => Right(base.temperature)

    val maxTokens = env.get("MIGRATION_AI_MAX_TOKENS") match
      case Some(value) => parseInt("MIGRATION_AI_MAX_TOKENS", value).map(Some(_))
      case None        => Right(base.maxTokens)

    val providerConfigEither: Either[String, AIProviderConfig] =
      for
        p  <- provider
        t  <- timeout
        mr <- maxRetries
        rp <- requestsPerMinute
        bs <- burstSize
        at <- acquireTimeout
        tp <- temperature
        mt <- maxTokens
      yield AIProviderConfig.withDefaults(
        base.copy(
          provider = p,
          model = env.getOrElse("MIGRATION_AI_MODEL", base.model),
          baseUrl = env.get("MIGRATION_AI_BASE_URL").orElse(base.baseUrl),
          apiKey = env.get("MIGRATION_AI_API_KEY").orElse(base.apiKey),
          timeout = t,
          maxRetries = mr,
          requestsPerMinute = rp,
          burstSize = bs,
          acquireTimeout = at,
          temperature = tp,
          maxTokens = mt,
        )
      )

    ZIO.fromEither(providerConfigEither.map(providerConfig => config.copy(aiProvider = Some(providerConfig))))

  /** Validate configuration values
    *
    * @param config
    *   The configuration to validate
    * @return
    *   ZIO effect that either succeeds with the config or fails with validation errors
    */
  def validate(config: MigrationConfig): IO[String, MigrationConfig] =
    val providerConfig = config.resolvedProviderConfig
    for
      _ <- validateParallelism(config.parallelism)
      _ <- validateBatchSize(config.batchSize)
      _ <- validateRetries(providerConfig.maxRetries)
      _ <- validateTimeout(providerConfig.timeout)
      _ <- validateRateLimiter(
             providerConfig.requestsPerMinute,
             providerConfig.burstSize,
             providerConfig.acquireTimeout,
           )
      _ <- validateAIProvider(providerConfig)
      _ <- validateDiscovery(config.discoveryMaxDepth, config.discoveryExcludePatterns)
      _ <- validateTelegram(config)
    yield config

  private def validateParallelism(parallelism: Int): IO[String, Unit] =
    ZIO
      .fail(s"Parallelism must be between 1 and 64, got: $parallelism")
      .when(parallelism < 1 || parallelism > 64)
      .unit

  private def validateBatchSize(batchSize: Int): IO[String, Unit] =
    ZIO
      .fail(s"Batch size must be between 1 and 100, got: $batchSize")
      .when(batchSize < 1 || batchSize > 100)
      .unit

  private def validateRetries(retries: Int): IO[String, Unit] =
    ZIO
      .fail(s"Max retries must be between 0 and 10, got: $retries")
      .when(retries < 0 || retries > 10)
      .unit

  private def validateTimeout(timeout: zio.Duration): IO[String, Unit] =
    ZIO
      .fail(s"Timeout must be between 1 second and 10 minutes, got: ${timeout.toSeconds}s")
      .when(timeout.toSeconds < 1 || timeout.toSeconds > 600)
      .unit

  private def validateRateLimiter(
    requestsPerMinute: Int,
    burstSize: Int,
    acquireTimeout: zio.Duration,
  ): IO[String, Unit] =
    for
      _ <- ZIO
             .fail(s"Gemini requests per minute must be between 1 and 600, got: $requestsPerMinute")
             .when(requestsPerMinute < 1 || requestsPerMinute > 600)
      _ <- ZIO
             .fail(s"Gemini burst size must be between 1 and 100, got: $burstSize")
             .when(burstSize < 1 || burstSize > 100)
      _ <- ZIO
             .fail(s"Gemini acquire timeout must be between 1 and 300 seconds, got: ${acquireTimeout.toSeconds}s")
             .when(acquireTimeout.toSeconds < 1 || acquireTimeout.toSeconds > 300)
    yield ()

  private def validateAIProvider(config: AIProviderConfig): IO[String, Unit] =
    for
      _ <- config.baseUrl match
             case Some(url) => validateBaseUrl(url)
             case None      => ZIO.unit
      _ <- config.temperature match
             case Some(t) =>
               ZIO
                 .fail(s"AI temperature must be between 0.0 and 2.0, got: $t")
                 .when(t < 0.0 || t > 2.0)
             case None    => ZIO.unit
      _ <- config.maxTokens match
             case Some(tokens) =>
               ZIO
                 .fail(s"AI max tokens must be between 1 and 1048576, got: $tokens")
                 .when(tokens < 1 || tokens > 1048576)
             case None         => ZIO.unit
      _ <- validateApiKeyRequirement(config)
    yield ()

  private def validateBaseUrl(baseUrl: String): IO[String, Unit] =
    ZIO.fromEither(
      scala.util.Try(URI.create(baseUrl)).toEither.left
        .map(_ => s"AI baseUrl must be a valid HTTP(S) URL, got: $baseUrl")
        .flatMap { uri =>
          val schemeValid = Option(uri.getScheme).exists(s => s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))
          val hostValid   = Option(uri.getHost).exists(_.nonEmpty)
          if schemeValid && hostValid then Right(())
          else Left(s"AI baseUrl must be a valid HTTP(S) URL, got: $baseUrl")
        }
    )

  private def validateApiKeyRequirement(config: AIProviderConfig): IO[String, Unit] =
    config.provider match
      case AIProvider.OpenAi | AIProvider.Anthropic =>
        val cloudTarget = config.baseUrl match
          case Some(url) => !isLocalEndpoint(url)
          case None      => true

        if cloudTarget && config.apiKey.forall(_.trim.isEmpty) then
          ZIO.fail(s"AI apiKey is required for ${config.provider} cloud endpoints")
        else ZIO.unit
      case _                                        =>
        ZIO.unit

  private def validateDiscovery(maxDepth: Int, excludes: List[String]): IO[String, Unit] =
    for
      _ <- ZIO
             .fail(s"Discovery max depth must be between 1 and 100, got: $maxDepth")
             .when(maxDepth < 1 || maxDepth > 100)
      _ <- ZIO
             .fail("Discovery exclude patterns cannot contain empty values")
             .when(excludes.exists(_.trim.isEmpty))
    yield ()

  private def validateTelegram(config: MigrationConfig): IO[String, Unit] =
    val telegram = config.telegram
    if !telegram.enabled then ZIO.unit
    else
      for
        _ <- ZIO
               .fail("Telegram bot token is required when telegram integration is enabled")
               .when(telegram.botToken.forall(_.trim.isEmpty))
        _ <- telegram.mode match
               case TelegramMode.Webhook =>
                 for
                   _ <- ZIO
                          .fail("Telegram webhook URL is required in webhook mode")
                          .when(telegram.webhookUrl.forall(_.trim.isEmpty))
                   _ <- ZIO.foreachDiscard(telegram.webhookUrl.filter(_.trim.nonEmpty))(validateBaseUrl)
                 yield ()
               case TelegramMode.Polling =>
                 for
                   _ <- ZIO
                          .fail(
                            s"Telegram polling interval must be > 0, got ${telegram.polling.interval.toMillis}ms"
                          )
                          .when(telegram.polling.interval <= Duration.Zero)
                   _ <- ZIO
                          .fail(s"Telegram polling batchSize must be > 0, got ${telegram.polling.batchSize}")
                          .when(telegram.polling.batchSize <= 0)
                   _ <- ZIO
                          .fail(
                            s"Telegram polling timeoutSeconds must be > 0, got ${telegram.polling.timeoutSeconds}"
                          )
                          .when(telegram.polling.timeoutSeconds <= 0)
                   _ <-
                     ZIO
                       .fail(
                         s"Telegram polling requestTimeout must be > 0, got ${telegram.polling.requestTimeout.toMillis}ms"
                       )
                       .when(telegram.polling.requestTimeout <= Duration.Zero)
                 yield ()
      yield ()

  private def parseAIProviderSection(config: TypesafeConfig): Either[String, Option[AIProviderConfig]] =
    if !config.hasPath(ConfigAiPath) then Right(None)
    else
      val ai = config.getConfig(ConfigAiPath)

      val providerEither =
        if ai.hasPath("provider") then parseAIProvider(ai.getString("provider"))
        else Right(AIProvider.GeminiCli)

      providerEither.map { provider =>
        val providerConfig = AIProviderConfig(
          provider = provider,
          model = if ai.hasPath("model") then ai.getString("model") else AIProviderConfig().model,
          baseUrl = if ai.hasPath("base-url") then Some(ai.getString("base-url")) else None,
          apiKey = if ai.hasPath("api-key") then Some(ai.getString("api-key")) else None,
          timeout =
            if ai.hasPath("timeout") then Duration.fromJava(ai.getDuration("timeout")) else AIProviderConfig().timeout,
          maxRetries = if ai.hasPath("max-retries") then ai.getInt("max-retries") else AIProviderConfig().maxRetries,
          requestsPerMinute =
            if ai.hasPath("requests-per-minute") then ai.getInt("requests-per-minute")
            else AIProviderConfig().requestsPerMinute,
          burstSize = if ai.hasPath("burst-size") then ai.getInt("burst-size") else AIProviderConfig().burstSize,
          acquireTimeout =
            if ai.hasPath("acquire-timeout") then Duration.fromJava(ai.getDuration("acquire-timeout"))
            else AIProviderConfig().acquireTimeout,
          temperature = if ai.hasPath("temperature") then Some(ai.getDouble("temperature")) else None,
          maxTokens = if ai.hasPath("max-tokens") then Some(ai.getInt("max-tokens")) else None,
        )

        Some(AIProviderConfig.withDefaults(providerConfig))
      }

  private def parseAIProvider(raw: String): Either[String, AIProvider] =
    raw.trim.toLowerCase match
      case "gemini-cli" => Right(AIProvider.GeminiCli)
      case "gemini-api" => Right(AIProvider.GeminiApi)
      case "openai"     => Right(AIProvider.OpenAi)
      case "anthropic"  => Right(AIProvider.Anthropic)
      case other        => Left(s"Invalid AI provider '$other'. Expected one of: gemini-cli|gemini-api|openai|anthropic")

  private def parseInt(name: String, raw: String): Either[String, Int] =
    scala.util.Try(raw.toInt).toEither.left.map(_ => s"$name must be an integer, got: $raw")

  private def parseDouble(name: String, raw: String): Either[String, Double] =
    scala.util.Try(raw.toDouble).toEither.left.map(_ => s"$name must be a decimal number, got: $raw")

  private def isLocalEndpoint(baseUrl: String): Boolean =
    scala.util.Try(URI.create(baseUrl)).toOption.flatMap(uri => Option(uri.getHost)).exists(host =>
      LocalHosts.contains(host.toLowerCase)
    )
