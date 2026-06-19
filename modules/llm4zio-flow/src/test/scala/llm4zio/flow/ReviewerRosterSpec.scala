package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ReviewerRosterSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Reviewer roster")(
    test("ships the canonical reviewers with non-empty prompts") {
      val names = Reviewers.all.map(_.name).toSet
      assertTrue(
        names.contains("security"),
        names.contains("scala-zio"),
        names.contains("code-functionality"),
        Reviewers.all.size == 7,
        Reviewers.all.forall(_.systemPrompt.nonEmpty),
      )
    },
    test("minimal preset is a subset of all") {
      assertTrue(Reviewers.minimal.map(_.name).toSet.subsetOf(Reviewers.all.map(_.name).toSet))
    },
    test("opt-in lenses load with non-empty prompts and stay outside the presets") {
      val all   = Reviewers.all.map(_.name).toSet
      val optIn = List(Reviewers.tddDiscipline, Reviewers.domainLanguage, Reviewers.effectShape)
      assertTrue(
        optIn.map(_.name) == List("tdd-discipline", "domain-language", "effect-shape"),
        optIn.forall(_.systemPrompt.nonEmpty),
        optIn.forall(_.matches(Nil)),
        optIn.forall(r => !all.contains(r.name)),
      )
    },
    test("scala-zio reviewer is scoped to .scala files; an empty file list always runs") {
      val z = Reviewers.all.find(_.name == "scala-zio").get
      assertTrue(z.matches(List("src/Main.scala")), !z.matches(List("README.md")), z.matches(Nil))
    },
    test("asService prepends the reviewer's system prompt to structured calls") {
      val reviewer = Reviewer("security", "SECURITY-LENS", None)
      for
        seen <- Ref.make("")
        base  = new LlmService:
                  def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
                  def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = ZStream.empty
                  def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] =
                    ZIO.fail(LlmError.InvalidRequestError("n/a"))
                  def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
                    seen.set(p) *> ZIO.fromEither(summon[JsonCodec[A]].decoder.decodeJson("\"ok\"")).orElseFail(
                      LlmError.ParseError("x", p)
                    )
                  def isAvailable: UIO[Boolean]                                                     = ZIO.succeed(true)
        _    <- reviewer.asService(base).executeStructured[String]("REVIEW THIS", zio.json.ast.Json.Obj())
        p    <- seen.get
      yield assertTrue(p.startsWith("SECURITY-LENS"), p.contains("REVIEW THIS"))
    },
  )
