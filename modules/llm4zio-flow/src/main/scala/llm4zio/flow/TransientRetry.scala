package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Wraps an [[LlmService]] and retries *transient* provider failures (connection resets, 5xx, short rate limits,
  * timeouts) a bounded number of times with exponential backoff. Non-transient errors — bad requests, parse errors,
  * config errors, usage caps — fail straight through (usage caps are handled by [[UsageLimitAware]] /
  * [[withUsageLimitRetry]]). Each retry publishes a visible [[FlowEvent.Info]] so the recovery is never silent.
  *
  * A retried stream restarts from scratch, so any chunks (e.g. tool-call events) from the failed attempt are re-emitted
  * — that duplication is intentional and surfaced by the retry notice.
  */
final class TransientRetry(
  underlying: LlmService,
  maxRetries: Int = 2,
  baseDelay: Duration = 1.second,
)(using events: FlowEvents
) extends LlmService:

  private def backoff(attempt: Int): Duration = baseDelay * math.pow(2, attempt.toDouble)

  private def notice(what: String, attempt: Int, e: LlmError): UIO[Unit] =
    events.publish(FlowEvent.Info(s"⟳ transient error ($what) — retry ${attempt + 1}/$maxRetries: ${e.message}"))

  private def retryIO[A](what: String)(io: IO[LlmError, A]): IO[LlmError, A] =
    def loop(attempt: Int): IO[LlmError, A] =
      io.catchSome {
        case e if TransientRetry.isTransient(e) && attempt < maxRetries =>
          notice(what, attempt, e) *> ZIO.sleep(backoff(attempt)) *> loop(attempt + 1)
      }
    loop(0)

  private def retryStream(what: String)(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, LlmChunk] =
    def loop(attempt: Int): Stream[LlmError, LlmChunk] =
      stream.catchSome {
        case e if TransientRetry.isTransient(e) && attempt < maxRetries =>
          ZStream.fromZIO(notice(what, attempt, e) *> ZIO.sleep(backoff(attempt))).drain ++ loop(attempt + 1)
      }
    loop(0)

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    retryStream("stream")(underlying.executeStream(prompt))

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    retryStream("stream")(underlying.executeStreamWithHistory(messages))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    retryIO("tools")(underlying.executeWithTools(prompt, tools))

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    retryIO("structured")(underlying.executeStructured[A](prompt, schema))

  override def executeStructuredWithUsage[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
  ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
    retryIO("structured")(underlying.executeStructuredWithUsage[A](prompt, schema))

  override def isAvailable: UIO[Boolean] = underlying.isAvailable

object TransientRetry:

  /** Transient = worth retrying: timeouts, short rate limits, and provider errors whose message points at a server-side
    * / network blip. Deliberately conservative so genuine failures (bad request, parse, config, usage cap) are NOT
    * masked.
    */
  def isTransient(e: LlmError): Boolean = e match
    case _: LlmError.TimeoutError           => true
    case _: LlmError.RateLimitError         => true
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List(
        "connection reset",
        "server_error",
        "internal error",
        "service unavailable",
        "unavailable",
        "overloaded",
        "temporarily",
        "timed out",
        "code=500",
        "code=502",
        "code=503",
        "code=504",
        " 500",
        " 502",
        " 503",
        " 504",
      ).exists(m.contains)
    case _                                  => false
