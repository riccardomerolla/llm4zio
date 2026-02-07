package core

import zio.*

import models.{ AIError, AIProvider, AIProviderConfig, AIResponse }

trait AIService:
  def execute(prompt: String): ZIO[Any, AIError, AIResponse]

  def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse]

  def isAvailable: ZIO[Any, Nothing, Boolean]

object AIService:
  def execute(prompt: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.execute(prompt))

  def executeWithContext(prompt: String, context: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.executeWithContext(prompt, context))

  def isAvailable: ZIO[AIService, Nothing, Boolean] =
    ZIO.serviceWithZIO[AIService](_.isAvailable)

  val fromConfig: ZLayer[AIProviderConfig & RateLimiter & HttpAIClient, AIError, AIService] =
    ZLayer.fromZIO {
      for
        providerConfig <- ZIO.service[AIProviderConfig]
        rateLimiter    <- ZIO.service[RateLimiter]
        httpClient     <- ZIO.service[HttpAIClient]
      yield providerConfig.provider match
        case AIProvider.GeminiCli => GeminiCliAIService.make(providerConfig, rateLimiter, GeminiCliExecutor.default)
        case AIProvider.GeminiApi => GeminiApiAIService.make(providerConfig, rateLimiter, httpClient)
        case AIProvider.OpenAi    => OpenAICompatAIService(providerConfig, rateLimiter, httpClient)
        case AIProvider.Anthropic => AnthropicCompatAIService(providerConfig, rateLimiter, httpClient)
    }
