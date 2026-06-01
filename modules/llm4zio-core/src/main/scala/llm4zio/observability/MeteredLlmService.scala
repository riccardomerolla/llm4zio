package llm4zio.observability

import java.util.concurrent.TimeUnit

import zio.*
import zio.json.JsonCodec
import zio.stream.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Decorator that wraps any [[LlmService]] and records request count, token usage (from streaming chunks), latency, and
  * error count into [[LlmMetrics]].
  *
  * Token usage is tracked only for streaming calls, where [[LlmChunk.usage]] is available in the final chunk.
  * Non-streaming calls record request count, latency, and errors only.
  */
final case class MeteredLlmService(
  underlying: LlmService,
  metrics: LlmMetrics,
) extends LlmService:

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    timedStream(underlying.executeStream(prompt))

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    timedStream(underlying.executeStreamWithHistory(messages))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    timedEffect(underlying.executeWithTools(prompt, tools))

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    timedEffect(underlying.executeStructured(prompt, schema))

  override def isAvailable: UIO[Boolean] = underlying.isAvailable

  private def timedStream(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, LlmChunk] =
    ZStream.unwrap(
      for
        _     <- metrics.recordRequest
        start <- Clock.currentTime(TimeUnit.MILLISECONDS)
      yield stream
        .tap(chunk => ZIO.foreachDiscard(chunk.usage)(metrics.recordTokens))
        .tapError(_ => metrics.recordError)
        .ensuring(
          Clock.currentTime(TimeUnit.MILLISECONDS)
            .flatMap(end => metrics.recordLatency(end - start))
        )
    )

  private def timedEffect[A](effect: IO[LlmError, A]): IO[LlmError, A] =
    for
      _      <- metrics.recordRequest
      start  <- Clock.currentTime(TimeUnit.MILLISECONDS)
      result <- effect
                  .tapError(_ => metrics.recordError)
                  .ensuring(
                    Clock.currentTime(TimeUnit.MILLISECONDS)
                      .flatMap(end => metrics.recordLatency(end - start))
                  )
    yield result

object MeteredLlmService:
  val layer: ZLayer[LlmService & LlmMetrics, Nothing, LlmService] =
    ZLayer.fromFunction(MeteredLlmService.apply)
