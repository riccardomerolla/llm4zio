package llm4zio.providers

import scala.io.Source

import zio.*
import zio.json.JsonCodec
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object CodexConnectorSpec extends ZIOSpecDefault:

  final case class ReplyStub(summary: String) derives JsonCodec

  /** Captures the argv passed to runStreaming, then replays a canned agent_message line. */
  final class RecordingExec(seen: Ref[List[String]], line: String) extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(List("{}"), 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seen.set(a)).drain ++ ZStream.succeed(line)

  private def codexFixtureLines: List[String] =
    val src = Source.fromResource("codex-stream.jsonl")
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

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CodexConnector")(
    test("id is codex") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      assertTrue(connector.id == ConnectorId.Codex)
    },
    test("kind is Cli") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is InteractiveStdin") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
    },
    test("buildArgv produces codex exec prompt") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("codex", "exec", "fix the bug"))
    },
    test("buildArgv threads --model and passthrough flags (e.g. --full-auto)") {
      val cfg       = CliConnectorConfig(ConnectorId.Codex, model = Some("o3"), flags = Map("full-auto" -> ""))
      val connector = CodexConnector.make(cfg, new MockCliExec())
      val argv      = connector.buildArgv("do it", CliContext("/w", "/r"))
      assertTrue(
        argv.startsWith(List("codex", "exec", "--model", "o3")),
        argv.contains("--full-auto"),
        argv.last == "do it",
      )
    },
    test("buildInteractiveArgv produces codex") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("codex"))
    },
    test("complete runs codex in the configured workingDir") {
      final class RecordingExec(seen: Ref[String]) extends CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          seen.set(cwd).as(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] = ZStream.empty
      for
        seen <- Ref.make("")
        conn  = CodexConnector.make(
                  CliConnectorConfig(ConnectorId.Codex, workingDir = Some("/tmp/repo")),
                  RecordingExec(seen),
                )
        _    <- conn.complete("x")
        cwd  <- seen.get
      yield assertTrue(cwd == "/tmp/repo")
    },
    test("complete returns stdout joined") {
      val mock      = new MockCliExec()
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("healthCheck returns Healthy when codex is installed") {
      val mock      = new MockCliExec(responses =
        Map(
          List("codex", "--version") -> ProcessResult(List("codex 1.0.0"), 0)
        )
      )
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("parseStreamLine maps an agent_message to a text chunk") {
      val chunks = codexFixtureLines.flatMap(CodexConnector.parseStreamLine)
      assertTrue(chunks.exists(c => c.delta == "Adding multiply."))
    },
    test("parseStreamLine maps a command_execution to a tool chunk") {
      val chunks = codexFixtureLines.flatMap(CodexConnector.parseStreamLine)
      assertTrue(chunks.exists(c =>
        c.metadata.get("event").contains("tool_use") &&
        c.metadata.get("tool_name").contains("Bash") &&
        c.metadata.get("tool_input").exists(_.contains("cargo test"))
      ))
    },
    test("parseStreamLine maps turn.completed to a usage chunk") {
      val chunks = codexFixtureLines.flatMap(CodexConnector.parseStreamLine)
      assertTrue(chunks.exists(c => c.usage.exists(_.prompt == 900) && c.usage.exists(_.completion == 30)))
    },
    test("completeStream emits the agent text and a tool chunk from the JSONL stream") {
      val argv      = List("codex", "exec", "--json", "go")
      val mock      = new MockCliExec(Map(argv -> ProcessResult(codexFixtureLines, 0)))
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), mock)
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString.contains("Adding multiply."),
        chunks.exists(_.metadata.get("event").contains("tool_use")),
        chunks.exists(_.usage.exists(_.prompt == 900)),
      )
    },
    test("executeStructured passes --output-schema to codex when a non-trivial schema is given") {
      val line = """{"type":"item.completed","item":{"type":"agent_message","text":"{\"summary\":\"x\"}"}}"""
      for
        seen <- Ref.make(List.empty[String])
        conn  = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new RecordingExec(seen, line))
        out  <- conn.executeStructured[ReplyStub]("go", SchemaDerivation.derive[ReplyStub])
        argv <- seen.get
      yield assertTrue(
        out == ReplyStub("x"),
        argv.contains("--output-schema"),
        argv.containsSlice(List("codex", "exec", "--json")),
      )
    },
  )
