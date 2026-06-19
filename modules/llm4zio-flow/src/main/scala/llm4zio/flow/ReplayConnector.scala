package llm4zio.flow

import zio.*
import zio.json.*
import zio.stream.{ Stream, ZStream }

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** An [[LlmService]] that replays recorded [[ReplayTurn]]s in order: each `executeStream` subscription consumes the
  * next turn (so a [[TransientRetry]] re-subscription advances, reproducing fail→retry→succeed). Built from a trace via
  * [[Replay.fromTrace]]. Pure and in-memory — no network — so a real incident becomes a deterministic test.
  */
final class ReplayConnector(turns: List[ReplayTurn], cursor: Ref[Int]) extends LlmService:

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    ZStream.unwrap(
      cursor.getAndUpdate(_ + 1).map { i =>
        turns.lift(i) match
          case Some(ReplayTurn.Success(text, usage, model)) =>
            ZStream.succeed(
              LlmChunk(
                delta = text,
                finishReason = Some("stop"),
                usage = usage,
                metadata = Map("provider" -> "replay") ++ model.map("model" -> _),
              )
            )
          case Some(ReplayTurn.Failure(message, _))         =>
            ZStream.fail(LlmError.ProviderError(message, None))
          case None                                         =>
            ZStream.fail(LlmError.ProviderError(s"replay trace exhausted at turn $i", None))
      }
    )

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    executeStream("")

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    ZIO.fail(LlmError.InvalidRequestError("replay does not support tool calling"))

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    Streaming.collect(executeStream(prompt)).flatMap { resp =>
      ZIO
        .fromEither(resp.content.fromJson[A])
        .mapError(err => LlmError.ParseError(s"replay structured parse error: $err", resp.content))
    }

  override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

object ReplayConnector:
  def make(turns: List[ReplayTurn]): UIO[ReplayConnector] =
    Ref.make(0).map(new ReplayConnector(turns, _))
