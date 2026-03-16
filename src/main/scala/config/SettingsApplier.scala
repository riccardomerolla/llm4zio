package config

import zio.*

import _root_.config.entity.*
import db.{ ConfigRepository, PersistenceError }

/** Converts flat Map[String, String] (from DB settings) to nested config objects.
  *
  * This enables hot reload of settings without restart:
  *   1. Read settings from application_settings table
  *   2. Parse them into AIProviderConfig, TelegramBotConfig, etc.
  *   3. Rebuild GatewayConfig
  *   4. Update Ref[GatewayConfig] to apply changes immediately
  */
object SettingsApplier:

  def loadGatewayConfig(baseConfig: GatewayConfig): ZIO[ConfigRepository, PersistenceError, GatewayConfig] =
    for
      rows <- ConfigRepository.getAllSettings
      map   = rows.map(row => row.key -> row.value).toMap
    yield if map.nonEmpty then toGatewayConfig(map) else baseConfig

  /** Convert DB settings map to GatewayConfig
    *
    * Merges settings with priority:
    *   1. Settings from map (DB settings)
    *   2. Defaults from GatewayConfig
    */
  def toGatewayConfig(settings: Map[String, String]): GatewayConfig =
    GatewayConfig(
      aiProvider = toAIProviderConfig(settings),
      dryRun = settings.get("gateway.dryRun").map(_ == "true").getOrElse(false),
      verbose = settings.get("gateway.verbose").map(_ == "true").getOrElse(false),
      telegram = toTelegramConfig(settings),
    )

  /** Convert DB settings map to AIProviderConfig */
  def toAIProviderConfig(settings: Map[String, String]): Option[AIProviderConfig] =
    settings.get("ai.provider").map { providerStr =>
      val provider = parseAIProvider(providerStr).getOrElse(AIProvider.GeminiCli)
      AIProviderConfig(
        provider = provider,
        model = settings.getOrElse("ai.model", "gemini-2.5-flash"),
        baseUrl = settings.get("ai.baseUrl").filter(_.nonEmpty),
        apiKey = settings.get("ai.apiKey").filter(_.nonEmpty),
        timeout = parseDuration(settings.get("ai.timeout")).getOrElse(300.seconds),
        maxRetries = settings.get("ai.maxRetries").flatMap(_.toIntOption).getOrElse(3),
        requestsPerMinute = settings.get("ai.requestsPerMinute").flatMap(_.toIntOption).getOrElse(60),
        burstSize = settings.get("ai.burstSize").flatMap(_.toIntOption).getOrElse(10),
        acquireTimeout = parseDuration(settings.get("ai.acquireTimeout")).getOrElse(30.seconds),
        temperature = settings.get("ai.temperature").flatMap(_.toDoubleOption),
        maxTokens = settings.get("ai.maxTokens").flatMap(_.toIntOption),
        fallbackChain = parseFallbackChain(settings.get("ai.fallbackChain"), provider),
      )
    }

  /** Convert DB settings map to TelegramBotConfig */
  def toTelegramConfig(settings: Map[String, String]): TelegramBotConfig =
    val enabled = settings.get("telegram.enabled").map(_ == "true").getOrElse(false)
    val modeStr = settings.getOrElse("telegram.mode", "Webhook")
    val mode    = if modeStr == "Polling" then TelegramMode.Polling else TelegramMode.Webhook

    TelegramBotConfig(
      enabled = enabled,
      mode = mode,
      botToken = settings.get("telegram.botToken").filter(_.nonEmpty),
      secretToken = settings.get("telegram.secretToken").filter(_.nonEmpty),
      webhookUrl = settings.get("telegram.webhookUrl").filter(_.nonEmpty),
      polling = TelegramPollingSettings(
        interval = parseDuration(settings.get("telegram.polling.interval")).getOrElse(1.second),
        batchSize = settings.get("telegram.polling.batchSize").flatMap(_.toIntOption).getOrElse(100),
        timeoutSeconds = settings.get("telegram.polling.timeout").flatMap(_.toIntOption).getOrElse(30),
        requestTimeout = parseDuration(settings.get("telegram.polling.requestTimeout")).getOrElse(70.seconds),
      ),
    )

  /** Parse AI provider string to enum */
  private def parseAIProvider(str: String): Option[AIProvider] =
    str match
      case "GeminiCli" => Some(AIProvider.GeminiCli)
      case "GeminiApi" => Some(AIProvider.GeminiApi)
      case "OpenAi"    => Some(AIProvider.OpenAi)
      case "Anthropic" => Some(AIProvider.Anthropic)
      case "LmStudio"  => Some(AIProvider.LmStudio)
      case "Ollama"    => Some(AIProvider.Ollama)
      case "OpenCode"  => Some(AIProvider.OpenCode)
      case _           => None

  private def parseFallbackChain(valueOpt: Option[String], defaultProvider: AIProvider): ModelFallbackChain =
    val refs = valueOpt
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
      .flatMap(_.split(",").toList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(parseModelRef(_, defaultProvider))

    ModelFallbackChain(refs)

  private def parseModelRef(raw: String, defaultProvider: AIProvider): Option[ModelRef] =
    raw.split(":", 2).toList match
      case providerRaw :: modelRaw :: Nil =>
        val modelId = modelRaw.trim
        parseAIProvider(providerRaw.trim).filter(_ => modelId.nonEmpty).map { provider =>
          ModelRef(provider = Some(provider), modelId = modelId)
        }
      case modelOnly :: Nil               =>
        val modelId = modelOnly.trim
        if modelId.nonEmpty then Some(ModelRef(provider = Some(defaultProvider), modelId = modelId))
        else None
      case _                              => None

  /** Parse duration from seconds string */
  private def parseDuration(valueOpt: Option[String]): Option[zio.Duration] =
    valueOpt.flatMap { value =>
      value.toLongOption.map(seconds => zio.Duration.fromSeconds(seconds))
    }
