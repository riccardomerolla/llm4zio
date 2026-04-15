package llm4zio.core

import zio.*
import zio.json.*
import zio.test.*

object ConnectorConfigSpec extends ZIOSpecDefault:
  def spec = suite("ConnectorConfig")(
    test("ApiConnectorConfig has correct defaults") {
      val cfg = ApiConnectorConfig(connectorId = ConnectorId.OpenAI, model = Some("gpt-4o"))
      assertTrue(
        cfg.timeout == 300.seconds,
        cfg.maxRetries == 3,
        cfg.requestsPerMinute == 60,
        cfg.burstSize == 10,
        cfg.acquireTimeout == 30.seconds,
        cfg.baseUrl.isEmpty,
        cfg.apiKey.isEmpty,
        cfg.temperature.isEmpty,
        cfg.maxTokens.isEmpty,
      )
    },
    test("CliConnectorConfig has correct defaults") {
      val cfg = CliConnectorConfig(connectorId = ConnectorId.GeminiCli, model = Some("gemini-2.5-flash"))
      assertTrue(
        cfg.timeout == 300.seconds,
        cfg.maxRetries == 3,
        cfg.flags.isEmpty,
        cfg.sandbox.isEmpty,
        cfg.turnLimit.isEmpty,
        cfg.envVars.isEmpty,
      )
    },
    test("ConnectorConfig sealed trait dispatches correctly") {
      val api: ConnectorConfig = ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o"))
      val cli: ConnectorConfig = CliConnectorConfig(ConnectorId.GeminiCli, Some("gemini-2.5-flash"))
      assertTrue(
        api.connectorId == ConnectorId.OpenAI,
        cli.connectorId == ConnectorId.GeminiCli,
      )
    },
    test("ApiConnectorConfig JSON round-trip") {
      val cfg = ApiConnectorConfig(
        connectorId = ConnectorId.Anthropic,
        model = Some("claude-sonnet-4"),
        apiKey = Some("sk-test"),
      )
      val json   = cfg.toJson
      val parsed = json.fromJson[ApiConnectorConfig]
      assertTrue(parsed == Right(cfg))
    },
    test("CliConnectorConfig JSON round-trip") {
      val cfg = CliConnectorConfig(
        connectorId = ConnectorId.ClaudeCli,
        model = Some("claude-sonnet-4"),
        flags = Map("print" -> "true"),
      )
      val json   = cfg.toJson
      val parsed = json.fromJson[CliConnectorConfig]
      assertTrue(parsed == Right(cfg))
    },
    test("FallbackChain preserves order") {
      val chain = FallbackChain(List(
        ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o")),
        ApiConnectorConfig(ConnectorId.Anthropic, Some("claude-sonnet-4")),
      ))
      assertTrue(
        chain.connectors.size == 2,
        chain.connectors.head.connectorId == ConnectorId.OpenAI,
      )
    },
  )
