package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.Stream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Decorates an [[LlmService]] so its idempotent IO calls (structured output, tool calling) wait out a provider
  * usage/credit cap instead of failing — when `policy.enabled`. Streaming calls and `isAvailable` pass through
  * unchanged (the streaming coder is handled by [[withUsageLimitRetry]]).
  */
final class UsageLimitAware(underlying: LlmService, policy: UsageLimitPolicy)(using events: FlowEvents)
  extends LlmService:

  private val buffer = 30.seconds

  private def patient[A](io: IO[LlmError, A]): IO[LlmError, A] =
    def loop(waited: Duration): IO[LlmError, A] =
      io.catchSome {
        case err: LlmError.UsageLimitError if policy.enabled =>
          Clock.instant.flatMap { now =>
            val sleepFor = err.resetAt match
              case Some(at) =>
                val remaining = Duration.fromInterval(now, at)
                (if remaining.isNegative then Duration.Zero else remaining) + buffer
              case None     => policy.pollInterval
            if waited + sleepFor > policy.maxWait then ZIO.fail(err)
            else
              events.publish(FlowEvent.Info(notice(err, sleepFor))) *>
                UsageLimitWait.sleep(err.provider, sleepFor, err.resetAt, policy.heartbeat) *>
                loop(waited + sleepFor)
          }
      }
    loop(Duration.Zero)

  private def notice(err: LlmError.UsageLimitError, sleepFor: Duration): String =
    val mins  = math.max(1, sleepFor.toMinutes)
    val until = err.resetAt.fold("")(at => s" until $at")
    s"⏳ usage limit (${err.provider}) — sleeping ${mins}m$until"

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk]              = underlying.executeStream(prompt)
  override def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk] =
    underlying.executeStreamWithHistory(m)
  override def isAvailable: UIO[Boolean]                                              = underlying.isAvailable

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    patient(underlying.executeWithTools(prompt, tools))

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    patient(underlying.executeStructured[A](prompt, schema))

  override def executeStructuredWithUsage[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
  ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
    patient(underlying.executeStructuredWithUsage[A](prompt, schema))
