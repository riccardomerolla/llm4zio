package core

import zio.*
import zio.json.*

import models.*

final case class AnthropicCompatAIService(
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
    ZIO.succeed(config.baseUrl.nonEmpty)

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
        ZIO.fromOption(config.baseUrl).orElseFail(AIError.InvalidResponse("Missing baseUrl for Anthropic provider"))
      request  = AnthropicRequest(
                   model = config.model,
                   max_tokens = config.maxTokens.getOrElse(32768),
                   messages = List(ChatMessage(role = "user", content = prompt)),
                   temperature = Some(config.temperature.getOrElse(0.1)),
                 )
      url      = s"${baseUrl.stripSuffix("/")}/v1/messages"
      body    <- httpClient.postJson(
                   url = url,
                   body = request.toJson,
                   headers = headers,
                   timeout = config.timeout,
                 )
      parsed  <- parseAnthropicBody(body)
      text    <- extractText(parsed)
      metadata = extractMetadata(parsed)
    yield AIResponse(output = text, metadata = metadata)

  private def parseAnthropicBody(body: String): ZIO[Any, AIError, AnthropicResponse] =
    ZIO
      .fromEither(body.fromJson[AnthropicResponse])
      .mapError(_ => AIError.InvalidResponse(body))
      .catchAll { _ =>
        parseAnthropicError(body)
      }

  private def parseAnthropicError(body: String): ZIO[Any, AIError, AnthropicResponse] =
    val nestedType = """"error"\s*:\s*\{\s*"type"\s*:\s*"([^"]+)""".r
    val topLevel   = """"type"\s*:\s*"([^"]+)""".r
    val errorType  = nestedType
      .findFirstMatchIn(body)
      .map(_.group(1))
      .orElse(topLevel.findAllMatchIn(body).toList.lastOption.map(_.group(1)))
      .getOrElse("unknown_error")

    errorType match
      case "rate_limit_error"     => ZIO.fail(AIError.RateLimitExceeded(config.timeout))
      case "authentication_error" => ZIO.fail(AIError.AuthenticationFailed("anthropic"))
      case "overloaded_error"     => ZIO.fail(AIError.ProviderUnavailable("anthropic", "HTTP 529: overloaded"))
      case _                      => ZIO.fail(AIError.InvalidResponse(s"Failed to decode Anthropic response: $body"))

  private def extractText(response: AnthropicResponse): ZIO[Any, AIError, String] =
    val text = response.content
      .filter(_.`type` == "text")
      .flatMap(_.text)
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString("\n")

    if text.nonEmpty then ZIO.succeed(text)
    else ZIO.fail(AIError.InvalidResponse("Anthropic response missing text content blocks"))

  private def extractMetadata(response: AnthropicResponse): Map[String, String] =
    val usage = response.usage.toList.flatMap { u =>
      List(
        u.input_tokens.map(v => "input_tokens" -> v.toString),
        u.output_tokens.map(v => "output_tokens" -> v.toString),
      ).flatten
    }

    val stopReason = response.stop_reason.filter(_.nonEmpty).map(v => "stop_reason" -> v)

    (usage ++ stopReason.toList).toMap ++ Map(
      "model" -> response.model.getOrElse(config.model)
    )

  private def headers: Map[String, String] =
    config.apiKey match
      case Some(value) if value.nonEmpty =>
        Map(
          "x-api-key"         -> value,
          "Authorization"     -> s"Bearer $value",
          "anthropic-version" -> "2023-06-01",
        )
      case _                             =>
        Map(
          "anthropic-version" -> "2023-06-01"
        )

  private def isRetryable(error: AIError): Boolean = error match
    case AIError.RateLimitExceeded(_)                                        => true
    case AIError.HttpError(status, _) if status == 429 || status == 500      => true
    case AIError.ProviderUnavailable(_, cause) if cause.contains("HTTP 500") => true
    case AIError.ProviderUnavailable(_, cause) if cause.contains("HTTP 529") => true
    case _                                                                   => false

  private def mapRateLimitError(error: RateLimitError): AIError = error match
    case RateLimitError.AcquireTimeout(timeout) => AIError.RateLimitExceeded(timeout)
    case RateLimitError.InvalidConfig(details)  => AIError.RateLimitMisconfigured(details)

object AnthropicCompatAIService:
  val layer: ZLayer[AIProviderConfig & RateLimiter & HttpAIClient, Nothing, AIService] =
    ZLayer.fromFunction(AnthropicCompatAIService.apply)
