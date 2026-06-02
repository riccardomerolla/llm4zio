package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ChatSpec extends ZIOSpecDefault:

  /** Records every history list it is asked to continue; always replies `reply`. */
  final class RecordingService(reply: String, calls: Ref[List[List[Message]]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      ZStream.fromZIO(calls.update(_ :+ messages)).as(LlmChunk(delta = reply, finishReason = Some("stop")))
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Chat")(
    test("ask threads accumulated history and records both turns") {
      for
        calls <- Ref.make(List.empty[List[Message]])
        chat  <- Chat.start(new RecordingService("ok", calls), manageGit = true)
        r1    <- chat.ask("first")
        r2    <- chat.ask("second")
        seen  <- calls.get
        msgs  <- chat.messages
      yield assertTrue(
        r1 == "ok",
        r2 == "ok",
        seen.size == 2,
        seen(1) == List(
          Message(MessageRole.User, "first"),
          Message(MessageRole.Assistant, "ok"),
          Message(MessageRole.User, "second"),
        ),
        msgs == List(
          Message(MessageRole.User, "first"),
          Message(MessageRole.Assistant, "ok"),
          Message(MessageRole.User, "second"),
          Message(MessageRole.Assistant, "ok"),
        ),
      )
    },
    test("start seeds an optional system prompt as the first message (manageGit=true: no git prefix)") {
      for
        calls <- Ref.make(List.empty[List[Message]])
        chat  <- Chat.start(new RecordingService("ok", calls), system = Some("be terse"), manageGit = true)
        _     <- chat.ask("hi")
        seen  <- calls.get
      yield assertTrue(seen.head.head == Message(MessageRole.System, "be terse"))
    },
  )
