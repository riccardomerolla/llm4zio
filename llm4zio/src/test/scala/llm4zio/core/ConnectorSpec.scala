package llm4zio.core

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.tools.{ AnyTool, JsonSchema }

object ConnectorSpec extends ZIOSpecDefault:

  val stubApiConnector: ApiConnector = new ApiConnector:
    def id: ConnectorId                                                                        = ConnectorId.OpenAI
    def healthCheck: IO[LlmError, HealthStatus]                                                =
      ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, Some(50.millis)))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)
    def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]                        = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk]    = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("not implemented"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("not implemented"))

  val stubCliConnector: CliConnector = new CliConnector:
    def id: ConnectorId                                                  = ConnectorId.ClaudeCli
    def healthCheck: IO[LlmError, HealthStatus]                          =
      ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
    def isAvailable: UIO[Boolean]                                        = ZIO.succeed(true)
    def interactionSupport: InteractionSupport                           = InteractionSupport.InteractiveStdin
    def buildArgv(prompt: String, ctx: CliContext): List[String]         = List("claude", "--print", prompt)
    def buildInteractiveArgv(ctx: CliContext): List[String]              = List("claude")
    def complete(prompt: String): IO[LlmError, String]                   = ZIO.succeed("ok")
    def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] = ZStream.empty

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Connector")(
    test("ApiConnector has Api kind") {
      assertTrue(stubApiConnector.kind == ConnectorKind.Api)
    },
    test("CliConnector has Cli kind") {
      assertTrue(stubCliConnector.kind == ConnectorKind.Cli)
    },
    test("ApiConnector healthCheck returns HealthStatus") {
      for status <- stubApiConnector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("CliConnector buildArgv produces correct list") {
      val argv = stubCliConnector.buildArgv("hello", CliContext("/work", "/repo"))
      assertTrue(argv == List("claude", "--print", "hello"))
    },
  )
