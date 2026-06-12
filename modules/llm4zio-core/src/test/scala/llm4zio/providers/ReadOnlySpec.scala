package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

/** `CliConnectorConfig.readOnly` maps to each backend's no-edit flag, overriding any edit-capable flag. */
object ReadOnlySpec extends ZIOSpecDefault:

  private val noopExec = new CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(Nil, 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.empty

  private val ctx = CliContext("/work", "/repo")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("read-only CLI mapping")(
    test("claude readOnly → --permission-mode plan, overriding the edit flag") {
      val argv = ClaudeCliConnector
        .make(
          CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"), readOnly = true),
          noopExec,
        )
        .buildArgv("p", ctx)
      assertTrue(argv.containsSlice(List("--permission-mode", "plan")), !argv.contains("acceptEdits"))
    },
    test("claude without readOnly keeps the configured edit flag") {
      val argv = ClaudeCliConnector
        .make(CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits")), noopExec)
        .buildArgv("p", ctx)
      assertTrue(argv.containsSlice(List("--permission-mode", "acceptEdits")))
    },
    test("codex readOnly → --sandbox read-only, overriding workspace-write") {
      val argv = CodexConnector
        .make(
          CliConnectorConfig(ConnectorId.Codex, flags = Map("sandbox" -> "workspace-write"), readOnly = true),
          noopExec,
        )
        .buildArgv("p", ctx)
      assertTrue(argv.containsSlice(List("--sandbox", "read-only")), !argv.contains("workspace-write"))
    },
    test("gemini readOnly → --approval-mode plan instead of -y") {
      val cfg = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
      val ro  = GeminiCliExecutor.buildGeminiArgs(cfg, GeminiCliExecutionContext(readOnly = true), "stream-json")
      val rw  = GeminiCliExecutor.buildGeminiArgs(cfg, GeminiCliExecutionContext(readOnly = false), "stream-json")
      assertTrue(ro.containsSlice(List("--approval-mode", "plan")), !ro.contains("-y"), rw.contains("-y"))
    },
  )
