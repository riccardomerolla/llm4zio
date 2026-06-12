package llm4zio.flow

import java.nio.file.Path

import zio.*
import zio.json.JsonCodec
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ContextAccessSpec extends ZIOSpecDefault:

  /** Inert service — these tests never call the LLM. */
  final class StubService extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val dir = Path.of("/tmp/ctx-access-spec")

  private def ctxWith(events: FlowEvents): FlowContext =
    FlowContext(
      reasoning = StubService(),
      coder = StubService(),
      git = GitTool(dir),
      gh = GhTool(dir),
      events = events,
      userPrompt = "add multiply",
      workDir = dir,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowContext as the single given")(
    test("stage resolves FlowEvents from an in-scope FlowContext (no `given FlowEvents` line)") {
      for
        ev  <- FlowEvents.collecting
        _   <- {
          given FlowContext = ctxWith(ev)
          stage("build")(ZIO.unit)
        }
        rec <- ev.recorded
      yield assertTrue(rec == Chunk(FlowEvent.StageStarted("build"), FlowEvent.StageCompleted("build")))
    },
    test("FlowContext carries the user prompt and working directory") {
      val ctx = ctxWith(FlowEvents.noop)
      assertTrue(ctx.userPrompt == "add multiply", ctx.workDir == dir)
    },
    test("bare-name accessors return the context members") {
      val ctx           = ctxWith(FlowEvents.noop)
      given FlowContext = ctx
      assertTrue(
        git == ctx.git,
        gh == ctx.gh,
        coder == ctx.coder,
        reasoning == ctx.reasoning,
        userPrompt == "add multiply",
        workDir == dir,
      )
    },
  )
