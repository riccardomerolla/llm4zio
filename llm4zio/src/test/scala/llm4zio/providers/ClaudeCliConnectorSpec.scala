package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object ClaudeCliConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  def spec = suite("ClaudeCliConnector")(
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
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("buildInteractiveArgv produces claude") {
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), new MockCliExec())
      val ctx = CliContext("/workspace", "/repo")
      val argv = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("claude"))
    },
    test("complete returns stdout joined") {
      val mock = new MockCliExec()
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("healthCheck returns Healthy when claude is installed") {
      val mock = new MockCliExec(responses = Map(
        List("claude", "--version") -> ProcessResult(List("claude 1.0.0"), 0)
      ))
      val connector = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
