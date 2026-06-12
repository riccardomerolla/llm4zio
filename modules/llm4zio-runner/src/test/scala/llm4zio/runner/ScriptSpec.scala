package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.json.JsonCodec
import zio.stream.*
import zio.test.*

import llm4zio.core.{ LlmChunk, LlmError, LlmService, Message, ToolCallResponse }
import llm4zio.flow.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ScriptSpec extends ZIOSpecDefault:

  /** Inert service — these tests never call the LLM. */
  final private class StubService extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val dir = Path.of("/tmp/script-spec")

  private def stubCtx(prompt: String): FlowContext =
    FlowContext(
      reasoning = StubService(),
      coder = StubService(),
      git = GitTool(dir),
      gh = GhTool(dir),
      events = FlowEvents.noop,
      userPrompt = prompt,
      workDir = dir,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Llm4zio.script")(
    test("resolvePrompt prefers the first arg, falls back to the default, then to a usage error") {
      assertTrue(
        Llm4zio.resolvePrompt(List("do it", "extra"), Some("dflt")) == Right("do it"),
        Llm4zio.resolvePrompt(Nil, Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(List("  "), Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(Nil, None).isLeft,
      )
    },
    test("script fails with ScriptUsage before touching any connector when no prompt is available") {
      for exit <- Llm4zio.script(Nil, claude)(ZIO.unit).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
    },
    suite("scriptReasoning")(
      test("defaults to coder.copy(readOnly = true) when no explicit reasoning connector is given") {
        assertTrue(Llm4zio.scriptReasoning(claude, None) == claude.copy(readOnly = true))
      },
      test("uses the explicit reasoning connector when one is provided") {
        assertTrue(Llm4zio.scriptReasoning(claude, Some(codex)) == codex)
      },
    ),
    suite("withPrompt")(
      test("threads the prompt into the FlowContext so the bare userPrompt accessor sees it") {
        val ctx    = stubCtx("original")
        val result = Llm4zio.withPrompt("the prompt")(ZIO.succeed(userPrompt)).apply(ctx)
        for value <- result
        yield assertTrue(value == "the prompt")
      }
    ),
  )
