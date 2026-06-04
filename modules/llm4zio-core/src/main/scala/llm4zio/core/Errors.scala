package llm4zio.core

import java.time.Instant

import zio.*

/** Typed, pure-ADT errors for the core LLM layer.
  *
  * Not a `Throwable`: domain errors travel ZIO's typed error channel (`IO[LlmError, A]`), never the exception
  * mechanism. When a failure originates from an underlying JVM exception, that exception is carried in `cause` — the
  * boundary where a `Throwable` is wrapped into a domain value, not inherited by it. Every case renders a stable,
  * non-empty [[message]] for logging and surfacing.
  */
sealed trait LlmError:
  /** Human-readable description for logging and rendering to the user. */
  def message: String

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None)              extends LlmError
  case class UsageLimitError(resetAt: Option[Instant], provider: String, message: String) extends LlmError
  case class AuthenticationError(message: String)                                         extends LlmError
  case class InvalidRequestError(message: String)                                         extends LlmError
  case class ParseError(message: String, raw: String)                                     extends LlmError
  case class ToolError(toolName: String, detail: String)                                  extends LlmError:
    def message: String = s"tool '$toolName' failed: $detail"
  case class ConfigError(message: String)                                                 extends LlmError
  case class RateLimitError(retryAfter: Option[Duration] = None)                          extends LlmError:
    def message: String = retryAfter.fold("rate limited")(d => s"rate limited; retry after $d")
  case class TimeoutError(duration: Duration)                                             extends LlmError:
    def message: String = s"timed out after $duration"
  case class TurnLimitError(limit: Option[Int] = None)                                    extends LlmError:
    def message: String = limit.fold("turn limit reached")(n => s"turn limit reached after $n turn(s)")
