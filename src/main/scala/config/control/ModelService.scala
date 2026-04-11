package config.control

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import zio.*

import _root_.config.entity.*
import llm4zio.core.LlmProvider
import shared.errors.AIError
import shared.services.HttpAIClient

trait ModelService:
  def listAvailableModels: UIO[ModelRegistryResponse]
  def probeProviders: UIO[List[ProviderProbeStatus]]
  def resolveFallbackChain(primary: ProviderConfig): UIO[List[ProviderConfig]]

object ModelService:

  def listAvailableModels: ZIO[ModelService, Nothing, ModelRegistryResponse] =
    ZIO.serviceWithZIO[ModelService](_.listAvailableModels)

  def probeProviders: ZIO[ModelService, Nothing, List[ProviderProbeStatus]] =
    ZIO.serviceWithZIO[ModelService](_.probeProviders)

  def resolveFallbackChain(primary: ProviderConfig): ZIO[ModelService, Nothing, List[ProviderConfig]] =
    ZIO.serviceWithZIO[ModelService](_.resolveFallbackChain(primary))

  val live: ZLayer[Ref[GatewayConfig] & HttpAIClient, Nothing, ModelService] =
    ZLayer.fromFunction(ModelServiceLive.apply)

final case class ModelServiceLive(
  configRef: Ref[GatewayConfig],
  http: HttpAIClient,
) extends ModelService:

  override def listAvailableModels: UIO[ModelRegistryResponse] =
    ZIO.succeed {
      val groups = catalog.toList
        .sortBy(_._1.toString)
        .map {
          case (provider, models) =>
            ProviderModelGroup(provider = provider, models = models)
        }
      ModelRegistryResponse(groups)
    }

  override def probeProviders: UIO[List[ProviderProbeStatus]] =
    for
      cfg      <- configRef.get.map(_.resolvedProviderConfig)
      now      <- Clock.instant
      distinct  = (cfg.provider :: cfg.fallbackChain.models.flatMap(_.provider)).distinct
      statuses <- ZIO.foreach(distinct)(probeProvider(cfg, _, now))
    yield statuses

  override def resolveFallbackChain(primary: ProviderConfig): UIO[List[ProviderConfig]] =
    ZIO.succeed {
      val fallbacks = primary.fallbackChain.models
        .map(ref =>
          ProviderConfig.withDefaults(
            primary.copy(
              provider = ref.provider.getOrElse(primary.provider),
              model = ref.modelId,
            )
          )
        )
      (primary :: fallbacks).distinctBy(cfg => (cfg.provider, cfg.model, cfg.baseUrl, cfg.apiKey))
    }

  private def probeProvider(
    primary: ProviderConfig,
    provider: LlmProvider,
    now: java.time.Instant,
  ): UIO[ProviderProbeStatus] =
    providerCheckConfig(primary, provider) match
      case Left(status)  => ZIO.succeed(status.copy(checkedAt = now))
      case Right(target) =>
        probeRemote(target).fold(
          err =>
            ProviderProbeStatus(
              provider = target.provider,
              availability = mapAvailability(err),
              auth = mapAuth(err),
              statusMessage = err.message,
              checkedAt = now,
              rateLimitHeadroom = None,
            ),
          _ =>
            ProviderProbeStatus(
              provider = target.provider,
              availability = ProviderAvailability.Healthy,
              auth = if needsApiKey(target.provider) then AuthStatus.Valid else AuthStatus.Unknown,
              statusMessage = "Provider reachable",
              checkedAt = now,
              rateLimitHeadroom = None,
            ),
        )

  private def providerCheckConfig(
    primary: ProviderConfig,
    provider: LlmProvider,
  ): Either[ProviderProbeStatus, ProviderConfig] =
    val cfg = ProviderConfig.withDefaults(
      primary.copy(
        provider = provider,
        model = defaultModelFor(provider),
      )
    )
    if needsApiKey(provider) && cfg.apiKey.forall(_.trim.isEmpty) then
      Left(
        ProviderProbeStatus(
          provider = provider,
          availability = ProviderAvailability.Unhealthy,
          auth = AuthStatus.Missing,
          statusMessage = "Missing API key",
          checkedAt = java.time.Instant.EPOCH,
          rateLimitHeadroom = None,
        )
      )
    else if cfg.baseUrl.forall(_.trim.isEmpty) && provider != LlmProvider.GeminiCli && provider != LlmProvider.Mock then
      Left(
        ProviderProbeStatus(
          provider = provider,
          availability = ProviderAvailability.Unhealthy,
          auth = AuthStatus.Unknown,
          statusMessage = "Missing base URL",
          checkedAt = java.time.Instant.EPOCH,
          rateLimitHeadroom = None,
        )
      )
    else Right(cfg)

  private def probeRemote(config: ProviderConfig): IO[AIError, String] =
    config.provider match
      case LlmProvider.GeminiCli                                          =>
        ZIO.succeed("CLI provider; remote health probe is not required")
      case LlmProvider.GeminiApi                                          =>
        val key = config.apiKey.getOrElse("")
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1beta/models?key=${urlEncode(key)}"
        http.get(url = url, timeout = 10.seconds)
      case LlmProvider.Ollama                                             =>
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/api/tags"
        http.get(url = url, timeout = 10.seconds)
      case LlmProvider.OpenAI | LlmProvider.LmStudio | LlmProvider.OpenCode =>
        val authHeaders = authHeader(config.apiKey)
        val url         = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/models"
        http.get(url = url, headers = authHeaders, timeout = 10.seconds)
      case LlmProvider.Anthropic                                          =>
        val headers = authHeader(config.apiKey) ++ Map(
          "anthropic-version" -> "2023-06-01"
        )
        val url     = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1/models"
        http.get(url = url, headers = headers, timeout = 10.seconds)
      case LlmProvider.Mock                                               =>
        ZIO.succeed("Mock provider; always available")

  private def authHeader(apiKey: Option[String]): Map[String, String] =
    apiKey.filter(_.trim.nonEmpty) match
      case Some(value) => Map("Authorization" -> s"Bearer $value")
      case None        => Map.empty

  private def mapAvailability(error: AIError): ProviderAvailability =
    error match
      case AIError.AuthenticationFailed(_)           => ProviderAvailability.Unhealthy
      case AIError.ProviderUnavailable(_, _)         =>
        ProviderAvailability.Degraded
      case AIError.Timeout(_)                        =>
        ProviderAvailability.Degraded
      case AIError.RateLimitExceeded(_)              =>
        ProviderAvailability.Degraded
      case AIError.HttpError(code, _) if code >= 500 =>
        ProviderAvailability.Degraded
      case _                                         =>
        ProviderAvailability.Unhealthy

  private def mapAuth(error: AIError): AuthStatus =
    error match
      case AIError.AuthenticationFailed(_) => AuthStatus.Invalid
      case _                               => AuthStatus.Unknown

  private def needsApiKey(provider: LlmProvider): Boolean =
    provider match
      case LlmProvider.GeminiApi | LlmProvider.OpenAI | LlmProvider.Anthropic => true
      case _                                                               => false

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def defaultModelFor(provider: LlmProvider): String =
    catalog.get(provider).flatMap(_.headOption.map(_.modelId)).getOrElse("unknown-model")

  private val catalog: Map[LlmProvider, List[AIModel]] = Map(
    LlmProvider.GeminiCli -> List(
      AIModel(
        LlmProvider.GeminiCli,
        "gemini-2.5-flash",
        "Gemini 2.5 Flash",
        1_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(
        LlmProvider.GeminiCli,
        "gemini-2.5-pro",
        "Gemini 2.5 Pro",
        2_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
    ),
    LlmProvider.GeminiApi -> List(
      AIModel(
        LlmProvider.GeminiApi,
        "gemini-2.5-flash",
        "Gemini 2.5 Flash",
        1_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(
        LlmProvider.GeminiApi,
        "gemini-2.5-pro",
        "Gemini 2.5 Pro",
        2_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(LlmProvider.GeminiApi, "text-embedding-004", "Text Embedding 004", 8_192, Set(ModelCapability.Embeddings)),
    ),
    LlmProvider.OpenAI    -> List(
      AIModel(
        LlmProvider.OpenAI,
        "gpt-4o",
        "GPT-4o",
        128_000,
        Set(
          ModelCapability.Chat,
          ModelCapability.Streaming,
          ModelCapability.ToolCalling,
          ModelCapability.StructuredOutput,
        ),
      ),
      AIModel(
        LlmProvider.OpenAI,
        "gpt-4o-mini",
        "GPT-4o mini",
        128_000,
        Set(
          ModelCapability.Chat,
          ModelCapability.Streaming,
          ModelCapability.ToolCalling,
          ModelCapability.StructuredOutput,
        ),
      ),
      AIModel(
        LlmProvider.OpenAI,
        "text-embedding-3-large",
        "Text Embedding 3 Large",
        8_192,
        Set(ModelCapability.Embeddings),
      ),
    ),
    LlmProvider.Anthropic -> List(
      AIModel(
        LlmProvider.Anthropic,
        "claude-3-5-sonnet-latest",
        "Claude 3.5 Sonnet",
        200_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.ToolCalling),
      ),
      AIModel(
        LlmProvider.Anthropic,
        "claude-3-5-haiku-latest",
        "Claude 3.5 Haiku",
        200_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.ToolCalling),
      ),
    ),
    LlmProvider.LmStudio  -> List(
      AIModel(
        LlmProvider.LmStudio,
        "local-model",
        "Local Model (LM Studio)",
        32_768,
        Set(
          ModelCapability.Chat,
          ModelCapability.Streaming,
          ModelCapability.ToolCalling,
          ModelCapability.StructuredOutput,
        ),
      )
    ),
    LlmProvider.Ollama    -> List(
      AIModel(LlmProvider.Ollama, "llama3.1", "Llama 3.1", 8_192, Set(ModelCapability.Chat, ModelCapability.Streaming)),
      AIModel(LlmProvider.Ollama, "mistral", "Mistral", 8_192, Set(ModelCapability.Chat, ModelCapability.Streaming)),
    ),
    LlmProvider.OpenCode  -> List(
      AIModel(
        LlmProvider.OpenCode,
        "openai/gpt-4o-mini",
        "OpenCode GPT-4o mini",
        128_000,
        Set(
          ModelCapability.Chat,
          ModelCapability.Streaming,
          ModelCapability.ToolCalling,
          ModelCapability.StructuredOutput,
        ),
      )
    ),
    LlmProvider.Mock      -> List(
      AIModel(
        LlmProvider.Mock,
        "mock-model",
        "Mock Model (Demo)",
        128_000,
        Set(
          ModelCapability.Chat,
          ModelCapability.Streaming,
          ModelCapability.StructuredOutput,
        ),
      )
    ),
  )
