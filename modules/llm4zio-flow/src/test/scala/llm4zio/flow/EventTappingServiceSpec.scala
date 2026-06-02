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

  /** A stub whose structured call decodes canned JSON and reports usage + model. */
  private def structuredStub(json: String, usage: TokenUsage, model: String): LlmService = new LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fromEither(summon[JsonCodec[A]].decoder.decodeJson(json)).mapError(e => LlmError.ParseError(e, json))
    override def executeStructuredWithUsage[A: JsonCodec](
      prompt: String,
      schema: JsonSchema,
    ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
      executeStructured[A](prompt, schema).map(a => (a, Some(usage), Some(model)))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  final case class Out(x: Int) derives JsonCodec

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("EventTappingService")(
    test("publishes TokensUsed (tagged with the agent) from a structured call that reports usage") {
      for
        sink <- FlowEvents.collecting
        svc   = EventTappingService(
                  structuredStub("""{"x":1}""", TokenUsage(7, 3, 10), "claude-sonnet-4-6"),
                  agent = "reasoning",
                  events = sink,
                  workDir = Paths.get("/repo"),
                )
        out  <- svc.executeStructured[Out]("plan it", zio.json.ast.Json.Obj())
        rec  <- sink.recorded
      yield assertTrue(
        out == Out(1),
        rec.contains(FlowEvent.TokensUsed("reasoning", Some("claude-sonnet-4-6"), TokenUsage(7, 3, 10))),
      )
    },
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
