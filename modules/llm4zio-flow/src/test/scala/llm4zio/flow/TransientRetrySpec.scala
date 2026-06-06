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

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TransientRetry")(
    suite("isTransient")(
      test("classifies timeouts, rate limits, and 5xx/connection-reset provider errors as transient") {
        assertTrue(
          TransientRetry.isTransient(LlmError.TimeoutError(1.second)),
          TransientRetry.isTransient(LlmError.RateLimitError()),
          TransientRetry.isTransient(LlmError.ProviderError("Gemini CLI stream error: connection reset by peer")),
          TransientRetry.isTransient(LlmError.ProviderError("api error code=503 type=server_error")),
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
  )
