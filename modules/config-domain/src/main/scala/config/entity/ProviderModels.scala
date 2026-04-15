package config.entity

import zio.*
import zio.json.*

import llm4zio.core.*

case class AIResponse(
  output: String,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

/** Application-level config wrapping the library LlmConfig with app-specific fields (fallbackChain).
  */
case class ProviderConfig(
  provider: LlmProvider = LlmProvider.GeminiCli,
  model: String = "gemini-2.5-flash",
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: zio.Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: zio.Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  fallbackChain: ModelFallbackChain = ModelFallbackChain.empty,
) derives JsonCodec:

  def toLlmConfig: LlmConfig =
    LlmConfig(
      provider = provider,
      model = model,
      baseUrl = baseUrl,
      apiKey = apiKey,
      timeout = timeout,
      maxRetries = maxRetries,
      requestsPerMinute = requestsPerMinute,
      burstSize = burstSize,
      acquireTimeout = acquireTimeout,
      temperature = temperature,
      maxTokens = maxTokens,
    )

  def toConnectorConfig: ConnectorConfig =
    val cid = provider.toConnectorId
    if ConnectorId.allCli.contains(cid) then
      CliConnectorConfig(
        connectorId = cid,
        model = Some(model),
        timeout = timeout,
        maxRetries = maxRetries,
      )
    else
      ApiConnectorConfig(
        connectorId = cid,
        model = Some(model),
        baseUrl = baseUrl,
        apiKey = apiKey,
        timeout = timeout,
        maxRetries = maxRetries,
        requestsPerMinute = requestsPerMinute,
        burstSize = burstSize,
        acquireTimeout = acquireTimeout,
        temperature = temperature,
        maxTokens = maxTokens,
      )

object ProviderConfig:
  def withDefaults(config: ProviderConfig): ProviderConfig =
    config.baseUrl match
      case Some(_) => config
      case None    => config.copy(baseUrl = LlmProvider.defaultBaseUrl(config.provider))

case class GeminiResponse(
  output: String,
  exitCode: Int,
) derives JsonCodec
