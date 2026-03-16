package orchestration.control

import zio.*

import _root_.config.entity.{ AIProvider, AIProviderConfig, ModelFallbackChain, ModelRef }
import db.{ PersistenceError, TaskRepository }

trait AgentConfigResolver:
  def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig]

object AgentConfigResolver:

  def resolveConfig(agentName: String): ZIO[AgentConfigResolver, PersistenceError, AIProviderConfig] =
    ZIO.serviceWithZIO[AgentConfigResolver](_.resolveConfig(agentName))

  val live: ZLayer[TaskRepository & AIProviderConfig, Nothing, AgentConfigResolver] =
    ZLayer.fromFunction(AgentConfigResolverLive.apply)

final case class AgentConfigResolverLive(
  repository: TaskRepository,
  startupConfig: AIProviderConfig,
) extends AgentConfigResolver:

  override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
    val agentPrefix = s"agent.$agentName.ai."
    for
      agentRows            <- repository.getSettingsByPrefix(agentPrefix).catchAll(_ => ZIO.succeed(Nil))
      globalRows           <- repository.getSettingsByPrefix("ai.").catchAll(_ => ZIO.succeed(Nil))
      agentMap              = agentRows.flatMap(row => normalizedAgentEntry(agentPrefix, row.key, row.value)).toMap
      globalMap             = globalRows.map(row => row.key -> row.value).toMap
      globalProvider        =
        firstNonBlank(globalMap.get("ai.provider"), None).flatMap(parseProvider).getOrElse(startupConfig.provider)
      providerKey           = firstNonBlank(agentMap.get("ai.provider"), globalMap.get("ai.provider"))
      provider              = providerKey.flatMap(parseProvider).getOrElse(startupConfig.provider)
      sameProviderAsGlobal  = provider == globalProvider
      sameProviderAsStartup = provider == startupConfig.provider
      agentModel            = firstNonBlank(agentMap.get("ai.model"), None)
      globalModel           = firstNonBlank(globalMap.get("ai.model"), None)
      model                 = agentModel.getOrElse(
                                if sameProviderAsGlobal then globalModel.getOrElse(startupConfig.model)
                                else defaultModelFor(provider)
                              )
      baseUrl               =
        if sameProviderAsGlobal then
          firstNonBlank(agentMap.get("ai.baseUrl"), globalMap.get("ai.baseUrl"))
            .orElse(AIProvider.defaultBaseUrl(provider))
            .orElse(if sameProviderAsStartup then startupConfig.baseUrl else None)
        else
          firstNonBlank(agentMap.get("ai.baseUrl"), None)
            .orElse(AIProvider.defaultBaseUrl(provider))
            .orElse(if sameProviderAsStartup then startupConfig.baseUrl else None)
      apiKey                =
        if sameProviderAsGlobal then
          firstNonBlank(agentMap.get("ai.apiKey"), globalMap.get("ai.apiKey"))
            .orElse(if sameProviderAsStartup then startupConfig.apiKey else None)
        else
          firstNonBlank(agentMap.get("ai.apiKey"), None)
            .orElse(if sameProviderAsStartup then startupConfig.apiKey else None)
      resolved              = AIProviderConfig.withDefaults(
                                startupConfig.copy(
                                  provider = provider,
                                  model = model,
                                  baseUrl = baseUrl,
                                  apiKey = apiKey,
                                  timeout = firstNonBlank(agentMap.get("ai.timeout"), globalMap.get("ai.timeout"))
                                    .flatMap(_.toLongOption)
                                    .map(Duration.fromSeconds)
                                    .getOrElse(startupConfig.timeout),
                                  maxRetries = firstNonBlank(agentMap.get("ai.maxRetries"), globalMap.get("ai.maxRetries"))
                                    .flatMap(_.toIntOption)
                                    .getOrElse(startupConfig.maxRetries),
                                  requestsPerMinute =
                                    firstNonBlank(agentMap.get("ai.requestsPerMinute"), globalMap.get("ai.requestsPerMinute"))
                                      .flatMap(_.toIntOption)
                                      .getOrElse(startupConfig.requestsPerMinute),
                                  burstSize = firstNonBlank(agentMap.get("ai.burstSize"), globalMap.get("ai.burstSize"))
                                    .flatMap(_.toIntOption)
                                    .getOrElse(startupConfig.burstSize),
                                  acquireTimeout = firstNonBlank(
                                    agentMap.get("ai.acquireTimeout"),
                                    globalMap.get("ai.acquireTimeout"),
                                  )
                                    .flatMap(_.toLongOption)
                                    .map(Duration.fromSeconds)
                                    .getOrElse(startupConfig.acquireTimeout),
                                  temperature = firstNonBlank(agentMap.get("ai.temperature"), globalMap.get("ai.temperature"))
                                    .flatMap(_.toDoubleOption)
                                    .orElse(startupConfig.temperature),
                                  maxTokens = firstNonBlank(agentMap.get("ai.maxTokens"), globalMap.get("ai.maxTokens"))
                                    .flatMap(_.toIntOption)
                                    .orElse(startupConfig.maxTokens),
                                  fallbackChain = parseFallbackChain(
                                    firstNonBlank(agentMap.get("ai.fallbackChain"), globalMap.get("ai.fallbackChain")),
                                    provider,
                                  ),
                                )
                              )
    yield resolved

  private def normalizedAgentEntry(prefix: String, key: String, value: String): Option[(String, String)] =
    val suffix = key.stripPrefix(prefix)
    if suffix.nonEmpty then Some(s"ai.$suffix" -> value) else None

  private def firstNonBlank(first: Option[String], second: Option[String]): Option[String] =
    first.map(_.trim).filter(_.nonEmpty).orElse(second.map(_.trim).filter(_.nonEmpty))

  private def parseProvider(value: String): Option[AIProvider] =
    value.trim.toLowerCase.replaceAll("[\\s_-]+", "") match
      case "geminicli" => Some(AIProvider.GeminiCli)
      case "geminiapi" => Some(AIProvider.GeminiApi)
      case "openai"    => Some(AIProvider.OpenAi)
      case "anthropic" => Some(AIProvider.Anthropic)
      case "lmstudio"  => Some(AIProvider.LmStudio)
      case "ollama"    => Some(AIProvider.Ollama)
      case "opencode"  => Some(AIProvider.OpenCode)
      case _           => None

  private def defaultModelFor(provider: AIProvider): String =
    provider match
      case AIProvider.GeminiCli => "gemini-2.5-flash"
      case AIProvider.GeminiApi => "gemini-2.5-flash"
      case AIProvider.OpenAi    => "gpt-4o-mini"
      case AIProvider.Anthropic => "claude-3-5-haiku-latest"
      case AIProvider.LmStudio  => "openai/gpt-oss-20b"
      case AIProvider.Ollama    => "llama3.2:3b"
      case AIProvider.OpenCode  => "gpt-4o-mini"

  private def parseFallbackChain(raw: Option[String], defaultProvider: AIProvider): ModelFallbackChain =
    val refs = raw
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
        parseProvider(providerRaw.trim).filter(_ => modelId.nonEmpty).map { provider =>
          ModelRef(provider = Some(provider), modelId = modelId)
        }
      case modelOnly :: Nil               =>
        val modelId = modelOnly.trim
        if modelId.nonEmpty then Some(ModelRef(provider = Some(defaultProvider), modelId = modelId))
        else None
      case _                              => None
