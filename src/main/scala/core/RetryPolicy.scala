package core

import zio.*

import models.{ AIError, GeminiError }

/** Retry policy configuration for handling transient failures
  *
  * @param maxRetries
  *   Maximum number of retry attempts
  * @param baseDelay
  *   Base delay for exponential backoff
  * @param maxDelay
  *   Maximum delay between retries
  * @param jitterFactor
  *   Jitter factor to avoid thundering herd (0.0 to 1.0)
  */
case class RetryPolicy(
  maxRetries: Int,
  baseDelay: Duration,
  maxDelay: Duration,
  jitterFactor: Double = 0.1,
)

object RetryPolicy:
  /** Default retry policy with reasonable defaults */
  val default: RetryPolicy = RetryPolicy(
    maxRetries = 3,
    baseDelay = Duration.fromSeconds(1),
    maxDelay = Duration.fromSeconds(30),
    jitterFactor = 0.1,
  )

  /** Execute an effect with retry logic based on the policy
    *
    * @param effect
    *   The effect to execute
    * @param policy
    *   Retry policy configuration
    * @param isRetryable
    *   Function to determine if an error is retryable
    * @return
    *   ZIO effect with retry logic applied
    */
  def withRetry[R, E, A](
    effect: ZIO[R, E, A],
    policy: RetryPolicy,
    isRetryable: E => Boolean,
  ): ZIO[R, E, A] =
    val schedule = (
      Schedule.exponential(policy.baseDelay)
        .jittered(policy.jitterFactor, 1.0 + policy.jitterFactor)
        .upTo(policy.maxDelay)
        && Schedule.recurs(policy.maxRetries)
        && Schedule.recurWhile[E](isRetryable)
    ).onDecision((_, _, decision) =>
      decision match
        case Schedule.Decision.Done        =>
          ZIO.logDebug("Retry schedule exhausted")
        case Schedule.Decision.Continue(_) =>
          ZIO.logInfo("Retrying after transient failure")
    )

    effect
      .tapError(err => ZIO.logWarning(s"Retryable error occurred: $err"))
      .retry(schedule)

  /** Determine if a GeminiError is retryable
    *
    * Retryable errors:
    *   - Timeout (transient network issues)
    *   - NonZeroExit with code 429 (rate limited)
    *   - NonZeroExit with code >= 500 (server errors)
    *
    * Non-retryable errors:
    *   - ProcessStartFailed (system issue)
    *   - NotInstalled (missing binary)
    *   - InvalidResponse (permanent failure)
    *   - OutputReadFailed (permanent failure)
    *   - NonZeroExit with 4xx codes except 429 (client errors)
    */
  def isRetryable(error: GeminiError): Boolean = error match
    case GeminiError.Timeout(_)                          => true
    case GeminiError.NonZeroExit(code, _) if code == 429 => true // Rate limited
    case GeminiError.NonZeroExit(code, _) if code >= 500 => true // Server error
    case GeminiError.ProcessFailed(_)                    => true // Might be transient
    case _                                               => false

  /** Determine if an AIError is retryable.
    */
  def isRetryableAI(error: AIError): Boolean = error match
    case AIError.Timeout(_)                          => true
    case AIError.NonZeroExit(code, _) if code == 429 => true
    case AIError.NonZeroExit(code, _) if code >= 500 => true
    case AIError.ProcessFailed(_)                    => true
    case AIError.HttpError(code, _) if code == 429   => true
    case AIError.HttpError(code, _) if code >= 500   => true
    case AIError.RateLimitExceeded(_)                => true
    case AIError.ProviderUnavailable(_, _)           => true
    case _                                           => false
