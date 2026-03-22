package llm4zio.core

import zio.*

sealed trait LlmError extends Throwable

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None) extends LlmError
  case class RateLimitError(retryAfter: Option[Duration] = None)             extends LlmError
  case class AuthenticationError(message: String)                            extends LlmError
  case class InvalidRequestError(message: String)                            extends LlmError
  case class TimeoutError(duration: Duration)                                extends LlmError
  case class ParseError(message: String, raw: String)                        extends LlmError
  case class ToolError(toolName: String, message: String)                    extends LlmError
  case class ConfigError(message: String)                                    extends LlmError
  case class TurnLimitError(limit: Option[Int] = None)                      extends LlmError
