package llm4zio.eval

import zio.*
import zio.json.*
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.eval.Sample
import llm4zio.tools.{ AnyTool, JsonSchema }

object JudgeSpec extends ZIOSpecDefault:

  /** A minimal LlmService that records the last structured prompt and returns a fixed JSON body, parsed with the real
    * StructuredOutputs path (so a malformed body yields a genuine ParseError).
    */
  private def stubLlm(json: String, promptRef: Ref[String]): LlmService = new LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(None, Nil, "stop"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      promptRef.set(prompt) *> StructuredOutputs.parseFromText[A](json, schema)
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val dims = List(
    Dimension("correctness", "Does the response match the expected outcome?"),
    Dimension("safety", "Does the response avoid PII leakage?"),
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Judge (Layer 2)")(
    test("maps dimension scores from the model response") {
      val json = """{"scores":[{"name":"safety","score":1,"reasoning":"borderline"},
                   |{"name":"correctness","score":2,"reasoning":"matches"}]}""".stripMargin
      for
        ref <- Ref.make("")
        r   <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("the answer"))
      yield assertTrue(
        r.score("correctness").contains(2),
        r.score("safety").contains(1),
      )
    },
    test("clamps an out-of-range score and reports a missing dimension as 0") {
      val json = """{"scores":[{"name":"correctness","score":3,"reasoning":"too high"}]}"""
      for
        ref <- Ref.make("")
        r   <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("x"))
      yield assertTrue(
        r.score("correctness").contains(2), // clamped 3 -> maxScore 2
        r.score("safety").contains(0),      // omitted -> 0
        r.scores.find(_.name == "safety").exists(_.reasoning == "missing"),
      )
    },
    test("the prompt carries each dimension's rubric and scale") {
      val json = """{"scores":[]}"""
      for
        ref    <- Ref.make("")
        _      <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("x", query = Some("q")))
        prompt <- ref.get
      yield assertTrue(
        prompt.contains("Does the response match the expected outcome?"),
        prompt.contains("Does the response avoid PII leakage?"),
        prompt.contains("0..2"),
        prompt.contains("q"),
      )
    },
    test("clamps a negative score to 0") {
      val json = """{"scores":[{"name":"correctness","score":-1,"reasoning":"too low"}]}"""
      for
        ref <- Ref.make("")
        r   <- Judge.of(stubLlm(json, ref), List(Dimension("correctness", "..."))).evaluate(Sample("x"))
      yield assertTrue(r.score("correctness").contains(0))
    },
    test("a non-JSON response fails with a parse error") {
      for
        ref  <- Ref.make("")
        exit <- Judge.of(stubLlm("sorry, I cannot comply", ref), dims).evaluate(Sample("x")).exit
      yield assert(exit)(
        Assertion.fails(Assertion.isSubtype[LlmError.ParseError](Assertion.anything))
      )
    },
  )
