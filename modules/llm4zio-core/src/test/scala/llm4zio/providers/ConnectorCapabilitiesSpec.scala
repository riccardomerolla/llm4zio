package llm4zio.providers

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

/** Each connector declares an accurate capability surface so a flow can refuse an unsupported workflow up front. */
object ConnectorCapabilitiesSpec extends ZIOSpecDefault:

  private val noopExec = new CliProcessExecutor:
    def run(a: List[String], c: String, e: Map[String, String]): IO[LlmError, ProcessResult]             =
      ZIO.succeed(ProcessResult(Nil, 0))
    def runStreaming(a: List[String], c: String, e: Map[String, String]): ZStream[Any, LlmError, String] =
      ZStream.empty

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ConnectorCapabilities")(
    test("claude declares full interactive / ask-user / approval / resumable support") {
      val c = ClaudeCliConnector.make(CliConnectorConfig(ConnectorId.ClaudeCli), noopExec).capabilities
      assertTrue(c.interactiveSessions, c.askUser, c.approval, c.resumableSessions, c.streaming)
    },
    test("gemini is interactive-capable but cannot ask_user (headless MCP limitation)") {
      val c = GeminiCliProvider
        .make(LlmConfig(LlmProvider.GeminiCli, "gemini-2.5-flash"), GeminiCliExecutor.default)
        .capabilities
      assertTrue(c.interactiveSessions, !c.askUser, !c.approval)
    },
    test("continuation-only CLIs (opencode, copilot) are not interactive") {
      val oc = OpenCodeCliConnector.make(CliConnectorConfig(ConnectorId.OpenCode), noopExec).capabilities
      val cp = CopilotConnector.make(CliConnectorConfig(ConnectorId.Copilot), noopExec).capabilities
      assertTrue(!oc.interactiveSessions, !oc.askUser, !cp.interactiveSessions, !cp.askUser)
    },
    test("an API connector defaults to streaming + structured + usage, with no interactive/ask-user/approval") {
      val m = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock")).capabilities
      assertTrue(
        m.streaming,
        m.structuredOutput,
        m.usageReporting,
        !m.interactiveSessions,
        !m.askUser,
        !m.approval,
      )
    },
  )
