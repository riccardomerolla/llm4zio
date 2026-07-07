package llm4zio.javaapi

import java.nio.file.Path
import java.util.function.Supplier

import zio.json.{ DecoderOps, JsonCodec }
import zio.stream.{ Stream, ZStream }
import zio.test.*
import zio.{ IO, Ref, Scope, UIO, ZIO }

import llm4zio.core.*
import llm4zio.flow.*
import llm4zio.providers.MockProvider
import llm4zio.tools.{ AnyTool, JsonSchema }

/** The Java-facing handle. These exercise it the way Java code does — synchronous, blocking calls — while asserting the
  * same progress-event protocol the `.sc` surface produces. The handle runs on the live runtime captured per test.
  */
object JavaFlowSpec extends ZIOSpecDefault:

  private val dir  = Path.of("/tmp/java-flow-spec")
  private val mock = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock"))

  private def ctx(events: FlowEvents): FlowContext =
    FlowContext(reasoning = mock, coder = mock, git = GitTool(dir), gh = GhTool(dir), events = events, workDir = dir)

  /** Counts structured calls and answers every review with a clean ReviewResult. */
  final private class CountingReviewService(hits: Ref[Int]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      hits.update(_ + 1) *>
        ZIO.fromEither("""{"issues":[]}""".fromJson[A]).mapError(e => LlmError.ParseError(e, "{}"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & Scope, Any] = suite("JavaFlow")(
    test("stage publishes StageStarted then StageCompleted around a successful body, returning its value") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        result <- ZIO.attemptBlocking(flow.stage("branch", (() => 7): Supplier[Int]))
        seen   <- events.recorded
      yield assertTrue(
        result == 7,
        seen.toList == List(FlowEvent.StageStarted("branch"), FlowEvent.StageCompleted("branch")),
      )
    },
    test("stage publishes StageFailed and re-throws when the body throws") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        body    = (() => throw new RuntimeException("boom")): Supplier[Int] // scalafix:ok DisableSyntax.throw
        exit   <- ZIO.attemptBlocking(flow.stage("branch", body)).exit
        seen   <- events.recorded
      yield assertTrue(
        exit.isFailure,
        seen.toList == List(FlowEvent.StageStarted("branch"), FlowEvent.StageFailed("branch", "boom")),
      )
    },
    test("startChat then ask returns the assistant reply") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        reply  <- ZIO.attemptBlocking {
                    val chat = flow.startChat("you implement one task at a time")
                    chat.ask("hello")
                  }
      yield assertTrue(reply.contains("mock response"))
    },
    test("reviewAndFixLoop with no reviewers settles clean in one round without an LLM call") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        _      <- ZIO.attemptBlocking {
                    val chat = flow.startChat("impl")
                    flow.reviewAndFixLoop(java.util.List.of(), chat, "First step", () => "a diff")
                  }
        seen   <- events.recorded
      yield assertTrue(seen.exists {
        case FlowEvent.Info(m) => m.contains("settled")
        case _                 => false
      })
    },
    test("Refs.issue parses owner/repo#number and rejects garbage") {
      val ok  = Refs.issue("octocat/hello#42")
      val bad = Refs.issue("not-a-ref")
      assertTrue(ok.isPresent, ok.get.owner == "octocat", ok.get.repo == "hello", ok.get.number == 42, !bad.isPresent)
    },
    test("fail publishes Aborted and throws Llm4zioException(Aborted)") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        caught <- ZIO.attemptBlocking {
                    try { flow.fail("nope"); Option.empty[ErrorCategory] }
                    catch case e: Llm4zioException => Some(e.getCategory)
                  }
        seen   <- events.recorded
      yield assertTrue(caught.contains(ErrorCategory.Aborted), seen.contains(FlowEvent.Aborted("nope")))
    },
    test("savePlan then loadPlan round-trips the plan") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        tmp    <- ZIO.attempt(java.nio.file.Files.createTempFile("plan2", ".md"))
        loaded <- ZIO.attemptBlocking {
                    flow.savePlan(tmp, Plan("epic-1", List(Task("t1", "d1"))))
                    flow.loadPlan(tmp)
                  }
      yield assertTrue(loaded.isPresent, loaded.get.epicId == "epic-1", loaded.get.tasks.size == 1)
    },
    test("reviewAndFixLoop runs reviews on the first extra reviewer seat, not the reasoning connector") {
      for
        events        <- FlowEvents.collecting
        rt            <- ZIO.runtime[Any]
        reviewerHits  <- Ref.make(0)
        reasoningHits <- Ref.make(0)
        reviewerSeat   = new CountingReviewService(reviewerHits)
        reasoningSeat  = new CountingReviewService(reasoningHits)
        flowCtx        = ctx(events).copy(reasoning = reasoningSeat, reviewers = List(reviewerSeat))
        flow           = new JavaFlow(rt, flowCtx)
        _             <- ZIO.attemptBlocking {
                           val chat = flow.startChat("impl")
                           // fully qualified: the file's `import llm4zio.flow.*` would otherwise shadow the
                           // same-package javaapi.Reviewers with the flow-layer object of the same name
                           flow.reviewAndFixLoop(llm4zio.javaapi.Reviewers.minimal(), chat, "First step", () => "a diff")
                         }
        onReviewer    <- reviewerHits.get
        onReasoning   <- reasoningHits.get
      yield assertTrue(onReviewer > 0, onReasoning == 0)
    },
    test("deletePlan removes the persisted plan file") {
      for
        events <- FlowEvents.collecting
        rt     <- ZIO.runtime[Any]
        flow    = new JavaFlow(rt, ctx(events))
        tmp    <- ZIO.attempt(java.nio.file.Files.createTempFile("plan", ".md"))
        _      <- PlanStore.save(tmp, Plan("x", List(Task("t", "d"))))
        before <- ZIO.attempt(java.nio.file.Files.exists(tmp)) // evaluate Files.* outside assertTrue (macro `Files$`)
        _      <- ZIO.attemptBlocking(flow.deletePlan(tmp))
        after  <- ZIO.attempt(java.nio.file.Files.exists(tmp))
      yield assertTrue(before, !after)
    },
  ) @@ TestAspect.withLiveClock
