package core

import zio.*

import models.{ AIError, AIProvider, AIProviderConfig, AIResponse, ResponseSchema }

trait AIService:
  def execute(prompt: String): ZIO[Any, AIError, AIResponse]

  def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse]

  def executeWithConfig(prompt: String, config: AIProviderConfig): ZIO[Any, AIError, AIResponse] =
    execute(prompt)

  def executeStructured(prompt: String, schema: ResponseSchema): ZIO[Any, AIError, AIResponse] =
    execute(prompt)

  def isAvailable: ZIO[Any, Nothing, Boolean]

object AIService:
  def execute(prompt: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.execute(prompt))

  def executeWithContext(prompt: String, context: String): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.executeWithContext(prompt, context))

  def executeWithConfig(prompt: String, config: AIProviderConfig): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.executeWithConfig(prompt, config))

  def executeStructured(prompt: String, schema: ResponseSchema): ZIO[AIService, AIError, AIResponse] =
    ZIO.serviceWithZIO[AIService](_.executeStructured(prompt, schema))

  def isAvailable: ZIO[AIService, Nothing, Boolean] =
    ZIO.serviceWithZIO[AIService](_.isAvailable)

  /** Create AIService from AIProviderConfig using old implementations
    *
    * NOTE: This is kept for backwards compatibility during migration.
    * New code should use AIService.fromLlmService via the bridge.
    */
  val fromConfig: ZLayer[AIProviderConfig & RateLimiter & HttpAIClient, AIError, AIService] =
    ZLayer.fromZIO {
      for
        defaultProviderConfig <- ZIO.service[AIProviderConfig]
        rateLimiter           <- ZIO.service[RateLimiter]
        httpClient            <- ZIO.service[HttpAIClient]
      yield new AIService {
        private def build(config: AIProviderConfig): AIService =
          config.provider match
            case AIProvider.GeminiCli => GeminiCliAIService.make(config, rateLimiter, GeminiCliExecutor.default)
            case AIProvider.GeminiApi => GeminiApiAIService.make(config, rateLimiter, httpClient)
            case AIProvider.OpenAi    => OpenAICompatAIService(config, rateLimiter, httpClient)
            case AIProvider.Anthropic => AnthropicCompatAIService(config, rateLimiter, httpClient)

        override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
          build(defaultProviderConfig).execute(prompt)

        override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
          build(defaultProviderConfig).executeWithContext(prompt, context)

        override def executeWithConfig(prompt: String, config: AIProviderConfig): ZIO[Any, AIError, AIResponse] =
          build(AIProviderConfig.withDefaults(config)).execute(prompt)

        override def executeStructured(prompt: String, schema: ResponseSchema): ZIO[Any, AIError, AIResponse] =
          build(defaultProviderConfig).executeStructured(prompt, schema)

        override def isAvailable: ZIO[Any, Nothing, Boolean] =
          build(defaultProviderConfig).isAvailable
      }
    }

  /** Create AIService from LlmService using the bridge
    *
    * This is the new way to create AIService using llm4zio.
    * Call this from your DI layer with a properly configured LlmService.
    */
  def fromLlmService(llmService: llm4zio.core.LlmService): AIService =
    AIServiceBridge.fromLlmService(llmService)
