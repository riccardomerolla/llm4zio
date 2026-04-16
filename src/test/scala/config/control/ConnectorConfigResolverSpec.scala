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
    test("resolves connector.default.api.* when agent mode is api") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.api.id"     -> "openai",
          "connector.default.api.model"  -> "gpt-4o",
          "connector.default.api.apiKey" -> "sk-test",
          "connector.default.cli.id"     -> "claude-cli",
          "connector.default.cli.model"  -> "sonnet",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.OpenAI,
        config.model == Some("gpt-4o"),
        config.isInstanceOf[ApiConnectorConfig],
      )
    },
    test("resolves connector.default.cli.* when agent mode is cli") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.api.id"    -> "openai",
          "connector.default.api.model" -> "gpt-4o",
          "connector.default.cli.id"    -> "claude-cli",
          "connector.default.cli.model" -> "sonnet",
          "agent.coder.connector.mode"  -> "cli",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("coder"))
      yield assertTrue(
        config.connectorId == ConnectorId.ClaudeCli,
        config.model == Some("sonnet"),
        config.isInstanceOf[CliConnectorConfig],
      )
    },
    test("agent mode defaults to api when not set") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.api.id"    -> "anthropic",
          "connector.default.api.model" -> "claude-sonnet-4",
          "connector.default.cli.id"    -> "claude-cli",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("reviewer"))
      yield assertTrue(
        config.connectorId == ConnectorId.Anthropic,
        config.model == Some("claude-sonnet-4"),
        config.isInstanceOf[ApiConnectorConfig],
      )
    },
    test("agent api override takes precedence over api defaults") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.api.id"          -> "openai",
          "connector.default.api.model"       -> "gpt-4o",
          "agent.planner.connector.mode"      -> "api",
          "agent.planner.connector.api.id"    -> "anthropic",
          "agent.planner.connector.api.model" -> "claude-sonnet-4",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("planner"))
      yield assertTrue(
        config.connectorId == ConnectorId.Anthropic,
        config.model == Some("claude-sonnet-4"),
      )
    },
    test("agent cli override takes precedence over cli defaults") {
      val repo     = new StubConfigRepository(
        Map(
          "connector.default.cli.id"        -> "claude-cli",
          "connector.default.cli.model"     -> "sonnet",
          "agent.coder.connector.mode"      -> "cli",
          "agent.coder.connector.cli.id"    -> "gemini-cli",
          "agent.coder.connector.cli.model" -> "gemini-2.5-pro",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("coder"))
      yield assertTrue(
        config.connectorId == ConnectorId.GeminiCli,
        config.model == Some("gemini-2.5-pro"),
      )
    },
  )
