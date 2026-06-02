package llm4zio.flow

import zio.*
import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object InteractivePlannerSpec extends ZIOSpecDefault:

  /** Pops the next canned PlanningStep JSON per structured call. */
  final class StubSteps(steps: Ref[List[String]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      steps
        .modify {
          case head :: tail => (head, tail)
          case Nil          => ("""{"kind":"Proposed","plan":{"epicId":"x","tasks":[]}}""", Nil)
        }
        .flatMap(json => ZIO.fromEither(json.fromJson[A]).mapError(e => LlmError.ParseError(e, json)))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  final class Scripted(answers: Ref[List[String]], asked: Ref[List[String]]) extends Interaction:
    def ask(question: String): IO[FlowError, String] =
      asked.update(_ :+ question) *>
        answers.modify {
          case head :: tail => (head, tail)
          case Nil          => ("(no answer)", Nil)
        }

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Planner.interactive")(
    test("asks a clarifying question, then returns the proposed plan") {
      val ask  = """{"kind":"AskUser","question":"web or cli?"}"""
      val done =
        """{"kind":"Proposed","plan":{"epicId":"calc","tasks":[{"title":"t","description":"d","completed":false}]}}"""
      for
        steps <- Ref.make(List(ask, done))
        ans   <- Ref.make(List("cli"))
        asked <- Ref.make(List.empty[String])
        plan  <- Planner.interactive(StubSteps(steps), "build a calculator", Scripted(ans, asked))
        qs    <- asked.get
      yield assertTrue(plan == Plan("calc", List(Task("t", "d"))), qs == List("web or cli?"))
    },
    test("proposing immediately needs no questions") {
      for
        steps <- Ref.make(List("""{"kind":"Proposed","plan":{"epicId":"e","tasks":[]}}"""))
        ans   <- Ref.make(List.empty[String])
        asked <- Ref.make(List.empty[String])
        plan  <- Planner.interactive(StubSteps(steps), "do it", Scripted(ans, asked))
        qs    <- asked.get
      yield assertTrue(plan == Plan("e", Nil), qs.isEmpty)
    },
    test("fails if the planner never proposes within maxTurns") {
      for
        steps <- Ref.make(List.fill(10)("""{"kind":"AskUser","question":"more?"}"""))
        ans   <- Ref.make(List.fill(10)("yes"))
        asked <- Ref.make(List.empty[String])
        res   <- Planner.interactive(StubSteps(steps), "x", Scripted(ans, asked), maxTurns = 3).either
      yield assertTrue(res.isLeft)
    },
  )
