package models

import zio.*
import zio.json.*

enum AIProvider derives JsonCodec:
  case GeminiCli, GeminiApi, OpenAi, Anthropic, LmStudio, Ollama, OpenCode

object AIProvider:
  def defaultBaseUrl(provider: AIProvider): Option[String] = provider match
    case AIProvider.GeminiCli => None
    case AIProvider.GeminiApi => Some("https://generativelanguage.googleapis.com")
    case AIProvider.OpenAi    => Some("https://api.openai.com/v1")
    case AIProvider.Anthropic => Some("https://api.anthropic.com")
    case AIProvider.LmStudio  => Some("http://localhost:1234")
    case AIProvider.Ollama    => Some("http://localhost:11434")
    case AIProvider.OpenCode  => Some("http://localhost:4096")

case class AIResponse(
  output: String,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class AIProviderConfig(
  provider: AIProvider = AIProvider.GeminiCli,
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
) derives JsonCodec

object AIProviderConfig:
  def withDefaults(config: AIProviderConfig): AIProviderConfig =
    config.baseUrl match
      case Some(_) => config
      case None    => config.copy(baseUrl = AIProvider.defaultBaseUrl(config.provider))

case class GeminiResponse(
  output: String,
  exitCode: Int,
) derives JsonCodec
