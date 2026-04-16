package config.control

import zio.*
import zio.test.*

import llm4zio.core.*
import shared.testfixtures.StubConfigRepository

object ConnectorConfigResolverSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ConnectorConfigResolver")(
    test("resolves global API connector config") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.id"      -> "openai",
          "connector.default.model"   -> "gpt-4o",
          "connector.default.apiKey"  -> "sk-test",
          "connector.default.timeout" -> "600",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.OpenAI,
        config.model == Some("gpt-4o"),
        config.timeout == 600.seconds,
        config.isInstanceOf[ApiConnectorConfig],
        config.asInstanceOf[ApiConnectorConfig].apiKey == Some("sk-test"),
      )
    },
    test("agent override merges with global defaults") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.id"        -> "openai",
          "connector.default.model"     -> "gpt-4o",
          "connector.default.apiKey"    -> "sk-test",
          "agent.coder.connector.id"    -> "claude-cli",
          "agent.coder.connector.model" -> "claude-sonnet-4",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("coder"))
      yield assertTrue(
        config.connectorId == ConnectorId.ClaudeCli,
        config.model == Some("claude-sonnet-4"),
        config.isInstanceOf[CliConnectorConfig],
      )
    },
    test("falls back to library defaults when no settings") {
      val repo     = new StubConfigRepository(Map.empty)
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.GeminiCli,
        config.isInstanceOf[CliConnectorConfig],
      )
    },
    test("reads legacy ai.* keys as fallback") {
      val repo     = new StubConfigRepository(
        Map(
          "ai.provider" -> "Anthropic",
          "ai.model"    -> "claude-sonnet-4",
          "ai.apiKey"   -> "sk-legacy",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.Anthropic,
        config.model == Some("claude-sonnet-4"),
      )
    },
  )
