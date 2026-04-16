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

    def get(key: String): Option[String] =
      agent
        .get(key)
        .filter(_.nonEmpty)
        .orElse(global.get(key).filter(_.nonEmpty))
        .orElse(legacy.get(key).filter(_.nonEmpty))

    val connectorId = get("id")
      .flatMap(parseConnectorId)
      .orElse(get("provider").flatMap(parseLegacyProvider))
      .getOrElse(ConnectorId.GeminiCli)

    val model   = get("model")
    val timeout = get("timeout").flatMap(s => scala.util.Try(s.toLong).toOption).map(_.seconds).getOrElse(300.seconds)
    val retries = get("maxRetries").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(3)

    if ConnectorId.allCli.contains(connectorId) then
      CliConnectorConfig(
        connectorId = connectorId,
        model = model,
        timeout = timeout,
        maxRetries = retries,
        flags = extractFlags(agent, global),
        envVars = extractEnvVars(agent, global),
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

  private def extractFlags(agent: Map[String, String], global: Map[String, String]): Map[String, String] =
    val prefix      = "flags."
    val globalFlags = global.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    val agentFlags  = agent.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    globalFlags ++ agentFlags // agent overrides global

  private def extractEnvVars(agent: Map[String, String], global: Map[String, String]): Map[String, String] =
    val prefix    = "env."
    val globalEnv = global.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    val agentEnv  = agent.collect { case (k, v) if k.startsWith(prefix) => k.stripPrefix(prefix) -> v }
    globalEnv ++ agentEnv
