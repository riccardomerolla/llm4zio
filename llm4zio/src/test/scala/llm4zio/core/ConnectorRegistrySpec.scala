package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import llm4zio.tools.{AnyTool, JsonSchema}

object ConnectorRegistrySpec extends ZIOSpecDefault:

  val mockApiFactory: ConnectorFactory = new ConnectorFactory:
    def connectorId: ConnectorId = ConnectorId.Mock
    def kind: ConnectorKind = ConnectorKind.Api
    def create(config: ConnectorConfig): IO[LlmError, Connector] =
      ZIO.succeed(new ApiConnector:
        def id: ConnectorId = ConnectorId.Mock
        def healthCheck: IO[LlmError, HealthStatus] =
          ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(1.millis)))
        def isAvailable: UIO[Boolean] = ZIO.succeed(true)
        def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
        def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] = ZStream.empty
        def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.fail(LlmError.InvalidRequestError("mock"))
        def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("mock"))
      )

  def spec = suite("ConnectorRegistry")(
    test("resolve returns connector for known id") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      val config = ApiConnectorConfig(ConnectorId.Mock, Some("mock-model"))
      for connector <- registry.resolve(config)
      yield assertTrue(connector.id == ConnectorId.Mock)
    },
    test("resolve fails for unknown id") {
      val registry = ConnectorRegistryLive(Map.empty)
      val config = ApiConnectorConfig(ConnectorId("unknown"), Some("model"))
      for result <- registry.resolve(config).exit
      yield assertTrue(result match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.ConfigError])
        case _                   => false
      )
    },
    test("available returns registered ids") {
      val registry = ConnectorRegistryLive(Map(ConnectorId.Mock -> mockApiFactory))
      for ids <- registry.available
      yield assertTrue(ids == List(ConnectorId.Mock))
    },
  )
