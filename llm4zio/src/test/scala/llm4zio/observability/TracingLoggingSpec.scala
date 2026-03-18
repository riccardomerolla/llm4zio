package llm4zio.observability

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object TracingLoggingSpec extends ZIOSpecDefault:

  final private class SuccessService extends LlmService:
    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.succeed(LlmChunk(
        "ok",
        finishReason = Some("stop"),
        usage = Some(TokenUsage(10, 5, 15)),
        metadata = Map("provider" -> "openai", "model" -> "gpt-4o"),
      ))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.succeed(LlmChunk("ok", finishReason = Some("stop"), usage = Some(TokenUsage(10, 5, 15))))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(content = Some("ok"), toolCalls = Nil, finishReason = "stop"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not used"))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  final private class FailingService extends LlmService:
    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(ZIO.fail(LlmError.TimeoutError(2.seconds)))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(ZIO.fail(LlmError.TimeoutError(2.seconds)))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.TimeoutError(2.seconds))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.TimeoutError(2.seconds))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(false)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TracingLogging")(
    test("propagates correlation id and records spans") {
      ZIO.scoped {
        for
          tracing <- TracingService.inMemory
          _       <- tracing.withCorrelationId(Some("corr-test")) {
                       tracing.inSpan("outer") {
                         tracing.inSpan("inner")(ZIO.succeed("done"))
                       }
                     }
          spans   <- tracing.recordedSpans
        yield assertTrue(
          spans.length == 2,
          spans.forall(_.correlationId == "corr-test"),
          spans.exists(_.parentSpanId.nonEmpty),
        )
      }
    },
    test("logs and metrics include same correlation id for success path") {
      ZIO.scoped {
        for
          tracing         <- TracingService.inMemory
          metrics         <- MetricsCollector.inMemory()
          sinkPair        <- StructuredLogSink.inMemory
          (sink, readLogs) = sinkPair
          observed         = ProductionLogging.observed(new SuccessService, tracing, metrics, sink)
          _               <- Streaming.collect(observed.executeStream("SELECT CUSTOMER"))
          logs            <- readLogs
          spans           <- tracing.recordedSpans
          snapshot        <- metrics.snapshot
          correlationIds   = logs.map(_.correlationId).toSet
        yield assertTrue(
          logs.nonEmpty,
          spans.nonEmpty,
          correlationIds.size == 1,
          spans.forall(span => correlationIds.contains(span.correlationId)),
          snapshot.totalRequests == 1,
          snapshot.totalErrors == 0,
        )
      }
    },
    test("logs errors and marks failures in metrics") {
      ZIO.scoped {
        for
          tracing         <- TracingService.inMemory
          metrics         <- MetricsCollector.inMemory()
          sinkPair        <- StructuredLogSink.inMemory
          (sink, readLogs) = sinkPair
          observed         = ProductionLogging.observed(new FailingService, tracing, metrics, sink)
          _               <- Streaming.collect(observed.executeStream("FAIL")).either
          logs            <- readLogs
          snapshot        <- metrics.snapshot
        yield assertTrue(
          logs.exists(_.level == StructuredLogLevel.Error),
          snapshot.totalRequests == 1,
          snapshot.totalErrors == 1,
        )
      }
    },
  )
