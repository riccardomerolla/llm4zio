package llm4zio.providers

import scala.io.Source

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object ClaudeCliConnectorSpec extends ZIOSpecDefault:

  final case class TriagePick(lane: String, rationale: String) derives JsonCodec

  private def claudeFixtureLines: List[String] =
    val src = Source.fromResource("claude-stream.jsonl")
    try src.getLines().toList
    finally src.close()

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] =
      ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ClaudeCliConnector")(
    test("id is claude-cli") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.id == ConnectorId.ClaudeCli)
    },
    test("kind is Cli") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is InteractiveStdin") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
    },
    test("buildArgv produces claude --print prompt") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("buildInteractiveArgv produces claude") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("claude"))
    },
    test("complete returns stdout joined") {
      val mock      = new MockCliExec()
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("is an LlmService — the CliConnector trait provides the capability uniformly") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      assertTrue(connector.isInstanceOf[LlmService])
    },
    test("executeStructured parses a JSON pick out of the shell stdout") {
      val mock      = new MockCliExec(responses =
        Map(
          List("claude", "--print", "triage prompt") ->
            ProcessResult(List("""{"lane":"Frontend","rationale":"UI change"}"""), 0)
        )
      )
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for pick <- connector.executeStructured[TriagePick]("triage prompt", zio.json.ast.Json.Obj())
      yield assertTrue(pick == TriagePick("Frontend", "UI change"))
    },
    test("executeStructured tolerates a fenced JSON block in the shell output") {
      val mock      = new MockCliExec(responses =
        Map(
          List("claude", "--print", "triage prompt") ->
            ProcessResult(
              List(
                "Here is your triage:",
                "```json",
                """{"lane":"Backend","rationale":"server work"}""",
                "```",
                "Done.",
              ),
              0,
            )
        )
      )
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for pick <- connector.executeStructured[TriagePick]("triage prompt", zio.json.ast.Json.Obj())
      yield assertTrue(pick == TriagePick("Backend", "server work"))
    },
    test("healthCheck returns Healthy when claude is installed") {
      val mock      = new MockCliExec(responses =
        Map(
          List("claude", "--version") -> ProcessResult(List("claude 1.0.0"), 0)
        )
      )
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("buildArgv includes --model and passthrough flags when configured") {
      val cfg       = CliConnectorConfig(
        ConnectorId.ClaudeCli,
        model = Some("sonnet"),
        flags = Map("permission-mode" -> "acceptEdits", "dangerously-skip-permissions" -> ""),
      )
      val connector = ClaudeCliConnector.make(cfg, new MockCliExec())
      val argv      = connector.buildArgv("do it", CliContext("/w", "/r"))
      assertTrue(
        argv.startsWith(List("claude", "--print", "--model", "sonnet")),
        argv.containsSlice(List("--permission-mode", "acceptEdits")),
        argv.contains("--dangerously-skip-permissions"),
        argv.last == "do it",
      )
    },
    test("complete runs the agent in the configured workingDir") {
      final class RecordingExec(seen: Ref[String]) extends CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          seen.set(cwd).as(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] = ZStream.empty
      for
        seen <- Ref.make("")
        cfg   = CliConnectorConfig(ConnectorId.ClaudeCli, workingDir = Some("/tmp/repo"))
        conn  = ClaudeCliConnector.make(cfg, RecordingExec(seen))
        _    <- conn.complete("x")
        cwd  <- seen.get
      yield assertTrue(cwd == "/tmp/repo")
    },
    test("parseStreamLine maps assistant text to a text chunk") {
      val chunks = claudeFixtureLines.flatMap(ClaudeCliConnector.parseStreamLine)
      assertTrue(chunks.exists(c => c.delta == "Editing the file."))
    },
    test("parseStreamLine maps a tool_use block to contract metadata") {
      val chunks = claudeFixtureLines.flatMap(ClaudeCliConnector.parseStreamLine)
      assertTrue(chunks.exists(c =>
        c.metadata.get("event").contains("tool_use") &&
        c.metadata.get("tool_name").contains("Edit") &&
        c.metadata.get("tool_input").exists(_.contains("src/lib.rs"))
      ))
    },
    test("parseStreamLine maps a result to a usage chunk") {
      val chunks = claudeFixtureLines.flatMap(ClaudeCliConnector.parseStreamLine)
      assertTrue(chunks.exists(c => c.usage.exists(_.prompt == 1200) && c.usage.exists(_.completion == 40)))
    },
    test("initModel reads the model from the system init line") {
      assertTrue(claudeFixtureLines.flatMap(ClaudeCliConnector.initModel).headOption.contains("claude-sonnet-4-6"))
    },
  )
