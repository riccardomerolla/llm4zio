package core

import zio.*
import zio.json.*

import models.*

final case class OpenAICompatAIService(
  config: AIProviderConfig,
  rateLimiter: RateLimiter,
  httpClient: HttpAIClient,
) extends AIService:

  override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
    rateLimiter.acquire.mapError(mapRateLimitError) *> executeWithRetry(prompt)

  override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
    val combinedPrompt = s"$prompt\n\nContext:\n$context"
    rateLimiter.acquire.mapError(mapRateLimitError) *> executeWithRetry(combinedPrompt)

  override def isAvailable: ZIO[Any, Nothing, Boolean] =
    config.baseUrl match
      case None          => ZIO.succeed(false)
      case Some(baseUrl) =>
        httpClient
          .get(
            url = s"${baseUrl.stripSuffix("/")}/models",
            headers = authHeaders,
            timeout = config.timeout,
          )
          .as(true)
          .catchAll(_ => ZIO.succeed(true))

  private def executeWithRetry(prompt: String): ZIO[Any, AIError, AIResponse] =
    val policy = RetryPolicy(
      maxRetries = config.maxRetries,
      baseDelay = Duration.fromSeconds(1),
      maxDelay = Duration.fromSeconds(30),
    )

    RetryPolicy.withRetry(
      executeOnce(prompt),
      policy,
      isRetryable,
    )

  private def executeOnce(prompt: String): ZIO[Any, AIError, AIResponse] =
    for
      baseUrl <-
        ZIO.fromOption(config.baseUrl).orElseFail(AIError.InvalidResponse("Missing baseUrl for OpenAI provider"))
      request  = ChatCompletionRequest(
                   model = config.model,
                   messages = List(ChatMessage(role = "user", content = prompt)),
                   temperature = Some(config.temperature.getOrElse(0.1)),
                   max_tokens = Some(config.maxTokens.getOrElse(32768)),
                   max_completion_tokens = None,
                   stream = Some(false),
                 )
      url      = s"${baseUrl.stripSuffix("/")}/chat/completions"
      body    <- httpClient.postJson(
                   url = url,
                   body = request.toJson,
                   headers = authHeaders,
                   timeout = config.timeout,
                 )
      parsed  <- ZIO
                   .fromEither(body.fromJson[ChatCompletionResponse])
                   .mapError(err => AIError.InvalidResponse(s"Failed to decode OpenAI response: $err"))
      content <- extractContent(parsed)
      metadata = extractMetadata(parsed)
    yield AIResponse(output = content, metadata = metadata)

  private def extractContent(response: ChatCompletionResponse): ZIO[Any, AIError, String] =
    val maybeContent =
      response.choices.headOption.flatMap { choice =>
        choice.message.map(_.content).orElse(choice.text).map(_.trim).filter(_.nonEmpty)
      }

    ZIO.fromOption(maybeContent).orElseFail(AIError.InvalidResponse("OpenAI response missing choices[0] content"))

  private def extractMetadata(response: ChatCompletionResponse): Map[String, String] =
    val usageMetadata = response.usage.toList.flatMap { usage =>
      List(
        usage.prompt_tokens.map(v => "prompt_tokens" -> v.toString),
        usage.completion_tokens.map(v => "completion_tokens" -> v.toString),
        usage.total_tokens.map(v => "total_tokens" -> v.toString),
      ).flatten
    }

    val finishReason =
      response.choices.headOption.flatMap(_.finish_reason).filter(_.nonEmpty).map(v => "finish_reason" -> v)

    (usageMetadata ++ finishReason.toList).toMap ++ Map(
      "model" -> response.model.getOrElse(config.model)
    )

  private def isRetryable(error: AIError): Boolean = error match
    case AIError.RateLimitExceeded(_)                                        => true
    case AIError.HttpError(status, _) if status == 429 || status == 500      => true
    case AIError.HttpError(status, _) if status == 503                       => true
    case AIError.ProviderUnavailable(_, cause) if cause.contains("HTTP 500") => true
    case AIError.ProviderUnavailable(_, cause) if cause.contains("HTTP 503") => true
    case _                                                                   => false

  private def authHeaders: Map[String, String] =
    config.apiKey match
      case Some(value) if value.nonEmpty => Map("Authorization" -> s"Bearer $value")
      case _                             => Map.empty

  private def mapRateLimitError(error: RateLimitError): AIError = error match
    case RateLimitError.AcquireTimeout(timeout) =>
      AIError.RateLimitExceeded(timeout)
    case RateLimitError.InvalidConfig(details)  =>
      AIError.RateLimitMisconfigured(details)

object OpenAICompatAIService:
  val layer: ZLayer[AIProviderConfig & RateLimiter & HttpAIClient, Nothing, AIService] =
    ZLayer.fromFunction(OpenAICompatAIService.apply)
