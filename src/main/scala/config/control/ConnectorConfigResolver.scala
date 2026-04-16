package config.control

import zio.*

import _root_.config.entity.ConfigRepository
import llm4zio.core.*
import shared.errors.PersistenceError

trait ConnectorConfigResolver:
  def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig]

object ConnectorConfigResolver:
  def resolve(agentName: Option[String]): ZIO[ConnectorConfigResolver, PersistenceError, ConnectorConfig] =
    ZIO.serviceWithZIO[ConnectorConfigResolver](_.resolve(agentName))
  val live: ZLayer[ConfigRepository, Nothing, ConnectorConfigResolver]                                    =
    ZLayer.fromFunction(ConnectorConfigResolverLive.apply)

final case class ConnectorConfigResolverLive(repo: ConfigRepository) extends ConnectorConfigResolver:

  override def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig] =
    for
      agentSettings  <- agentName.fold(ZIO.succeed(Map.empty[String, String]))(name =>
                          repo
                            .getSettingsByPrefix(s"agent.$name.connector.")
                            .map(_.map(row => row.key -> row.value).toMap)
                        )
      globalSettings <- repo
                          .getSettingsByPrefix("connector.default.")
                          .map(_.map(row => row.key -> row.value).toMap)
      legacySettings <- repo
                          .getSettingsByPrefix("ai.")
                          .map(_.map(row => row.key -> row.value).toMap)
    yield buildConfig(agentSettings, globalSettings, legacySettings, agentName)

  private def buildConfig(
    agentSettings: Map[String, String],
    globalSettings: Map[String, String],
    legacySettings: Map[String, String],
    agentName: Option[String],
  ): ConnectorConfig =
    // Strip prefixes for uniform key access
    val agent  = agentName.fold(Map.empty[String, String])(name =>
      agentSettings.map { case (k, v) => k.stripPrefix(s"agent.$name.connector.") -> v }
    )
    val global = globalSettings.map { case (k, v) => k.stripPrefix("connector.default.") -> v }
    val legacy = legacySettings.map { case (k, v) => k.stripPrefix("ai.") -> v }

    // Determine mode: agent.<name>.connector.mode, default "api"
    val mode = agent.get("mode").filter(_.nonEmpty).getOrElse("api")

    // Mode-scoped agent keys: agent.<name>.connector.{api|cli}.*
    val agentModed = agent.collect { case (k, v) if k.startsWith(s"$mode.") => k.stripPrefix(s"$mode.") -> v }

    // Mode-scoped global keys: connector.default.{api|cli}.*
    val globalModed = global.collect { case (k, v) if k.startsWith(s"$mode.") => k.stripPrefix(s"$mode.") -> v }

    // Flat agent keys (no api./cli. prefix) for backward compat
    val agentFlat = agent.filterNot { case (k, _) =>
      k.startsWith("api.") || k.startsWith("cli.") || k == "mode"
    }

    // Flat global keys (no api./cli. prefix) for backward compat
    val globalFlat = global.filterNot { case (k, _) =>
      k.startsWith("api.") || k.startsWith("cli.")
    }

    // Resolution order:
    // 1. agent mode-scoped  (agent.<name>.connector.{api|cli}.*)
    // 2. global mode-scoped (connector.default.{api|cli}.*)
    // 3. agent flat         (agent.<name>.connector.*)
    // 4. global flat        (connector.default.*)
    // 5. legacy             (ai.*)
    def get(key: String): Option[String] =
      agentModed
        .get(key)
        .filter(_.nonEmpty)
        .orElse(globalModed.get(key).filter(_.nonEmpty))
        .orElse(agentFlat.get(key).filter(_.nonEmpty))
        .orElse(globalFlat.get(key).filter(_.nonEmpty))
        .orElse(legacy.get(key).filter(_.nonEmpty))

    val defaultConnector = if mode == "cli" then ConnectorId.ClaudeCli else ConnectorId.GeminiCli

    val connectorId = get("id")
      .flatMap(parseConnectorId)
      .orElse(get("provider").flatMap(parseConnectorId))
      .orElse(get("connector").flatMap(parseConnectorId))
      .orElse(get("provider").flatMap(parseLegacyProvider))
      .getOrElse(defaultConnector)

    val model   = get("model")
    val timeout = get("timeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(300.seconds)
    val retries = get("maxRetries").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(3)

    if ConnectorId.allCli.contains(connectorId) then
      CliConnectorConfig(
        connectorId = connectorId,
        model = model,
        timeout = timeout,
        maxRetries = retries,
        flags = extractFlags(agentModed, globalModed, agentFlat, globalFlat),
        envVars = extractEnvVars(agentModed, globalModed, agentFlat, globalFlat),
        sandbox = get("sandbox").flatMap(parseSandbox),
        turnLimit = get("turnLimit").flatMap(s => scala.util.Try(s.toInt).toOption),
      )
    else
      ApiConnectorConfig(
        connectorId = connectorId,
        model = model,
        baseUrl = get("baseUrl"),
        apiKey = get("apiKey"),
        timeout = timeout,
        maxRetries = retries,
        requestsPerMinute =
          get("requestsPerMinute").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(60),
        burstSize = get("burstSize").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(10),
        acquireTimeout =
          get("acquireTimeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(30.seconds),
        temperature = get("temperature").flatMap(s => scala.util.Try(s.toDouble).toOption),
        maxTokens = get("maxTokens").flatMap(s => scala.util.Try(s.toInt).toOption),
      )

  private def parseConnectorId(value: String): Option[ConnectorId] =
    ConnectorId.all.find(_.value == value.toLowerCase.trim)

  private def parseLegacyProvider(value: String): Option[ConnectorId] =
    value.trim.toLowerCase match
      case "geminicli" => Some(ConnectorId.GeminiCli)
      case "geminiapi" => Some(ConnectorId.GeminiApi)
      case "openai"    => Some(ConnectorId.OpenAI)
      case "anthropic" => Some(ConnectorId.Anthropic)
      case "lmstudio"  => Some(ConnectorId.LmStudio)
      case "ollama"    => Some(ConnectorId.Ollama)
      case "opencode"  => Some(ConnectorId.OpenCode)
      case "mock"      => Some(ConnectorId.Mock)
      case _           => None

  private[control] def parseSandbox(value: String): Option[CliSandbox] =
    value.trim.toLowerCase match
      case "docker"        => Some(CliSandbox.Docker(image = "default"))
      case "podman"        => Some(CliSandbox.Podman)
      case "seatbeltmacos" => Some(CliSandbox.SeatbeltMacOS)
      case "runsc"         => Some(CliSandbox.Runsc)
      case "lxc"           => Some(CliSandbox.Lxc)
      case _               => None

  private def extractFlags(
    agentModed: Map[String, String],
    globalModed: Map[String, String],
    agentFlat: Map[String, String],
    globalFlat: Map[String, String],
  ): Map[String, String] =
    val prefix = "flags."
    def collect(m: Map[String, String]) = m.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    collect(globalFlat) ++ collect(globalModed) ++ collect(agentFlat) ++ collect(agentModed)

  private def extractEnvVars(
    agentModed: Map[String, String],
    globalModed: Map[String, String],
    agentFlat: Map[String, String],
    globalFlat: Map[String, String],
  ): Map[String, String] =
    val prefix = "env."
    def collect(m: Map[String, String]) = m.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    collect(globalFlat) ++ collect(globalModed) ++ collect(agentFlat) ++ collect(agentModed)
