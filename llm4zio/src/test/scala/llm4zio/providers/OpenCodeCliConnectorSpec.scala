package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object OpenCodeCliConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  def spec = suite("OpenCodeCliConnector")(
    test("id is opencode") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      assertTrue(connector.id == ConnectorId.OpenCode)
    },
    test("kind is Cli") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is ContinuationOnly") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.ContinuationOnly)
    },
    test("buildArgv produces opencode run --prompt prompt") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("opencode", "run", "--prompt", "fix the bug"))
    },
    test("buildInteractiveArgv produces opencode") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("opencode"))
    },
    test("complete returns stdout joined") {
      val mock = new MockCliExec()
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("healthCheck returns Healthy when opencode is installed") {
      val mock = new MockCliExec(responses = Map(
        List("opencode", "--version") -> ProcessResult(List("opencode 1.0.0"), 0)
      ))
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
