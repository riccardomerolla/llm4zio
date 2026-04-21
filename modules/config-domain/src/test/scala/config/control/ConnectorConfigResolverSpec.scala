package config.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.*
import llm4zio.core.*
import shared.errors.PersistenceError

/** Read-only ConfigRepository stub backed by a fixed Map. Write/workflow/custom-agent ops die.
  *
  * Colocated with ConnectorConfigResolverSpec rather than shared because the resolver is the only settings-only
  * consumer inside config-domain; broader fixtures still live in root tests.
  */
final class StubConfigRepository(settings: Map[String, String]) extends ConfigRepository:

  private val ts: Instant = Instant.parse("2026-01-01T00:00:00Z")

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    ZIO.succeed(settings.toList.map { case (k, v) => SettingRow(k, v, ts) })

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    ZIO.succeed(settings.get(key).map(v => SettingRow(key, v, ts)))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteSetting(key: String): IO[PersistenceError, Unit]                = ZIO.unit
  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]    = ZIO.unit

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.dieMessage("unused")
  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.dieMessage("unused")
  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.dieMessage("unused")
  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = ZIO.dieMessage("unused")
  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.dieMessage("unused")
  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.dieMessage("unused")
  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.dieMessage("unused")
  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = ZIO.dieMessage("unused")

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
    test("mode toggle selects between coexisting api and cli agent overrides") {
      val settings = Map(
        "agent.dual.connector.api.id"    -> "anthropic",
        "agent.dual.connector.api.model" -> "claude-opus-4",
        "agent.dual.connector.cli.id"    -> "gemini-cli",
        "agent.dual.connector.cli.model" -> "gemini-2.5-pro",
      )
      val apiRepo  = new StubConfigRepository(settings + ("agent.dual.connector.mode" -> "api"))
      val cliRepo  = new StubConfigRepository(settings + ("agent.dual.connector.mode" -> "cli"))
      for
        apiConfig <- ConnectorConfigResolverLive(apiRepo).resolve(Some("dual"))
        cliConfig <- ConnectorConfigResolverLive(cliRepo).resolve(Some("dual"))
      yield assertTrue(
        apiConfig.connectorId == ConnectorId.Anthropic,
        apiConfig.model == Some("claude-opus-4"),
        apiConfig.isInstanceOf[ApiConnectorConfig],
        cliConfig.connectorId == ConnectorId.GeminiCli,
        cliConfig.model == Some("gemini-2.5-pro"),
        cliConfig.isInstanceOf[CliConnectorConfig],
      )
    },
    test("flat agent keys beat global flat when no mode-scoped keys exist") {
      val repo     = new StubConfigRepository(
        Map(
          // No mode-scoped keys at any layer
          "connector.default.id"        -> "openai",
          "connector.default.model"     -> "gpt-4o",
          "agent.coder.connector.model" -> "custom-model",
        )
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = Some("coder"))
      yield assertTrue(
        config.connectorId == ConnectorId.OpenAI,
        config.model == Some("custom-model"),
      )
    },
    test("unrecognized connector id falls back to default (api mode → gemini-api)") {
      val repo     = new StubConfigRepository(
        Map("connector.default.id" -> "not-a-real-connector")
      )
      val resolver = ConnectorConfigResolverLive(repo)
      for config <- resolver.resolve(agentName = None)
      yield assertTrue(
        config.connectorId == ConnectorId.GeminiCli,
        config.isInstanceOf[CliConnectorConfig],
      )
    },
  )
