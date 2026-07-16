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

  /** Captures the argv and stdin passed to runWithStdin / runStreamingWithStdin. */
  final class StdinCapturingExec(
    seenArgv: Ref[List[String]],
    seenStdin: Ref[String],
    streamLines: List[String],
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.succeed(ProcessResult(List("mocked response"), 0))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] = ZStream.fromIterable(streamLines)
    override def runWithStdin(
      argv: List[String],
      cwd: String,
      envVars: Map[String, String],
      stdin: String,
    ): IO[LlmError, ProcessResult] =
      seenArgv.set(argv) *> seenStdin.set(stdin) *> ZIO.succeed(ProcessResult(List("mocked response"), 0))
    override def runStreamingWithStdin(
      argv: List[String],
      cwd: String,
      envVars: Map[String, String],
      stdin: String,
    ): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seenArgv.set(argv) *> seenStdin.set(stdin)).drain ++ ZStream.fromIterable(streamLines)

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
    test("executeStructured parses a JSON pick out of the stream-json output") {
      val lines = List(
        """{"type":"system","subtype":"init","model":"claude-opus-4-8"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"{\"lane\":\"Frontend\",\"rationale\":\"UI change\"}"}]}}""",
        """{"type":"result","usage":{"input_tokens":120,"output_tokens":45}}""",
      )
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        connector  = ClaudeCliConnector.make(
                       CliConnectorConfig(ConnectorId.ClaudeCli),
                       new StdinCapturingExec(seenArgv, seenStdin, lines),
                     )
        pick      <- connector.executeStructured[TriagePick]("triage prompt", zio.json.ast.Json.Obj())
        argv      <- seenArgv.get
      yield assertTrue(
        pick == TriagePick("Frontend", "UI change"),
        // Structured calls must ride the stream path — the non-streaming path reports no usage,
        // which is how the reasoning seat's judge/planner calls billed 0 tokens in the ledger.
        argv.containsSlice(List("--output-format", "stream-json")),
      )
    },
    test("executeStructured tolerates a fenced JSON block in the streamed text") {
      val lines = List(
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Here is your triage:\n```json\n{\"lane\":\"Backend\",\"rationale\":\"server work\"}\n```\nDone."}]}}""",
        """{"type":"result","usage":{"input_tokens":10,"output_tokens":5}}""",
      )
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        connector  = ClaudeCliConnector.make(
                       CliConnectorConfig(ConnectorId.ClaudeCli),
                       new StdinCapturingExec(seenArgv, seenStdin, lines),
                     )
        pick      <- connector.executeStructured[TriagePick]("triage prompt", zio.json.ast.Json.Obj())
      yield assertTrue(pick == TriagePick("Backend", "server work"))
    },
    test("executeStructuredWithUsage carries the stream's token usage and served model") {
      val lines = List(
        """{"type":"system","subtype":"init","model":"claude-opus-4-8"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"{\"lane\":\"Frontend\",\"rationale\":\"UI change\"}"}]}}""",
        """{"type":"result","usage":{"input_tokens":120,"output_tokens":45,"cache_read_input_tokens":1000}}""",
      )
      for
        seenArgv             <- Ref.make(List.empty[String])
        seenStdin            <- Ref.make("")
        connector             = ClaudeCliConnector.make(
                                  CliConnectorConfig(ConnectorId.ClaudeCli),
                                  new StdinCapturingExec(seenArgv, seenStdin, lines),
                                )
        (pick, usage, model) <-
          connector.executeStructuredWithUsage[TriagePick]("triage prompt", zio.json.ast.Json.Obj())
      yield assertTrue(
        pick == TriagePick("Frontend", "UI change"),
        usage.exists(u => u.prompt == 120 && u.completion == 45 && u.cached.contains(1000)),
        model.contains("claude-opus-4-8"),
      )
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
    test("completeStream stamps the init model onto the usage chunk and emits assistant text") {
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        exec       = StdinCapturingExec(seenArgv, seenStdin, claudeFixtureLines)
        connector  = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), exec)
        chunks    <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.exists(c => c.usage.exists(_.prompt == 1200) && c.metadata.get("model").contains("claude-sonnet-4-6")),
        chunks.map(_.delta).mkString.contains("Editing the file."),
      )
    },
    test("completeStream passes prompt via stdin, NOT in argv") {
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        exec       = StdinCapturingExec(seenArgv, seenStdin, claudeFixtureLines)
        connector  = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), exec)
        _         <- connector.completeStream("THE-PROMPT").runDrain
        argv      <- seenArgv.get
        stdin     <- seenStdin.get
      yield assertTrue(
        !argv.contains("THE-PROMPT"),
        stdin == "THE-PROMPT",
      )
    },
    test("complete passes prompt via stdin, NOT in argv") {
      for
        seenArgv  <- Ref.make(List.empty[String])
        seenStdin <- Ref.make("")
        exec       = StdinCapturingExec(seenArgv, seenStdin, Nil)
        connector  = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), exec)
        _         <- connector.complete("THE-PROMPT")
        argv      <- seenArgv.get
        stdin     <- seenStdin.get
      yield assertTrue(
        !argv.contains("THE-PROMPT"),
        stdin == "THE-PROMPT",
      )
    },
  )
