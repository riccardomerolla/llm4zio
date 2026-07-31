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
  *
  * Two independent retry budgets:
  *   - ''Transient'' (connection resets, 5xx, rate limits): exponential backoff, bounded by `maxRetries`.
  *   - ''Flaky-stream'' (empty response / malformed tool call / invalid stream): short fixed backoff, bounded by
  *     `flakyRetries`. A fresh CLI process is cheap and almost always recovers, so this class gets a more generous
  *     budget without burning the transient budget.
  */
final class TransientRetry(
  underlying: LlmService,
  maxRetries: Int = 3,
  baseDelay: Duration = 1.second,
  flakyRetries: Int = 6,
  flakyDelay: Duration = 1.second,
)(using events: FlowEvents
) extends LlmService:

  private def backoff(attempt: Int): Duration = baseDelay * math.pow(2, attempt.toDouble)

  private def notice(what: String, attempt: Int, max: Int, e: LlmError): UIO[Unit] =
    events.publish(FlowEvent.Info(s"⟳ $what — retry ${attempt + 1}/$max: ${e.message}"))

  private def retryIO[A](what: String)(io: IO[LlmError, A]): IO[LlmError, A] =
    def loop(tN: Int, fN: Int): IO[LlmError, A] =
      io.catchSome {
        case e if TransientRetry.isFlakyStream(e) && fN < flakyRetries =>
          notice(s"flaky $what (fresh retry)", fN, flakyRetries, e) *> ZIO.sleep(flakyDelay) *> loop(tN, fN + 1)
        case e if TransientRetry.isTransient(e) && tN < maxRetries     =>
          notice(s"transient error ($what)", tN, maxRetries, e) *>
            ZIO.sleep(TransientRetry.transientDelay(e, backoff(tN))) *> loop(tN + 1, fN)
      }
    loop(0, 0)

  private def retryStream(what: String)(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, LlmChunk] =
    def loop(tN: Int, fN: Int): Stream[LlmError, LlmChunk] =
      stream.catchSome {
        case e if TransientRetry.isFlakyStream(e) && fN < flakyRetries =>
          ZStream.fromZIO(notice(s"flaky $what (fresh retry)", fN, flakyRetries, e) *> ZIO.sleep(flakyDelay)).drain ++
            loop(tN, fN + 1)
        case e if TransientRetry.isTransient(e) && tN < maxRetries     =>
          ZStream.fromZIO(
            notice(s"transient error ($what)", tN, maxRetries, e) *>
              ZIO.sleep(TransientRetry.transientDelay(e, backoff(tN)))
          ).drain ++
            loop(tN + 1, fN)
      }
    loop(0, 0)

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

  /** Longest provider-named `retryAfter` honored before a transient retry. Longer waits are usage-cap territory —
    * classified as [[LlmError.UsageLimitError]] and handled by the wait layers — so the cap only guards against a
    * pathological header stalling the flow.
    */
  private val maxHonoredRetryAfter: Duration = 2.minutes

  /** The wait before a transient retry: the exponential backoff, unless the provider named a longer `retryAfter` (e.g.
    * gemini "quota will reset after 45s") — sleeping 1s+2s+4s against a 45s window burns the whole retry budget on
    * failures that were never going to succeed.
    */
  def transientDelay(e: LlmError, backoff: Duration): Duration = e match
    case LlmError.RateLimitError(Some(retryAfter)) =>
      val honored = if retryAfter.compareTo(maxHonoredRetryAfter) > 0 then maxHonoredRetryAfter else retryAfter
      if honored.compareTo(backoff) > 0 then honored else backoff
    case _                                         => backoff

  /** Flaky-stream class: gemini intermittently closes the stream with no candidates or a half-formed function call.
    * Non-deterministic; a fresh process (which a retried stream spawns) almost always succeeds, so this gets its own,
    * more generous budget with a short fixed backoff — distinct from rate-limit-flavoured transients.
    *
    * NB an empty response is AMBIGUOUS: gemini returns one both for a random mid-stream flake and for a prompt too
    * large to start on. This classifier sees only the error message, never the prompt size, so it cannot tell the two
    * apart — and deliberately treats both as flaky, since a fresh process fixes the common (flake) case. The
    * oversized-prompt case is resolved a layer up, by `Context.withShrink`, which retries at a smaller budget rather
    * than an identical one. Do not "fix" this by routing empty responses into [[isContextOverflow]]: that would trade a
    * frequent real recovery (the flake) for a rarer one (the overflow), breaking the common case.
    */
  def isFlakyStream(e: LlmError): Boolean = e match
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List("empty response", "malformed tool call", "invalid stream").exists(m.contains)
    case _                                  => false

  /** Deterministic client errors that a retry can never fix. Gemini wraps EVERY error in `[API Error: {...}]`,
    * including 400s, so the `"api error"` substring in [[isTransient]] would otherwise swallow them.
    */
  private def isDeterministic4xx(message: String): Boolean =
    val m = message.toLowerCase
    List(
      "invalid_argument",
      "\"code\": 400",
      "\"code\":400",
      "code=400",
      "exceeds the maximum number of tokens",
    ).exists(m.contains)

  /** The prompt was larger than the model's input window. Deterministic: the same prompt always fails, so it is NOT
    * transient — it routes to [[Context.withShrink]], which retries at a smaller budget.
    */
  def isContextOverflow(e: LlmError): Boolean = e match
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List(
        "exceeds the maximum number of tokens",
        "input token count exceeds",
        "context length exceeded",
        "maximum context length",
        "prompt is too long",
        "request too large",
      ).exists(m.contains)
    case _                                  => false

  /** Transient = worth retrying: timeouts, short rate limits, and provider errors whose message points at a server-side
    * / network blip. Deliberately conservative so genuine failures (bad request, parse, config, usage cap) are NOT
    * masked. Flaky-stream signals ([[isFlakyStream]]) are intentionally excluded here — they have their own budget.
    */
  def isTransient(e: LlmError): Boolean = e match
    case _: LlmError.TimeoutError                                           => true
    case _: LlmError.RateLimitError                                         => true
    case LlmError.ProviderError(message, _) if !isDeterministic4xx(message) =>
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
        // Gemini's catch-all server-side glitch, surfaced as "[API Error: An unknown error occurred.]" — flaky, worth
        // retrying (bounded). Tune the count with LLM4ZIO_RETRIES (0 = fail fast).
        "api error",
        "unknown error",
        "code=500",
        "code=502",
        "code=503",
        "code=504",
        " 500",
        " 502",
        " 503",
        " 504",
      ).exists(m.contains)
    case _                                                                  => false
