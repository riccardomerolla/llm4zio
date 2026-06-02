package llm4zio.runner

import java.nio.file.Path

import zio.Scope
import zio.test.*

import llm4zio.core.{ ApiConnectorConfig, CliConnectorConfig, ConnectorId, LlmConfig, LlmProvider }
import llm4zio.flow.EventTappingService
import llm4zio.providers.MockProvider

object DefaultFlowContextSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("DefaultFlowContext")(
    test("enrichApi fills the base URL from the provider default when unset") {
      val enriched = DefaultFlowContext.enrichApi(ApiConnectorConfig(ConnectorId.Anthropic, model = Some("m")))
      assertTrue(enriched.baseUrl == LlmProvider.defaultBaseUrl(LlmProvider.Anthropic))
    },
    test("enrichApi leaves an explicit base URL untouched") {
      val enriched =
        DefaultFlowContext.enrichApi(ApiConnectorConfig(ConnectorId.Anthropic, baseUrl = Some("http://x")))
      assertTrue(enriched.baseUrl.contains("http://x"))
    },
    test("prepare enriches an API reasoner and roots a CLI reasoner in the workDir") {
      val dir = Path.of("/tmp/repo")
      val api = DefaultFlowContext.prepare(ApiConnectorConfig(ConnectorId.Anthropic), dir)
      val cli = DefaultFlowContext.prepare(CliConnectorConfig(ConnectorId.GeminiCli), dir)
      assertTrue(
        api.asInstanceOf[ApiConnectorConfig].baseUrl == LlmProvider.defaultBaseUrl(LlmProvider.Anthropic),
        cli.asInstanceOf[CliConnectorConfig].workingDir.contains("/tmp/repo"),
      )
    },
    test("wraps the connectors for event-tapping and exposes the same hub as the context's events") {
      val reasoning = MockProvider.make(LlmConfig(LlmProvider.Mock, "r"))
      val coder     = MockProvider.make(LlmConfig(LlmProvider.Mock, "c"))
      for bundle <- DefaultFlowContext.make(reasoning, coder, Path.of("/tmp/repo"))
      yield
        val (ctx, hub) = bundle
        assertTrue(
          ctx.events eq hub,
          ctx.reasoning.isInstanceOf[EventTappingService],
          ctx.coder.isInstanceOf[EventTappingService],
        )
    },
  )
