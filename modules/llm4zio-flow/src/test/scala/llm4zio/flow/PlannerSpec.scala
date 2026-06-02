package llm4zio.flow

import zio.*
import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object PlannerSpec extends ZIOSpecDefault:

  /** Decodes its canned JSON into whatever structured type is requested. */
  final class StubStructured(json: String) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fromEither(json.fromJson[A]).mapError(e => LlmError.ParseError(e, json))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Planner")(
    test("from returns the plan the model produced") {
      val json =
        """{"epicId":"add-multiply","tasks":[{"title":"Add multiply","description":"impl multiply","completed":false}]}"""
      for plan <- Planner.from(StubStructured(json), "Add multiply to the calculator")
      yield assertTrue(plan == Plan("add-multiply", List(Task("Add multiply", "impl multiply"))))
    },
    test("from maps an LLM/parse failure into FlowError.Llm") {
      for res <- Planner.from(StubStructured("not json at all"), "x").either
      yield assertTrue(res.isLeft, res.left.exists(_.isInstanceOf[FlowError.Llm]))
    },
  )
