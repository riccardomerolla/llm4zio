package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object TransientRetrySpec extends ZIOSpecDefault:

  /** executeStream consults a per-call attempt counter: the first `failTimes` attempts fail with `err`, then it
    * succeeds emitting one "ok" chunk.
    */
  final class FlakyService(attempts: Ref[Int], failTimes: Int, err: LlmError) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.unwrap(attempts.updateAndGet(_ + 1).map { n =>
        if n <= failTimes then ZStream.fail(err) else ZStream.succeed(LlmChunk(delta = "ok"))
      })
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = executeStream("")
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    override def executeStructuredWithUsage[A: JsonCodec](
      prompt: String,
      schema: JsonSchema,
    ): IO[LlmError, (A, Option[TokenUsage], Option[String])] = ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  // A stream that fails `failTimes` times with `failWith`, then emits a single "ok" chunk. Counts attempts in `calls`.
  final class CountingStream(calls: Ref[Int], failWith: LlmError, failTimes: Int) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.unwrap(calls.updateAndGet(_ + 1).map { n =>
        if n <= failTimes then ZStream.fail(failWith)
        else ZStream.succeed(LlmChunk(delta = "ok"))
      })
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = executeStream("")
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TransientRetry")(
    suite("isTransient")(
      test("classifies timeouts, rate limits, and 5xx/connection-reset provider errors as transient") {
        assertTrue(
          TransientRetry.isTransient(LlmError.TimeoutError(1.second)),
          TransientRetry.isTransient(LlmError.RateLimitError()),
          TransientRetry.isTransient(LlmError.ProviderError("Gemini CLI stream error: connection reset by peer")),
          TransientRetry.isTransient(LlmError.ProviderError("api error code=503 type=server_error")),
          // gemini's catch-all glitch, now retryable
          TransientRetry.isTransient(
            LlmError.ProviderError("Gemini CLI returned an error: [API Error: An unknown error occurred.]")
          ),
        )
      },
      test("does NOT retry bad requests, parse errors, config errors, or usage caps") {
        assertTrue(
          !TransientRetry.isTransient(LlmError.InvalidRequestError("bad")),
          !TransientRetry.isTransient(LlmError.ParseError("nope", "raw")),
          !TransientRetry.isTransient(LlmError.ConfigError("missing key")),
          !TransientRetry.isTransient(LlmError.UsageLimitError(None, "gemini", "cap")),
          !TransientRetry.isTransient(LlmError.ProviderError("you asked for a nonexistent model")),
        )
      },
    ),
    suite("transientDelay")(
      test("honors a provider-named retryAfter longer than the backoff (gemini 'reset after 45s')") {
        assertTrue(
          TransientRetry.transientDelay(LlmError.RateLimitError(Some(45.seconds)), 1.second) == 45.seconds
        )
      },
      test("keeps the backoff when it is already longer than retryAfter") {
        assertTrue(TransientRetry.transientDelay(LlmError.RateLimitError(Some(1.second)), 4.seconds) == 4.seconds)
      },
      test("caps a pathological retryAfter at two minutes") {
        assertTrue(TransientRetry.transientDelay(LlmError.RateLimitError(Some(21.hours)), 1.second) == 2.minutes)
      },
      test("non-rate-limit transients use the plain backoff") {
        assertTrue(
          TransientRetry.transientDelay(LlmError.ProviderError("connection reset", None), 3.seconds) == 3.seconds,
          TransientRetry.transientDelay(LlmError.RateLimitError(None), 3.seconds) == 3.seconds,
        )
      },
    ),
    test("a usage-limit failure fails straight through — no transient or flaky retries, no notices") {
      // A quota-exhausted provider (e.g. gemini "quota will reset after 21h") cannot be retried back to life;
      // the typed UsageLimitError must reach the wait layers (LLM4ZIO_USAGE_WAIT) or the user immediately.
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        attempts        <- Ref.make(0)
        cap              = LlmError.UsageLimitError(None, "gemini", "You have exhausted your capacity on this model.")
        svc              = FlakyService(attempts, failTimes = 99, cap)
        retry            = TransientRetry(svc, maxRetries = 3, baseDelay = Duration.Zero)
        result          <- retry.executeStream("hi").runCollect.exit
        tries           <- attempts.get
        infos           <- events.recorded
      yield assertTrue(result.isFailure, tries == 1, infos.isEmpty)
    },
    test("retries a transient stream failure then succeeds, emitting a visible retry notice each time") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        attempts        <- Ref.make(0)
        svc              = FlakyService(attempts, failTimes = 2, LlmError.ProviderError("connection reset"))
        retry            = TransientRetry(svc, maxRetries = 2, baseDelay = Duration.Zero)
        out             <- retry.executeStream("hi").runCollect
        tries           <- attempts.get
        infos           <- events.recorded
      yield assertTrue(
        out.map(_.delta).mkString == "ok",
        tries == 3, // 2 transient failures + 1 success
        infos.collect { case FlowEvent.Info(m) if m.contains("transient error") => m }.size == 2,
      )
    },
    test("gives up after maxRetries on a persistent transient failure") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        attempts        <- Ref.make(0)
        svc              = FlakyService(attempts, failTimes = 99, LlmError.ProviderError("service unavailable"))
        retry            = TransientRetry(svc, maxRetries = 2, baseDelay = Duration.Zero)
        result          <- retry.executeStream("hi").runCollect.exit
        tries           <- attempts.get
      yield assertTrue(result.isFailure, tries == 3) // initial + 2 retries
    },
    test("maxRetries = 0 fails fast on a transient error (one attempt, no retry notice)") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        attempts        <- Ref.make(0)
        svc              = FlakyService(attempts, failTimes = 99, LlmError.ProviderError("connection reset"))
        retry            = TransientRetry(svc, maxRetries = 0, baseDelay = Duration.Zero)
        result          <- retry.executeStream("hi").runCollect.exit
        tries           <- attempts.get
        infos           <- events.recorded
      yield assertTrue(result.isFailure, tries == 1, infos.isEmpty)
    },
    test("does not retry a non-transient stream failure") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        attempts        <- Ref.make(0)
        svc              = FlakyService(attempts, failTimes = 1, LlmError.InvalidRequestError("bad request"))
        retry            = TransientRetry(svc, maxRetries = 2, baseDelay = Duration.Zero)
        result          <- retry.executeStream("hi").runCollect.exit
        tries           <- attempts.get
        infos           <- events.recorded
      yield assertTrue(result.isFailure, tries == 1, infos.isEmpty)
    },
    test("isFlakyStream matches empty-stream signals; isTransient no longer does") {
      val flaky = LlmError.ProviderError("Gemini CLI stream error: Invalid stream: empty response", None)
      assertTrue(
        TransientRetry.isFlakyStream(flaky),
        !TransientRetry.isTransient(flaky),
        !TransientRetry.isFlakyStream(LlmError.ProviderError("connection reset", None)),
        TransientRetry.isTransient(LlmError.ProviderError("connection reset", None)),
      )
    },
    test("an empty structured response is classified flaky — structured judge calls get fresh-process retries") {
      // StructuredOutputs.parseFromText fails a BLANK response with this ProviderError (not ParseError) precisely so
      // the flaky budget picks it up: gemini intermittently returns an envelope with empty text, and a fresh CLI
      // process almost always recovers. A real parse mismatch (non-empty text, wrong shape) stays non-retriable.
      val emptyStructured =
        LlmError.ProviderError("empty response from provider — no text to parse as structured output", None)
      assertTrue(
        TransientRetry.isFlakyStream(emptyStructured),
        !TransientRetry.isTransient(emptyStructured),
        !TransientRetry.isFlakyStream(LlmError.ParseError("wrong shape", """{"a":1}""")),
      )
    },
    test("a flaky stream is retried on its own budget, independent of maxRetries") {
      // maxRetries = 0 (transient budget exhausted) but flakyRetries = 2 => two flaky retries still happen.
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        calls           <- Ref.make(0)
        svc              = new TransientRetrySpec.CountingStream(
                             calls,
                             failWith = LlmError.ProviderError(
                               "Gemini CLI stream error: Invalid stream: empty response",
                               None,
                             ),
                             failTimes = 2,
                           )
        rt               = TransientRetry(svc, maxRetries = 0, flakyRetries = 2, flakyDelay = zio.Duration.Zero)
        out             <- rt.executeStream("p").runCollect
        n               <- calls.get
      yield assertTrue(out.map(_.delta).mkString == "ok", n == 3) // 2 failures + 1 success
    },
    test("a flaky stream past its budget fails") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        calls           <- Ref.make(0)
        svc              = new TransientRetrySpec.CountingStream(
                             calls,
                             failWith = LlmError.ProviderError("Invalid stream: empty response", None),
                             failTimes = 5,
                           )
        rt               = TransientRetry(svc, maxRetries = 0, flakyRetries = 2, flakyDelay = zio.Duration.Zero)
        exit            <- rt.executeStream("p").runCollect.exit
      yield assertTrue(exit.isFailure)
    },
    test("a 400 INVALID_ARGUMENT token-count error is not transient and is a context overflow") {
      val msg = """Gemini CLI returned an error: [API Error: [{
                  |  "error": {
                  |    "code": 400,
                  |    "message": "The input token count exceeds the maximum number of tokens allowed 1048576.",
                  |    "status": "INVALID_ARGUMENT"
                  |  }
                  |}]]""".stripMargin
      val err = LlmError.ProviderError(msg, None)
      assertTrue(
        !TransientRetry.isTransient(err),
        TransientRetry.isContextOverflow(err),
      )
    },
    test("genuine transients are still transient and are not context overflows") {
      val unknown = LlmError.ProviderError("[API Error: An unknown error occurred.]", None)
      val unavail = LlmError.ProviderError("503 service unavailable", None)
      val reset   = LlmError.ProviderError("connection reset by peer", None)
      assertTrue(
        TransientRetry.isTransient(unknown),
        TransientRetry.isTransient(unavail),
        TransientRetry.isTransient(reset),
        !TransientRetry.isContextOverflow(unknown),
        !TransientRetry.isContextOverflow(unavail),
      )
    },
  )
