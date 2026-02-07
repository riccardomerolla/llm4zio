package core

import zio.*

import models.{ AIError, AIResponse, GeminiError, GeminiResponse, MigrationConfig }

trait GeminiService extends AIService:
  def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse]

  def executeWithContextLegacy(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse]

  override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
    executeLegacy(prompt).map(toAIResponse).mapError(GeminiService.mapGeminiErrorToAIError)

  override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
    executeWithContextLegacy(prompt, context).map(toAIResponse).mapError(GeminiService.mapGeminiErrorToAIError)

  private def toAIResponse(response: GeminiResponse): AIResponse =
    AIResponse(
      output = response.output,
      metadata = Map(
        "exitCode" -> response.exitCode.toString,
        "provider" -> "gemini-cli",
      ),
    )

object GeminiService:
  /** Access the legacy execute method through ZIO service. */
  def execute(prompt: String): ZIO[GeminiService, GeminiError, GeminiResponse] =
    ZIO.serviceWithZIO[GeminiService](_.executeLegacy(prompt))

  /** Access the legacy executeWithContext method through ZIO service. */
  def executeWithContext(prompt: String, context: String): ZIO[GeminiService, GeminiError, GeminiResponse] =
    ZIO.serviceWithZIO[GeminiService](_.executeWithContextLegacy(prompt, context))

  /** Access the isAvailable method through ZIO service. */
  def isAvailable: ZIO[GeminiService, Nothing, Boolean] =
    ZIO.serviceWithZIO[GeminiService](_.isAvailable)

  /** Backward-compatible live layer, delegated to GeminiCliAIService. */
  val live: ZLayer[MigrationConfig & RateLimiter, Nothing, GeminiService] =
    (
      ZLayer.fromZIO(ZIO.serviceWith[MigrationConfig](_.resolvedProviderConfig)) ++
        ZLayer.service[RateLimiter]
    ) >>> GeminiCliAIService.layer >>> bridgeFromAIService

  private[core] val bridgeFromAIService: ZLayer[AIService, Nothing, GeminiService] =
    ZLayer.fromFunction((aiService: AIService) =>
      new GeminiService {
        override def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
          aiService.execute(prompt).map(toGeminiResponse).mapError(mapAIErrorToGeminiError)

        override def executeWithContextLegacy(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
          aiService.executeWithContext(prompt, context).map(toGeminiResponse).mapError(mapAIErrorToGeminiError)

        override def isAvailable: ZIO[Any, Nothing, Boolean] =
          aiService.isAvailable
      }
    )

  private def toGeminiResponse(response: AIResponse): GeminiResponse =
    GeminiResponse(
      output = response.output,
      exitCode = response.metadata.get("exitCode").flatMap(_.toIntOption).getOrElse(0),
    )

  private[core] def mapAIErrorToGeminiError(error: AIError): GeminiError = error match
    case AIError.ProcessStartFailed(cause)            => GeminiError.ProcessStartFailed(cause)
    case AIError.OutputReadFailed(cause)              => GeminiError.OutputReadFailed(cause)
    case AIError.Timeout(duration)                    => GeminiError.Timeout(duration)
    case AIError.NonZeroExit(code, output)            => GeminiError.NonZeroExit(code, output)
    case AIError.ProcessFailed(cause)                 => GeminiError.ProcessFailed(cause)
    case AIError.NotAvailable(_)                      => GeminiError.NotInstalled
    case AIError.InvalidResponse(output)              => GeminiError.InvalidResponse(output)
    case AIError.RateLimitExceeded(timeout)           => GeminiError.RateLimitExceeded(timeout)
    case AIError.RateLimitMisconfigured(details)      => GeminiError.RateLimitMisconfigured(details)
    case AIError.HttpError(statusCode, body)          => GeminiError.NonZeroExit(statusCode, body)
    case AIError.AuthenticationFailed(provider)       => GeminiError.ProcessFailed(s"Authentication failed for $provider")
    case AIError.ProviderUnavailable(provider, cause) => GeminiError.ProcessFailed(s"$provider unavailable: $cause")

  private[core] def mapGeminiErrorToAIError(error: GeminiError): AIError = error match
    case GeminiError.ProcessStartFailed(cause)       => AIError.ProcessStartFailed(cause)
    case GeminiError.OutputReadFailed(cause)         => AIError.OutputReadFailed(cause)
    case GeminiError.Timeout(duration)               => AIError.Timeout(duration)
    case GeminiError.NonZeroExit(code, output)       => AIError.NonZeroExit(code, output)
    case GeminiError.ProcessFailed(cause)            => AIError.ProcessFailed(cause)
    case GeminiError.NotInstalled                    => AIError.NotAvailable("gemini-cli")
    case GeminiError.InvalidResponse(output)         => AIError.InvalidResponse(output)
    case GeminiError.RateLimitExceeded(timeout)      => AIError.RateLimitExceeded(timeout)
    case GeminiError.RateLimitMisconfigured(details) => AIError.RateLimitMisconfigured(details)
