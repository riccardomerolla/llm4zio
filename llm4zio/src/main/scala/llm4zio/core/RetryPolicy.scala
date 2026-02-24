package llm4zio.core

import zio.*

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

  /** Determine if an LlmError is retryable
    *
    * Retryable errors:
    *   - TimeoutError (transient network issues)
    *   - RateLimitError (rate limiting)
    *   - ProviderError (might be transient)
    *
    * Non-retryable errors:
    *   - AuthenticationError (permanent - invalid credentials)
    *   - InvalidRequestError (permanent - bad request)
    *   - ParseError (permanent - cannot parse response)
    *   - ToolError (permanent - tool execution failed)
    *   - ConfigError (permanent - invalid configuration)
    */
  def isRetryable(error: LlmError): Boolean = error match
    case LlmError.TimeoutError(_)     => true
    case LlmError.RateLimitError(_)   => true
    case LlmError.ProviderError(_, _) => true // Might be transient
    case _                            => false
