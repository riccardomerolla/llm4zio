package llm4zio.flow

import zio.*
import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object LlmReviewSpec extends ZIOSpecDefault:

  /** Reviewer: pops the next canned ReviewResult JSON per structured call. */
  final class StubReviewer(results: Ref[List[String]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      results
        .modify {
          case head :: tail => (head, tail)
          case Nil          => ("""{"issues":[],"summary":"clean"}""", Nil)
        }
        .flatMap(json => ZIO.fromEither(json.fromJson[A]).mapError(e => LlmError.ParseError(e, json)))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  /** Coder: counts how many times it is asked to fix; replies "done". */
  final class CountingCoder(asks: Ref[Int]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      ZStream.fromZIO(asks.update(_ + 1)).as(LlmChunk(delta = "done", finishReason = Some("stop")))
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("reviewAndFixLoop")(
    test("reviews via a reasoning connector, fixes via the coder, halts when clean") {
      for
        ev      <- FlowEvents.collecting
        results <- Ref.make(List("""{"issues":[{"severity":"Warning","title":"nit"}],"summary":"one issue"}"""))
        asks    <- Ref.make(0)
        coder   <- Chat.start(CountingCoder(asks))
        out     <- reviewAndFixLoop(List(StubReviewer(results)), coder, "task A", ZIO.succeed("the diff"))(using ev)
        nAsks   <- asks.get
      yield assertTrue(out.isClean, nAsks == 1)
    },
    test("merges findings from multiple reviewers run in parallel") {
      for
        ev    <- FlowEvents.collecting
        rA    <- Ref.make(List("""{"issues":[{"severity":"Warning","title":"a"}],"summary":"A"}"""))
        rB    <- Ref.make(List("""{"issues":[{"severity":"Critical","title":"b"}],"summary":"B"}"""))
        asks  <- Ref.make(0)
        coder <- Chat.start(CountingCoder(asks))
        // maxRounds = 1 → evaluate once, no fix, return the merged dirty result.
        out   <- reviewAndFixLoop(List(StubReviewer(rA), StubReviewer(rB)), coder, "t", ZIO.succeed("d"), maxRounds = 1)(
                   using ev
                 )
        nAsks <- asks.get
      yield assertTrue(
        out.issues.map(_.title).toSet == Set("a", "b"),
        nAsks == 0,
      )
    },
    test("a failing lint gate short-circuits the LLM reviewers for that round") {
      for
        ev      <- FlowEvents.collecting
        calls   <- Ref.make(0)
        asks    <- Ref.make(0)
        coder   <- Chat.start(CountingCoder(asks))
        reviewer = new LlmService:
                     def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
                     def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = ZStream.empty
                     def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] =
                       ZIO.dieMessage("unused")
                     def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
                       calls.update(_ + 1) *> ZIO.dieMessage("reviewer must not run when lint fails")
                     def isAvailable: UIO[Boolean]                                                     = ZIO.succeed(true)
        lint     = ZIO.succeed(ReviewResult(List(ReviewIssue(Severity.Critical, "build broke")), "lint failed"))
        out     <-
          reviewAndFixLoop(List(reviewer), coder, "t", ZIO.succeed("d"), maxRounds = 1, lint = Some(lint))(using ev)
        nCalls  <- calls.get
      yield assertTrue(!out.isClean, out.issues.map(_.title) == List("build broke"), nCalls == 0)
    },
    test("a selector returning no reviewers yields a clean round (no reviewer calls)") {
      for
        ev      <- FlowEvents.collecting
        calls   <- Ref.make(0)
        asks    <- Ref.make(0)
        coder   <- Chat.start(CountingCoder(asks))
        reviewer = new LlmService:
                     def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
                     def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = ZStream.empty
                     def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] =
                       ZIO.dieMessage("unused")
                     def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
                       calls.update(_ + 1) *> ZIO.dieMessage("reviewer must not run")
                     def isAvailable: UIO[Boolean]                                                     = ZIO.succeed(true)
        noneSel  = new ReviewerSelector:
                     def select(rs: List[LlmService], round: Int, prev: Option[ReviewResult]): List[LlmService] = Nil
        out     <- reviewAndFixLoop(List(reviewer), coder, "t", ZIO.succeed("d"), selector = noneSel)(using ev)
        nCalls  <- calls.get
        nAsks   <- asks.get
      yield assertTrue(out.isClean, nCalls == 0, nAsks == 0)
    },
  )
