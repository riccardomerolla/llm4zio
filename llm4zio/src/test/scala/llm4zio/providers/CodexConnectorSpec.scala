package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object CodexConnectorSpec extends ZIOSpecDefault:

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
    test("buildArgv produces codex prompt") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildArgv("fix the bug", ctx)
      assertTrue(argv == List("codex", "fix the bug"))
    },
    test("buildInteractiveArgv produces codex") {
      val connector = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new MockCliExec())
      val ctx       = CliContext("/workspace", "/repo")
      val argv      = connector.buildInteractiveArgv(ctx)
      assertTrue(argv == List("codex"))
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
  )
