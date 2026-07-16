package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object OpenCodeCliConnectorSpec extends ZIOSpecDefault:

  class MockCliExec(
    responses: Map[List[String], ProcessResult] = Map.empty,
    streamLines: List[String] = Nil,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElse(ZIO.succeed(ProcessResult(List("mocked response"), 0)))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] =
      ZStream.fromIterable(streamLines)

  // Recorded from `opencode run --format json` (opencode 1.18.3): part events (step-start, text, tool, step-finish)
  // wrapped in {type, timestamp, sessionID, part}. Tokens ride each step-finish; tool args ride part.state.input.
  private val streamLines = List(
    """{"type":"step_start","timestamp":1,"sessionID":"s","part":{"id":"p1","messageID":"m","sessionID":"s","type":"step-start"}}""",
    """{"type":"tool_use","timestamp":2,"sessionID":"s","part":{"id":"p2","messageID":"m","sessionID":"s","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","input":{"command":"cargo build"},"output":"ok","title":"bash","metadata":{},"time":{"start":1,"end":2}}}}""",
    """{"type":"text","timestamp":3,"sessionID":"s","part":{"id":"p3","messageID":"m","sessionID":"s","type":"text","text":"Adding multiply.","time":{"start":1,"end":2}}}""",
    """{"type":"step_finish","timestamp":4,"sessionID":"s","part":{"id":"p4","reason":"stop","messageID":"m","sessionID":"s","type":"step-finish","tokens":{"total":10208,"input":8401,"output":4,"reasoning":11,"cache":{"write":0,"read":1792}},"cost":0}}""",
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("OpenCodeCliConnector")(
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
    test("buildArgv: opencode run with --auto for edits and the message positional last") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      val argv      = connector.buildArgv("fix the bug", CliContext("/workspace", "/repo"))
      assertTrue(
        argv.take(2) == List("opencode", "run"),
        argv.contains("--auto"),
        argv.last == "fix the bug",
        !argv.contains("--prompt"),
      )
    },
    test("buildArgv: model adds --model provider/model") {
      val connector = OpenCodeCliConnector.make(
        CliConnectorConfig(ConnectorId.OpenCode, model = Some("anthropic/claude-sonnet-5")),
        new MockCliExec(),
      )
      val argv      = connector.buildArgv("go", CliContext("/w", "/r"))
      assertTrue(argv.containsSlice(List("--model", "anthropic/claude-sonnet-5")))
    },
    test("readOnly maps to the built-in plan agent and drops --auto") {
      val connector = OpenCodeCliConnector.make(
        CliConnectorConfig(ConnectorId.OpenCode, readOnly = true),
        new MockCliExec(),
      )
      val argv      = connector.buildArgv("go", CliContext("/w", "/r"))
      assertTrue(
        argv.containsSlice(List("--agent", "plan")),
        !argv.contains("--auto"),
      )
    },
    test("buildInteractiveArgv produces opencode") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      assertTrue(connector.buildInteractiveArgv(CliContext("/w", "/r")) == List("opencode"))
    },
    test("complete returns stdout joined") {
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec())
      for result <- connector.complete("hello")
      yield assertTrue(result == "mocked response")
    },
    test("completeStream parses text, tool and per-step usage from the JSON event stream") {
      val connector =
        OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec(streamLines = streamLines))
      for chunks <- connector.completeStream("go").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString == "Adding multiply.",
        chunks.exists(c =>
          c.metadata.get("event").contains("tool_use") &&
          c.metadata.get("tool_name").contains("bash") &&
          c.metadata.get("tool_input").exists(_.contains("cargo build"))
        ),
        chunks.exists(c =>
          c.usage.exists(u => u.prompt == 8401 && u.completion == 4 && u.total == 10208 && u.cached.contains(1792))
        ),
      )
    },
    test("parseStreamLine surfaces an error event in metadata") {
      val errLine = """{"type":"error","timestamp":5,"sessionID":"s","error":"quota exhausted"}"""
      val chunks  = OpenCodeCliConnector.parseStreamLine(errLine)
      assertTrue(chunks.exists(_.metadata.get("opencodeError").contains("quota exhausted")))
    },
    test("a quota-keyword error is classified as UsageLimitError so the wait layers engage") {
      val lines     = List("""{"type":"error","timestamp":5,"sessionID":"s","error":"quota exhausted"}""")
      val connector =
        OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), new MockCliExec(streamLines = lines))
      for exit <- connector.completeStream("go").runDrain.exit
      yield assertTrue(exit match
        case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[LlmError.UsageLimitError])
        case _                   => false)
    },
    test("healthCheck returns Healthy when opencode is installed") {
      val mock      = new MockCliExec(responses =
        Map(
          List("opencode", "--version") -> ProcessResult(List("opencode 1.0.0"), 0)
        )
      )
      val connector = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), mock)
      for status <- connector.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
