package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object CopilotConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] =
      ZStream.fromIterable(responses.get(argv).map(_.stdout).getOrElse(List("mocked")))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CopilotConnector")(
    test("id is copilot") {
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), new MockCliExec())
      assertTrue(connector.id == ConnectorId.Copilot)
    },
    test("kind is Cli") {
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), new MockCliExec())
      assertTrue(connector.kind == ConnectorKind.Cli)
    },
    test("interactionSupport is ContinuationOnly") {
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), new MockCliExec())
      assertTrue(connector.interactionSupport == InteractionSupport.ContinuationOnly)
    },
    test("buildArgv produces gh copilot suggest -t shell prompt") {
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("gh", "copilot", "suggest", "-t", "shell", "fix the bug"))
    },
    test("buildInteractiveArgv produces gh copilot") {
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("gh", "copilot"))
    },
    test("complete returns stdout joined") {
      val mock      = new MockCliExec()
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), mock)
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("healthCheck returns Healthy when gh copilot is installed") {
      val mock      = new MockCliExec(responses =
        Map(
          List("gh", "copilot", "--version") -> ProcessResult(List("gh copilot 1.0.0"), 0)
        )
      )
      val connector = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
