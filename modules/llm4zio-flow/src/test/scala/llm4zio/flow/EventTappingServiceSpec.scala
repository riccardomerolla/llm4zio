package llm4zio.flow

import java.nio.file.Paths

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object EventTappingServiceSpec extends ZIOSpecDefault:

  /** A stub LlmService whose history stream yields a fixed chunk list. */
  private def stub(chunks: List[LlmChunk]): LlmService = new LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.fromIterable(chunks)
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.fromIterable(chunks)
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("EventTappingService")(
    test("publishes ToolUse, one AssistantMessage, and TokensUsed") {
      val chunks = List(
        LlmChunk(
          delta = "",
          metadata = Map("event" -> "tool_use", "tool_name" -> "Edit", "tool_input" -> """{"file_path":"src/lib.rs"}"""),
        ),
        LlmChunk(delta = "Hello "),
        LlmChunk(delta = "world"),
        LlmChunk(
          delta = "",
          finishReason = Some("stop"),
          usage = Some(TokenUsage(10, 5, 15)),
          metadata = Map("model" -> "gemini-2.5-flash"),
        ),
      )
      for
        sink <- FlowEvents.collecting
        svc   = EventTappingService(stub(chunks), agent = "coder", events = sink, workDir = Paths.get("/repo"))
        _    <- svc.executeStreamWithHistory(Nil).runDrain
        rec  <- sink.recorded
      yield assertTrue(
        rec.contains(FlowEvent.ToolUse("Edit", "(src/lib.rs)")),
        rec.contains(FlowEvent.AssistantMessage("Hello world")),
        rec.contains(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(10, 5, 15))),
        rec.count(_.isInstanceOf[FlowEvent.AssistantMessage]) == 1,
      )
    },
    test("emits no AssistantMessage when there is no assistant text") {
      val chunks = List(LlmChunk(delta = "", usage = Some(TokenUsage(1, 1, 2))))
      for
        sink <- FlowEvents.collecting
        svc   = EventTappingService(stub(chunks), "coder", sink, Paths.get("/repo"))
        _    <- svc.executeStream("hi").runDrain
        rec  <- sink.recorded
      yield assertTrue(!rec.exists(_.isInstanceOf[FlowEvent.AssistantMessage]))
    },
  )
