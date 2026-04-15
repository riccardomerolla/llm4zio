package llm4zio.core

import zio.*
import zio.json.*

sealed trait ConnectorConfig:
  def connectorId: ConnectorId
  def model: Option[String]
  def timeout: Duration
  def maxRetries: Int

object ConnectorConfig:
  given JsonCodec[ConnectorConfig] = DeriveJsonCodec.gen[ConnectorConfig]

final case class ApiConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String] = None,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
) extends ConnectorConfig derives JsonCodec:

  def toLlmConfig: LlmConfig = LlmConfig(
    provider = connectorId match
      case ConnectorId.OpenAI    => LlmProvider.OpenAI
      case ConnectorId.Anthropic => LlmProvider.Anthropic
      case ConnectorId.GeminiApi => LlmProvider.GeminiApi
      case ConnectorId.LmStudio  => LlmProvider.LmStudio
      case ConnectorId.Ollama    => LlmProvider.Ollama
      case ConnectorId.Mock      => LlmProvider.Mock
      case _                     => LlmProvider.OpenAI,
    model = model.getOrElse(""),
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

final case class CliConnectorConfig(
  connectorId: ConnectorId,
  model: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  flags: Map[String, String] = Map.empty,
  sandbox: Option[CliSandbox] = None,
  turnLimit: Option[Int] = None,
  envVars: Map[String, String] = Map.empty,
) extends ConnectorConfig derives JsonCodec

final case class FallbackChain(connectors: List[ConnectorConfig] = Nil) derives JsonCodec:
  def nonEmpty: Boolean = connectors.nonEmpty
  def isEmpty: Boolean  = connectors.isEmpty

object FallbackChain:
  val empty: FallbackChain = FallbackChain()
