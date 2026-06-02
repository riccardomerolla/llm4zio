package llm4zio.flow

import zio.*
import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object TriageSpec extends ZIOSpecDefault:

  final class StubStructured(json: String) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fromEither(json.fromJson[A]).mapError(e => LlmError.ParseError(e, json))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("IssueRef + Triage")(
    test("IssueRef parses owner/repo#number") {
      assertTrue(
        IssueRef.parse("acme/widgets#42").contains(IssueRef("acme", "widgets", 42)),
        IssueRef.parse("nope").isEmpty,
        IssueRef.parse("a/b#x").isEmpty,
        IssueRef.parse("acme/widgets#42").map(_.shortRef).contains("acme/widgets#42"),
      )
    },
    test("Triage decodes a Testable verdict from structured output") {
      val json =
        """{"kind":"Testable","summary":"off-by-one","branchName":"fix/off-by-one","failingTestPath":"src/test/scala/X.scala"}"""
      for t <- Planner.triage(StubStructured(json), "Title", "body")
      yield assertTrue(t == Triage.Testable("off-by-one", "fix/off-by-one", "src/test/scala/X.scala"))
    },
    test("Triage decodes a NotABug verdict") {
      for t <- Planner.triage(StubStructured("""{"kind":"NotABug","explanation":"works as intended"}"""), "T", "b")
      yield assertTrue(t == Triage.NotABug("works as intended"))
    },
  )
