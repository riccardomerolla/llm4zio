package llm4zio.core

import zio.*
import zio.json.*

enum LlmProvider derives JsonCodec:
  case GeminiCli, GeminiApi, OpenAI, Anthropic, LmStudio, Ollama, OpenCode, Mock

object LlmProvider:
  def defaultBaseUrl(provider: LlmProvider): Option[String] = provider match
    case LlmProvider.GeminiCli => None
    case LlmProvider.GeminiApi => Some("https://generativelanguage.googleapis.com")
    case LlmProvider.OpenAI    => Some("https://api.openai.com/v1")
    case LlmProvider.Anthropic => Some("https://api.anthropic.com")
    case LlmProvider.LmStudio  => Some("http://localhost:1234/v1")
    case LlmProvider.Ollama    => Some("http://localhost:11434")
    case LlmProvider.OpenCode  => Some("http://localhost:4096")
    case LlmProvider.Mock      => None

  extension (p: LlmProvider)
    def toConnectorId: ConnectorId = p match
      case LlmProvider.GeminiCli => ConnectorId.GeminiCli
      case LlmProvider.GeminiApi => ConnectorId.GeminiApi
      case LlmProvider.OpenAI    => ConnectorId.OpenAI
      case LlmProvider.Anthropic => ConnectorId.Anthropic
      case LlmProvider.LmStudio  => ConnectorId.LmStudio
      case LlmProvider.Ollama    => ConnectorId.Ollama
      case LlmProvider.OpenCode  => ConnectorId.OpenCode
      case LlmProvider.Mock      => ConnectorId.Mock

case class ConnectorId(value: String) derives JsonCodec

object ConnectorId:
  // API
  val OpenAI: ConnectorId    = ConnectorId("openai")
  val Anthropic: ConnectorId = ConnectorId("anthropic")
  val GeminiApi: ConnectorId = ConnectorId("gemini-api")
  val LmStudio: ConnectorId  = ConnectorId("lm-studio")
  val Ollama: ConnectorId    = ConnectorId("ollama")
  // CLI
  val ClaudeCli: ConnectorId = ConnectorId("claude-cli")
  val GeminiCli: ConnectorId = ConnectorId("gemini-cli")
  val OpenCode: ConnectorId  = ConnectorId("opencode")
  val Codex: ConnectorId     = ConnectorId("codex")
  val Copilot: ConnectorId   = ConnectorId("copilot")
  // Test
  val Mock: ConnectorId      = ConnectorId("mock")

  val allApi: List[ConnectorId] = List(OpenAI, Anthropic, GeminiApi, LmStudio, Ollama)
  val allCli: List[ConnectorId] = List(ClaudeCli, GeminiCli, OpenCode, Codex, Copilot)
  val all: List[ConnectorId]    = allApi ++ allCli :+ Mock

enum ConnectorKind derives JsonCodec:
  case Api, Cli

enum MessageRole derives JsonCodec:
  case System, User, Assistant, Tool

case class Message(
  role: MessageRole,
  content: String,
) derives JsonCodec

case class TokenUsage(
  prompt: Int,
  completion: Int,
  total: Int,
) derives JsonCodec

case class LlmResponse(
  content: String,
  usage: Option[TokenUsage] = None,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

/** Streaming chunk from LLM provider
  *
  * @param delta
  *   Text delta (incremental content)
  * @param finishReason
  *   Reason for completion: "stop", "length", "tool_calls", "content_filter", etc.
  * @param usage
  *   Token usage (typically only in final chunk)
  * @param metadata
  *   Provider-specific metadata (model, latency, etc.)
  */
case class LlmChunk(
  delta: String,
  finishReason: Option[String] = None,
  usage: Option[TokenUsage] = None,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

/** Streaming progress metrics
  *
  * @param tokensProcessed
  *   Total tokens processed so far
  * @param tokensPerSecond
  *   Current throughput
  * @param elapsedMs
  *   Time elapsed since start
  * @param estimatedRemainingMs
  *   Estimated time remaining (if known)
  */
case class StreamProgress(
  tokensProcessed: Int,
  tokensPerSecond: Double,
  elapsedMs: Long,
  estimatedRemainingMs: Option[Long] = None,
) derives JsonCodec

case class LlmConfig(
  provider: LlmProvider,
  model: String,
  baseUrl: Option[String] = None,
  apiKey: Option[String] = None,
  timeout: Duration = 300.seconds,
  maxRetries: Int = 3,
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
) derives JsonCodec

object LlmConfig:
  def withDefaults(config: LlmConfig): LlmConfig =
    config.baseUrl match
      case Some(_) => config
      case None    => config.copy(baseUrl = LlmProvider.defaultBaseUrl(config.provider))
