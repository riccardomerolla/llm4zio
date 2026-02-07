package core

import zio.*
import zio.json.*

import models.*

object GeminiApiAIService:
  val layer: ZLayer[AIProviderConfig & RateLimiter & HttpAIClient, Nothing, AIService] =
    ZLayer.fromFunction(make)

  private[core] def make(
    providerConfig: AIProviderConfig,
    rateLimiter: RateLimiter,
    httpClient: HttpAIClient,
  ): AIService =
    new AIService {
      override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
        rateLimiter.acquire.mapError(mapRateLimitError) *> executeRequest(prompt)

      override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
        val combinedPrompt = s"$prompt\n\nContext:\n$context"
        rateLimiter.acquire.mapError(mapRateLimitError) *> executeRequest(combinedPrompt)

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        execute("health check").fold(_ => false, _ => true)

      private def executeRequest(prompt: String): ZIO[Any, AIError, AIResponse] =
        for
          baseUrl <- ZIO.fromOption(providerConfig.baseUrl).orElseFail(
                       AIError.InvalidResponse("Missing baseUrl for Gemini API provider")
                     )
          apiKey  <- ZIO.fromOption(providerConfig.apiKey).orElseFail(
                       AIError.AuthenticationFailed("gemini-api")
                     )
          request  = GeminiGenerateContentRequest(
                       contents = List(
                         GeminiContent(
                           parts = List(GeminiPart(text = prompt))
                         )
                       )
                     )
          url      = s"${baseUrl.stripSuffix("/")}/v1beta/models/${providerConfig.model}:generateContent"
          body    <- httpClient.postJson(
                       url = url,
                       body = request.toJson,
                       headers = Map("x-goog-api-key" -> apiKey),
                       timeout = providerConfig.timeout,
                     )
          parsed  <- ZIO
                       .fromEither(body.fromJson[GeminiGenerateContentResponse])
                       .mapError(err => AIError.InvalidResponse(s"Failed to decode Gemini API response: $err"))
          output  <- extractText(parsed)
        yield AIResponse(
          output = output,
          metadata = baseMetadata(parsed),
        )

      private def extractText(response: GeminiGenerateContentResponse): ZIO[Any, AIError, String] =
        val text =
          for
            candidate <- response.candidates.headOption
            part      <- candidate.content.parts.headOption
            value      = part.text.trim
            if value.nonEmpty
          yield value

        ZIO.fromOption(
          text
        ).orElseFail(AIError.InvalidResponse("Gemini API response missing candidates[0].content.parts[0].text"))

      private def baseMetadata(response: GeminiGenerateContentResponse): Map[String, String] =
        val usage = response.usageMetadata.toList.flatMap { meta =>
          List(
            meta.promptTokenCount.map(v => "promptTokenCount" -> v.toString),
            meta.candidatesTokenCount.map(v => "candidatesTokenCount" -> v.toString),
            meta.totalTokenCount.map(v => "totalTokenCount" -> v.toString),
          ).flatten
        }.toMap

        usage ++ Map(
          "provider" -> "gemini-api",
          "model"    -> providerConfig.model,
        )

      private def mapRateLimitError(error: RateLimitError): AIError = error match
        case RateLimitError.AcquireTimeout(timeout) =>
          AIError.RateLimitExceeded(timeout)
        case RateLimitError.InvalidConfig(details)  =>
          AIError.RateLimitMisconfigured(details)
    }
