package llm4zio.modernize

import java.nio.file.{ Files, Path }

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

  /** Records the FULL message list each call receives (not just its size), so a test can check content — e.g. that
    * every task sees the same fully-composed system prompt, not a narrowed one.
    */
  final class RecordingHistory(seen: Ref[List[List[Message]]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.succeed(LlmChunk(delta = "done"))
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      ZStream.unwrap(seen.update(_ :+ messages).as(ZStream.succeed(LlmChunk(delta = "done"))))
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-chat-per-task-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

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
    // Pins the SHAPE ImplementFlow relies on (implementTaskLoop, one Chat.start per task), not ImplementFlow
    // itself — ImplementFlow.run needs a FlowContext, a real git repo, and a loaded Pack, which is impractical to
    // wire in a unit test. This drives the same `implementTaskLoop(planPath, plan) { task => Chat.start(...) }`
    // production shape over a real (temp-dir) PlanStore, across two tasks, against a stub LlmService.
    test(
      "implementTaskLoop-shaped: two tasks each get a fresh chat — bounded requests, same full system prompt"
    ) {
      ZIO.scoped {
        for
          dir     <- tempDir
          path     = dir.resolve("plan.md")
          plan     = Plan("epic", List(Task("t1", "implement the first slice"), Task("t2", "implement the second slice")))
          seen    <- Ref.make(List.empty[List[Message]])
          svc      = RecordingHistory(seen)
          system   = "pack brief\n\nlessons from previous runs\n\npattern cards cited by the specs: PATTERN-1, PATTERN-2"
          _       <- implementTaskLoop(path, plan) { task =>
                       Chat.start(svc, system = Some(system)).flatMap(_.ask(plan.taskPrompt(task))).unit
                     }(using FlowEvents.noop)
          history <- seen.get
          systems  = history.map(_.head.content)
        yield assertTrue(
          history.size == 2,
          history.map(_.size) == List(2, 2), // task 2's request is exactly as small as task 1's — no growth
          systems.distinct.size == 1,        // both tasks got the identical, fully-composed system prompt
          systems.forall(_.contains(system)), // and it's the FULL one (pack brief + lessons + cards), not narrowed
        )
      }
    },
  )
