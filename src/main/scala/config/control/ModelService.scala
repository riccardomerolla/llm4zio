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
    ZIO.succeed(ModelRegistryResponse(Nil))

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
      case LlmProvider.GeminiCli                                            =>
        ZIO.succeed("CLI provider; remote health probe is not required")
      case LlmProvider.GeminiApi                                            =>
        val key = config.apiKey.getOrElse("")
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1beta/models?key=${urlEncode(key)}"
        http.get(url = url, timeout = 10.seconds)
      case LlmProvider.Ollama                                               =>
        val url = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/api/tags"
        http.get(url = url, timeout = 10.seconds)
      case LlmProvider.OpenAI | LlmProvider.LmStudio | LlmProvider.OpenCode =>
        val authHeaders = authHeader(config.apiKey)
        val url         = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/models"
        http.get(url = url, headers = authHeaders, timeout = 10.seconds)
      case LlmProvider.Anthropic                                            =>
        val headers = authHeader(config.apiKey) ++ Map(
          "anthropic-version" -> "2023-06-01"
        )
        val url     = s"${config.baseUrl.getOrElse("").stripSuffix("/")}/v1/models"
        http.get(url = url, headers = headers, timeout = 10.seconds)
      case LlmProvider.Mock                                                 =>
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
      case _                                                                  => false

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def defaultModelFor(provider: LlmProvider): String =
    provider match
      case LlmProvider.GeminiCli => "gemini-2.5-flash"
      case LlmProvider.GeminiApi => "gemini-2.5-flash"
      case LlmProvider.OpenAI    => "gpt-4o-mini"
      case LlmProvider.Anthropic => "claude-3-5-haiku-latest"
      case LlmProvider.LmStudio  => "local-model"
      case LlmProvider.Ollama    => "llama3.1"
      case LlmProvider.OpenCode  => "openai/gpt-4o-mini"
      case LlmProvider.Mock      => "mock-model"
