package core

import zio.*
import zio.json.*
import llm4zio.core.{LlmService, LlmConfig, LlmError, LlmResponse}
import llm4zio.tools.JsonSchema
import models.{AIError, AIResponse, AIProviderConfig, ResponseSchema, AIProvider}

/** Bridge layer that adapts llm4zio.core.LlmService to the legacy core.AIService interface.
  *
  * This allows existing agent code to continue using AIService while the underlying
  * implementation uses the new llm4zio framework.
  *
  * Migration strategy:
  *   1. This bridge wraps LlmService and translates types
  *   2. Agents continue using AIService interface unchanged
  *   3. Gradually update agents to use LlmService directly
  *   4. Remove this bridge once migration is complete
  */
object AIServiceBridge:
  /** Create an AIService that delegates to llm4zio.LlmService */
  def fromLlmService(llmService: LlmService): AIService = new AIService:
    override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
      llmService
        .execute(prompt)
        .mapBoth(
          llmError => toAIError(llmError),
          llmResponse => toAIResponse(llmResponse)
        )

    override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
      val enrichedPrompt = s"$context\n\n$prompt"
      execute(enrichedPrompt)

    override def executeWithConfig(prompt: String, config: AIProviderConfig): ZIO[Any, AIError, AIResponse] =
      // For now, ignore the config override and use the configured LlmService
      // Future: support dynamic config switching
      execute(prompt)

    override def executeStructured(prompt: String, schema: ResponseSchema): ZIO[Any, AIError, AIResponse] =
      llmService
        .executeStructured[zio.json.ast.Json](prompt, toJsonSchema(schema))
        .mapBoth(
          llmError => toAIError(llmError),
          json => AIResponse(json.toJson, Map("structured" -> "true"))
        )

    override def isAvailable: ZIO[Any, Nothing, Boolean] =
      llmService.isAvailable

  /** Convert llm4zio LlmConfig to legacy AIProviderConfig */
  def toLlmConfig(config: AIProviderConfig): LlmConfig =
    LlmConfig(
      provider = config.provider match
        case AIProvider.GeminiCli => llm4zio.core.LlmProvider.GeminiCli
        case AIProvider.GeminiApi => llm4zio.core.LlmProvider.GeminiApi
        case AIProvider.OpenAi    => llm4zio.core.LlmProvider.OpenAI
        case AIProvider.Anthropic => llm4zio.core.LlmProvider.Anthropic
      ,
      model = config.model,
      baseUrl = config.baseUrl,
      apiKey = config.apiKey,
      temperature = config.temperature,
      maxTokens = config.maxTokens,
      timeout = config.timeout
    )

  /** Convert llm4zio LlmResponse to legacy AIResponse */
  private def toAIResponse(response: LlmResponse): AIResponse =
    val enrichedMetadata = response.usage match
      case Some(usage) =>
        response.metadata ++ Map(
          "tokens.prompt" -> usage.prompt.toString,
          "tokens.completion" -> usage.completion.toString,
          "tokens.total" -> usage.total.toString
        )
      case None        => response.metadata

    AIResponse(
      output = response.content,
      metadata = enrichedMetadata
    )

  /** Convert llm4zio LlmError to legacy AIError */
  private def toAIError(error: LlmError): AIError = error match
    case LlmError.ProviderError(message, cause) =>
      AIError.ProcessFailed(message + cause.map(c => s": ${c.getMessage}").getOrElse(""))
    case LlmError.RateLimitError(retryAfter) =>
      AIError.RateLimitExceeded(retryAfter.getOrElse(30.seconds))
    case LlmError.AuthenticationError(message) =>
      AIError.AuthenticationFailed(message)
    case LlmError.InvalidRequestError(message) =>
      AIError.InvalidResponse(message)
    case LlmError.TimeoutError(duration) =>
      AIError.Timeout(duration)
    case LlmError.ParseError(message, raw) =>
      AIError.InvalidResponse(s"$message\nRaw: ${raw.take(200)}")
    case LlmError.ToolError(toolName, message) =>
      AIError.ProcessFailed(s"Tool error ($toolName): $message")
    case LlmError.ConfigError(message) =>
      AIError.InvalidResponse(s"Config error: $message")

  /** Convert legacy ResponseSchema to llm4zio JsonSchema */
  private def toJsonSchema(schema: ResponseSchema): JsonSchema =
    // ResponseSchema contains a Json schema, extract it
    schema.schema

  /** Create ZLayer that provides AIService from LlmService */
  val layer: ZLayer[LlmService, Nothing, AIService] =
    ZLayer.fromFunction { (llmService: LlmService) =>
      fromLlmService(llmService)
    }
