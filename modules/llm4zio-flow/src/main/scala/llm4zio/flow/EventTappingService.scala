package llm4zio.flow

import java.nio.file.Path

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Wraps an [[LlmService]] and republishes its streaming activity as [[FlowEvent]]s on `events`, tagged with `agent`.
  * Tool calls and token usage are read from the metadata contract every provider normalizes to; assistant prose is
  * buffered and flushed as a single [[FlowEvent.AssistantMessage]] at stream end.
  *
  * Non-streaming methods (`executeStructured`, `executeWithTools`) delegate untapped — they expose no usage or tool
  * events.
  */
final class EventTappingService(
  underlying: LlmService,
  agent: String,
  events: FlowEvents,
  workDir: Path,
) extends LlmService:

  private def tap(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, LlmChunk] =
    ZStream.unwrap {
      Ref.make(new StringBuilder()).map { buf =>
        stream
          .tap(chunk => onChunk(chunk, buf))
          .ensuring(flush(buf))
      }
    }

  private def onChunk(chunk: LlmChunk, buf: Ref[StringBuilder]): UIO[Unit] =
    val toolEvent  =
      if chunk.metadata.get("event").contains("tool_use") then
        events.publish(
          FlowEvent.ToolUse(
            chunk.metadata.getOrElse("tool_name", chunk.metadata.getOrElse("toolName", "")),
            ToolInputSummary.summarise(
              chunk.metadata.getOrElse("tool_input", chunk.metadata.getOrElse("toolInput", "")),
              120,
              workDir,
            ),
          )
        )
      else ZIO.unit
    val textEvent  = if chunk.delta.nonEmpty then buf.update(b => b.append(chunk.delta)) else ZIO.unit
    val usageEvent = chunk.usage match
      case Some(u) => events.publish(FlowEvent.TokensUsed(agent, chunk.metadata.get("model"), u))
      case None    => ZIO.unit
    toolEvent *> textEvent *> usageEvent

  private def flush(buf: Ref[StringBuilder]): UIO[Unit] =
    buf.get.flatMap { sb =>
      val text = sb.toString.trim
      ZIO.unless(text.isEmpty)(events.publish(FlowEvent.AssistantMessage(text))).unit
    }

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    tap(underlying.executeStream(prompt))

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    tap(underlying.executeStreamWithHistory(messages))

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    underlying.executeWithTools(prompt, tools)

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    underlying.executeStructured(prompt, schema)

  override def isAvailable: UIO[Boolean] = underlying.isAvailable
