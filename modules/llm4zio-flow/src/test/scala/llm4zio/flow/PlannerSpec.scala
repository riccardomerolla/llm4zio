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

  /** Streams a canned text reply — used to exercise the free-text `brief`. */
  final class StubText(text: String) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.succeed(LlmChunk(delta = text, finishReason = Some("stop")))
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = executeStream("")
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
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
    test("assessThenPlan returns Proceed(plan) when the model proposes one") {
      val json = """{"kind":"Proceed","value":{"epicId":"x","tasks":[]}}"""
      for v <- Planner.assessThenPlan(StubStructured(json), "do a thing")
      yield assertTrue(v match
        case Verdict.Proceed(p) => p.epicId == "x"
        case _                  => false)
    },
    test("assessThenPlan returns Blocked(reason) when the model declines") {
      val json = """{"kind":"Blocked","reason":"needs design"}"""
      for v <- Planner.assessThenPlan(StubStructured(json), "vague")
      yield assertTrue(v match
        case Verdict.Blocked(r) => r == "needs design"
        case _                  => false)
    },
    test("reviewed returns the improved plan and preserves an already-attached brief") {
      val improved = """{"epicId":"x","tasks":[{"title":"better","description":"sharper","completed":false}]}"""
      val draft    = Plan("x", List(Task("vague", "")), brief = Some("KEEP ME"))
      for p <- Planner.reviewed(StubStructured(improved), draft)
      yield assertTrue(p.tasks == List(Task("better", "sharper")), p.brief.contains("KEEP ME"))
    },
    test("briefed attaches the generated codebase brief so taskPrompt prepends it") {
      val plan = Plan("x", List(Task("a", "do it")))
      for p <- Planner.briefed(StubText("Modules: core/flow. Build: sbt test."), plan, "add a thing")
      yield assertTrue(
        p.brief.contains("Modules: core/flow. Build: sbt test."),
        p.taskPrompt(plan.tasks.head).startsWith("Modules: core/flow."),
      )
    },
    test("extensions chain: from(...).reviewed(...).briefed(...) reads like orca") {
      val draft    =
        """{"epicId":"add-multiply","tasks":[{"title":"draft","description":"d","completed":false}]}"""
      val improved =
        """{"epicId":"add-multiply","tasks":[{"title":"better","description":"sharper","completed":false}]}"""
      for plan <- Planner
                    .from(StubStructured(draft), "Add multiply")
                    .reviewed(StubStructured(improved))
                    .briefed(StubText("the brief"), "Add multiply")
      yield assertTrue(
        plan.tasks.map(_.title) == List("better"),
        plan.brief == Some("the brief"),
      )
    },
  )
