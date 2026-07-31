package llm4zio.modernize

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.flow.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ChatPerTaskSpec extends ZIOSpecDefault:

  /** Records the size of the history each call receives. */
  final class Recording(seen: Ref[List[Int]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.succeed(LlmChunk(delta = "done"))
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      ZStream.unwrap(seen.update(_ :+ messages.size).as(ZStream.succeed(LlmChunk(delta = "done"))))
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("chat per task")(
    test("a fresh chat per task keeps every request the same size") {
      for
        seen <- Ref.make(List.empty[Int])
        svc   = Recording(seen)
        _    <- ZIO.foreachDiscard(List("task 1", "task 2", "task 3")) { t =>
                  Chat.start(svc, system = Some("sys")).flatMap(_.ask(t))
                }
        out  <- seen.get
      yield assertTrue(out == List(2, 2, 2)) // system + user, every time
    },
    test("one shared chat grows with every task — the behaviour being removed") {
      for
        seen <- Ref.make(List.empty[Int])
        svc   = Recording(seen)
        chat <- Chat.start(svc, system = Some("sys"))
        _    <- ZIO.foreachDiscard(List("task 1", "task 2", "task 3"))(chat.ask)
        out  <- seen.get
      yield assertTrue(out == List(2, 4, 6))
    },
  )
