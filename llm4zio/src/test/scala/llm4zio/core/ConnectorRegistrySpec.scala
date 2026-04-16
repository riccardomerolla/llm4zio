package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.providers.{ ConnectorFactories, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }

object ConnectorRegistrySpec extends ZIOSpecDefault:

  val mockApiFactory: ConnectorFactory = new ConnectorFactory:
    def connectorId: ConnectorId                                 = ConnectorId.Mock
    def kind: ConnectorKind                                      = ConnectorKind.Api
    def create(config: ConnectorConfig): IO[LlmError, Connector] =
      ZIO.succeed(new ApiConnector:
        def id: ConnectorId                                                                        = ConnectorId.Mock
        def healthCheck: IO[LlmError, HealthStatus]                                                =
          ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(1.millis)))
        def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)
        def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]                        = ZStream.empty
        def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk]    = ZStream.empty
        def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.InvalidRequestError("mock"))
        def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
          ZIO.fail(LlmError.InvalidRequestError("mock")))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ConnectorRegistry")(
    test("resolve returns connector for known id") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      val config   = ApiConnectorConfig(ConnectorId.Mock, Some("mock-model"))
      for connector <- registry.resolve(config)
      yield assertTrue(connector.id == ConnectorId.Mock)
    },
    test("resolve fails for unknown id") {
      val registry = ConnectorRegistryLive(Map.empty)
      val config   = ApiConnectorConfig(ConnectorId("unknown"), Some("model"))
      for result <- registry.resolve(config).exit
      yield assertTrue(result match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.ConfigError])
        case _                   => false)
    },
    test("available returns registered ids") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      for ids <- registry.available
      yield assertTrue(ids == List(ConnectorId.Mock))
    },
    test("live registry resolves all known connector ids") {
      val mockHttp = new HttpClient:
        def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("{}")
      val mockCli  = new CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          ZIO.succeed(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] =
          ZStream.succeed("ok")
      val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
      for ids <- registry.available
      yield assertTrue(
        ids.contains(ConnectorId.OpenAI),
        ids.contains(ConnectorId.Anthropic),
        ids.contains(ConnectorId.GeminiApi),
        ids.contains(ConnectorId.LmStudio),
        ids.contains(ConnectorId.Ollama),
        ids.contains(ConnectorId.ClaudeCli),
        ids.contains(ConnectorId.GeminiCli),
        ids.contains(ConnectorId.OpenCode),
        ids.contains(ConnectorId.Codex),
        ids.contains(ConnectorId.Copilot),
        ids.contains(ConnectorId.Mock),
      )
    },
    test("live registry creates API connector from config") {
      val mockHttp = new HttpClient:
        def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("{}")
      val mockCli  = new CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          ZIO.succeed(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] =
          ZStream.succeed("ok")
      val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
      val config   = ApiConnectorConfig(ConnectorId.Mock, Some("mock-model"))
      for connector <- registry.resolve(config)
      yield assertTrue(
        connector.id == ConnectorId.Mock,
        connector.kind == ConnectorKind.Api,
      )
    },
    test("live registry creates CLI connector from config") {
      val mockHttp = new HttpClient:
        def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("{}")
      val mockCli  = new CliProcessExecutor:
        def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
          ZIO.succeed(ProcessResult(List("ok"), 0))
        def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
          : ZStream[Any, LlmError, String] =
          ZStream.succeed("ok")
      val registry = ConnectorFactories.createRegistry(mockHttp, mockCli)
      val config   = CliConnectorConfig(ConnectorId.ClaudeCli, Some("claude-sonnet-4"))
      for connector <- registry.resolve(config)
      yield assertTrue(
        connector.id == ConnectorId.ClaudeCli,
        connector.kind == ConnectorKind.Cli,
      )
    },
  )
