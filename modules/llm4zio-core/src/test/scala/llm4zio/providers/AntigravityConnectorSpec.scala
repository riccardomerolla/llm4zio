package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object AntigravityConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty,
    streamFailure: Option[LlmError] = None,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] =
      streamFailure match
        case Some(err) => ZStream.fail(err)
        case None      => ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  /** Captures the argv passed to `run` (the path `complete` takes), replaying a canned result. */
  final class ArgvCapturingExec(seenArgv: Ref[List[String]]) extends CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      seenArgv.set(a).as(ProcessResult(List("ok"), 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromZIO(seenArgv.set(a)).drain ++ ZStream.succeed("ok")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AntigravityConnector")(
    test("id is antigravity-cli") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      assertTrue(connector.id == ConnectorId.AntigravityCli)
    },
    test("kind is Cli") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is InteractiveStdin") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.InteractiveStdin)
    },
    test("buildArgv produces agy --print <prompt>") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("agy", "--print", "fix the bug"))
    },
    test("buildArgv threads --model and passthrough flags, keeping --print <prompt> as the trailing pair") {
      val cfg       =
        CliConnectorConfig(
          ConnectorId.AntigravityCli,
          model = Some("Gemini 3.5 Flash (Low)"),
          flags = Map("sandbox" -> ""),
        )
      val connector = AntigravityConnector.make(cfg, new MockCliExec())
      val argv      = connector.buildArgv("do it", CliContext("/w", "/r"))
      assertTrue(
        argv.startsWith(List("agy", "--model", "Gemini 3.5 Flash (Low)")),
        argv.contains("--sandbox"),
        argv.takeRight(2) == List("--print", "do it"),
      )
    },
    test("buildInteractiveArgv produces agy with no --print") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      val argv      = connector.buildInteractiveArgv(CliContext("/workspace", "/repo"))
      assertTrue(argv == List("agy"), !argv.contains("--print"))
    },
    test("complete passes --add-dir <workingDir> so agy edits the repo, not its scratch project") {
      // agy does NOT treat the process cwd as its workspace: in --print mode it writes edits to its own scratch
      // project unless the target dir is added with --add-dir. Without this a coder's edits never reach the repo.
      for
        seenArgv <- Ref.make(List.empty[String])
        conn      = AntigravityConnector.make(
                      CliConnectorConfig(ConnectorId.AntigravityCli, workingDir = Some("/tmp/repo")),
                      ArgvCapturingExec(seenArgv),
                    )
        _        <- conn.complete("edit something")
        argv     <- seenArgv.get
      yield assertTrue(argv.containsSlice(List("--add-dir", "/tmp/repo")))
    },
    test("completeStream passes --add-dir <workingDir>") {
      for
        seenArgv <- Ref.make(List.empty[String])
        conn      = AntigravityConnector.make(
                      CliConnectorConfig(ConnectorId.AntigravityCli, workingDir = Some("/tmp/repo")),
                      ArgvCapturingExec(seenArgv),
                    )
        _        <- conn.completeStream("edit something").runDrain
        argv     <- seenArgv.get
      yield assertTrue(argv.containsSlice(List("--add-dir", "/tmp/repo")))
    },
    test("no --add-dir when no workingDir is configured") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      val argv      = connector.buildArgv("p", CliContext("/workspace", "/repo"))
      assertTrue(!argv.contains("--add-dir"))
    },
    test("complete runs agy in the configured workingDir") {
      final class CwdCapturingExec(seen: Ref[String]) extends CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          seen.set(cwd).as(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] = ZStream.empty
      for
        seen <- Ref.make("")
        conn  = AntigravityConnector.make(
                  CliConnectorConfig(ConnectorId.AntigravityCli, workingDir = Some("/tmp/repo")),
                  CwdCapturingExec(seen),
                )
        _    <- conn.complete("x")
        cwd  <- seen.get
      yield assertTrue(cwd == "/tmp/repo")
    },
    test("complete returns stdout joined") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("complete surfaces stderr text when agy exits non-zero") {
      val argv      = List("agy", "--print", "hello")
      val mock      = new MockCliExec(Map(
        argv -> ProcessResult(Nil, 1, List("Error: invalid --model \"nope\": model nope is not recognized"))
      ))
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), mock)
      for res <- connector.complete("hello").either
      yield assertTrue(res.left.toOption.exists(_.message.contains("model nope is not recognized")))
    },
    test("healthCheck returns Healthy when agy is installed") {
      val mock      = new MockCliExec(responses =
        Map(List("agy", "--version") -> ProcessResult(List("1.1.2"), 0))
      )
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("healthCheck returns Unhealthy when agy --version fails") {
      val mock      = new MockCliExec(responses =
        Map(List("agy", "--version") -> ProcessResult(Nil, 127))
      )
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Unhealthy)
    },
    test("completeStream emits stdout lines as chunks with a terminal finishReason=stop") {
      val argv      = List("agy", "--print", "go")
      val mock      = new MockCliExec(Map(argv -> ProcessResult(List("line one", "line two"), 0)))
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), mock)
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta) == Chunk("line one", "line two", ""),
        chunks.last.finishReason.contains("stop"),
      )
    },
    test("completeStream fails when the underlying process exits non-zero") {
      val mock      = new MockCliExec(streamFailure = Some(LlmError.ProviderError("agy exited with code 1: boom", None)))
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), mock)
      for res <- connector.completeStream("go").runCollect.either
      yield assertTrue(res.left.toOption.exists(_.message.contains("boom")))
    },
    test("executeWithTools fails: agy has no exposed tool-calling interface") {
      val connector = AntigravityConnector.make(CliConnectorConfig(ConnectorId.AntigravityCli), new MockCliExec())
      for res <- connector.executeWithTools("go", Nil).either
      yield assertTrue(res.isLeft)
    },
  )
