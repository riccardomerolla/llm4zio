package config.control

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.json.*

import _root_.config.entity.*
import app.control.HttpAIClient
import shared.errors.AIError

enum ProviderAvailability derives JsonCodec:
  case Healthy, Degraded, Unhealthy, Unknown

enum AuthStatus derives JsonCodec:
  case Valid, Missing, Invalid, Unknown

final case class ProviderProbeStatus(
  provider: AIProvider,
  availability: ProviderAvailability,
  auth: AuthStatus,
  statusMessage: String,
  checkedAt: java.time.Instant,
  rateLimitHeadroom: Option[Double] = None,
) derives JsonCodec

final case class ProviderModelGroup(
  provider: AIProvider,
  models: List[AIModel],
) derives JsonCodec

final case class ModelRegistryResponse(
  providers: List[ProviderModelGroup]
) derives JsonCodec

enum ModelServiceError derives JsonCodec:
  case ProbeFailed(provider: AIProvider, message: String)

trait ModelService:
  def listAvailableModels: UIO[ModelRegistryResponse]
  def probeProviders: UIO[List[ProviderProbeStatus]]
  def resolveFallbackChain(primary: AIProviderConfig): UIO[List[AIProviderConfig]]

object ModelService:

  def listAvailableModels: ZIO[ModelService, Nothing, ModelRegistryResponse] =
    ZIO.serviceWithZIO[ModelService](_.listAvailableModels)

  def probeProviders: ZIO[ModelService, Nothing, List[ProviderProbeStatus]] =
    ZIO.serviceWithZIO[ModelService](_.probeProviders)

  def resolveFallbackChain(primary: AIProviderConfig): ZIO[ModelService, Nothing, List[AIProviderConfig]] =
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

  override def resolveFallbackChain(primary: AIProviderConfig): UIO[List[AIProviderConfig]] =
    ZIO.succeed {
      val fallbacks = primary.fallbackChain.models
        .map(ref =>
          AIProviderConfig.withDefaults(
            primary.copy(
              provider = ref.provider.getOrElse(primary.provider),
              model = ref.modelId,
            )
          )
        )
      (primary :: fallbacks).distinctBy(cfg => (cfg.provider, cfg.model, cfg.baseUrl, cfg.apiKey))
    }

  private def probeProvider(
    primary: AIProviderConfig,
    provider: AIProvider,
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
    primary: AIProviderConfig,
    provider: AIProvider,
  ): Either[ProviderProbeStatus, AIProviderConfig] =
    val cfg = AIProviderConfig.withDefaults(
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
    else if cfg.baseUrl.forall(_.trim.isEmpty) && provider != AIProvider.GeminiCli then
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

  private def probeRemote(config: AIProviderConfig): IO[AIError, String] =
    config.provider match
      case AIProvider.GeminiCli                                          =>
        ZIO.succeed("CLI provider; remote health probe is not required")
      case AIProvider.GeminiApi                                          =>
        val key = config.apiKey.getOrElse("")
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1beta/models?key=${urlEncode(key)}"
        http.get(url = url, timeout = 10.seconds)
      case AIProvider.Ollama                                             =>
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/api/tags"
        http.get(url = url, timeout = 10.seconds)
      case AIProvider.OpenAi | AIProvider.LmStudio | AIProvider.OpenCode =>
        val authHeaders = authHeader(config.apiKey)
        val url         = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/models"
        http.get(url = url, headers = authHeaders, timeout = 10.seconds)
      case AIProvider.Anthropic                                          =>
        val headers = authHeader(config.apiKey) ++ Map(
          "anthropic-version" -> "2023-06-01"
        )
        val url     = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1/models"
        http.get(url = url, headers = headers, timeout = 10.seconds)

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

  private def needsApiKey(provider: AIProvider): Boolean =
    provider match
      case AIProvider.GeminiApi | AIProvider.OpenAi | AIProvider.Anthropic => true
      case _                                                               => false

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def defaultModelFor(provider: AIProvider): String =
    catalog.get(provider).flatMap(_.headOption.map(_.modelId)).getOrElse("unknown-model")

  private val catalog: Map[AIProvider, List[AIModel]] = Map(
    AIProvider.GeminiCli -> List(
      AIModel(
        AIProvider.GeminiCli,
        "gemini-2.5-flash",
        "Gemini 2.5 Flash",
        1_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(
        AIProvider.GeminiCli,
        "gemini-2.5-pro",
        "Gemini 2.5 Pro",
        2_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
    ),
    AIProvider.GeminiApi -> List(
      AIModel(
        AIProvider.GeminiApi,
        "gemini-2.5-flash",
        "Gemini 2.5 Flash",
        1_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(
        AIProvider.GeminiApi,
        "gemini-2.5-pro",
        "Gemini 2.5 Pro",
        2_000_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.StructuredOutput),
      ),
      AIModel(AIProvider.GeminiApi, "text-embedding-004", "Text Embedding 004", 8_192, Set(ModelCapability.Embeddings)),
    ),
    AIProvider.OpenAi    -> List(
      AIModel(
        AIProvider.OpenAi,
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
        AIProvider.OpenAi,
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
        AIProvider.OpenAi,
        "text-embedding-3-large",
        "Text Embedding 3 Large",
        8_192,
        Set(ModelCapability.Embeddings),
      ),
    ),
    AIProvider.Anthropic -> List(
      AIModel(
        AIProvider.Anthropic,
        "claude-3-5-sonnet-latest",
        "Claude 3.5 Sonnet",
        200_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.ToolCalling),
      ),
      AIModel(
        AIProvider.Anthropic,
        "claude-3-5-haiku-latest",
        "Claude 3.5 Haiku",
        200_000,
        Set(ModelCapability.Chat, ModelCapability.Streaming, ModelCapability.ToolCalling),
      ),
    ),
    AIProvider.LmStudio  -> List(
      AIModel(
        AIProvider.LmStudio,
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
    AIProvider.Ollama    -> List(
      AIModel(AIProvider.Ollama, "llama3.1", "Llama 3.1", 8_192, Set(ModelCapability.Chat, ModelCapability.Streaming)),
      AIModel(AIProvider.Ollama, "mistral", "Mistral", 8_192, Set(ModelCapability.Chat, ModelCapability.Streaming)),
    ),
    AIProvider.OpenCode  -> List(
      AIModel(
        AIProvider.OpenCode,
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
  )
