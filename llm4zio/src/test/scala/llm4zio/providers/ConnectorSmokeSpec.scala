package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.TestAspect.*

import llm4zio.core.*

object ConnectorSmokeSpec extends ZIOSpecDefault:

  private val mockHttp: HttpClient = new HttpClient:
    def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      ZIO.succeed("{}")

  private val mockCli: CliProcessExecutor = new CliProcessExecutor:
    def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(List("ok"), 0))
    def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.succeed("ok")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Connector Smoke Tests")(
    suite("Registry wiring")(
      test("all 11 connectors registered") {
        val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
        for ids <- registry.available
        yield assertTrue(ids.length == 11)
      },
      test("resolving Mock API connector returns healthy") {
        val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
        val config   = ApiConnectorConfig(ConnectorId.Mock, Some("test-model"))
        for
          connector <- registry.resolve(config)
          status    <- connector.healthCheck
        yield assertTrue(
          connector.id == ConnectorId.Mock,
          status.availability == Availability.Healthy,
        )
      },
      test("resolving ClaudeCli connector succeeds") {
        val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
        val config   = CliConnectorConfig(ConnectorId.ClaudeCli, Some("claude-sonnet-4"))
        for connector <- registry.resolve(config)
        yield assertTrue(
          connector.id == ConnectorId.ClaudeCli,
          connector.kind == ConnectorKind.Cli,
        )
      },
      test("resolving GeminiCli connector succeeds") {
        val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
        val config   = CliConnectorConfig(ConnectorId.GeminiCli, Some("gemini-2.5-flash"))
        for connector <- registry.resolve(config)
        yield assertTrue(
          connector.id == ConnectorId.GeminiCli,
          connector.kind == ConnectorKind.Cli,
        )
      },
    ),
    suite("Live connector health checks")(
      test("Gemini CLI health check") {
        val executor  = GeminiCliExecutor.default
        val config    = LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash")
        val connector = GeminiCliProvider.make(config, executor)
        for status <- connector.healthCheck
        yield assertTrue(status.availability == Availability.Healthy)
      } @@ TestAspect.ifEnvSet("GEMINI_CLI_AVAILABLE"),
      test("Claude CLI health check") {
        val executor  = new CliProcessExecutor:
          def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
            ZIO.attemptBlocking {
              val pb    = new ProcessBuilder(argv*)
              pb.directory(new java.io.File(cwd))
              pb.redirectErrorStream(true)
              val proc  = pb.start()
              val lines = scala.io.Source.fromInputStream(proc.getInputStream).getLines().toList
              val exit  = proc.waitFor()
              ProcessResult(lines, exit)
            }.mapError(e => LlmError.ProviderError(e.getMessage, Some(e)))
          def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
            : ZStream[Any, LlmError, String] =
            ZStream.fromZIO(run(argv, cwd, envVars)).flatMap(r => ZStream.fromIterable(r.stdout))
        val config    = CliConnectorConfig(ConnectorId.ClaudeCli)
        val connector = ClaudeCliConnector.make(config, executor)
        for status <- connector.healthCheck
        yield assertTrue(status.availability == Availability.Healthy)
      } @@ TestAspect.ifEnvSet("CLAUDE_CLI_AVAILABLE"),
    ) @@ sequential,
  )
