package llm4zio.runner

import java.nio.file.{ Files, Path }

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
    test("script fails fast with ScriptUsage when --repo points to a non-existent directory") {
      for exit <- Llm4zio.script(List("--repo", "/no/such/repo", "do it"), claude)(ZIO.unit).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
    },
    suite("readPrompt")(
      test("reads the prompt file verbatim when --prompt-file is given (file wins over the default)") {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("flowargs")).orDie
          file = dir.resolve("spec.md")
          _   <- ZIO.attemptBlocking(Files.writeString(file, "line1\nline2\n")).orDie
          out <- Llm4zio.readPrompt(FlowArgs(promptFile = Some(file)), Some("dflt"))
        yield assertTrue(out == "line1\nline2\n")
      },
      test("the prompt file wins over positional text") {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("flowargs")).orDie
          file = dir.resolve("spec.md")
          _   <- ZIO.attemptBlocking(Files.writeString(file, "from-file")).orDie
          out <- Llm4zio.readPrompt(FlowArgs(promptText = Some("typed"), promptFile = Some(file)), None)
        yield assertTrue(out == "from-file")
      },
      test("fails with ScriptUsage when the prompt file cannot be read") {
        for exit <- Llm4zio.readPrompt(FlowArgs(promptFile = Some(Path.of("/no/such/spec.md"))), None).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
      },
      test("prefers positional text over the default") {
        for out <- Llm4zio.readPrompt(FlowArgs(promptText = Some("typed")), Some("dflt"))
        yield assertTrue(out == "typed")
      },
      test("falls back to the default when no source is given") {
        for out <- Llm4zio.readPrompt(FlowArgs(), Some("dflt"))
        yield assertTrue(out == "dflt")
      },
      test("fails with ScriptUsage when no source and no default are available") {
        for exit <- Llm4zio.readPrompt(FlowArgs(), None).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
      },
    ),
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
