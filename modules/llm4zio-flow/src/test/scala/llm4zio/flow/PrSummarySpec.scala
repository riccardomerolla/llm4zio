package llm4zio.flow

import zio.*
import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object PrSummarySpec extends ZIOSpecDefault:

  final class StubStructured(json: String) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fromEither(json.fromJson[A]).mapError(e => LlmError.ParseError(e, json))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("summarisePr")(
    test("returns the title and body from structured output") {
      for s <- summarisePr(StubStructured("""{"title":"Fix off-by-one","body":"Adjust the loop bound."}"""), "a diff")
      yield assertTrue(s == PrSummary("Fix off-by-one", "Adjust the loop bound."))
    }
  )
