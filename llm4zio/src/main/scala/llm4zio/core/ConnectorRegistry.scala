package llm4zio.core

import zio.*

trait ConnectorFactory:
  def connectorId: ConnectorId
  def kind: ConnectorKind
  def create(config: ConnectorConfig): IO[LlmError, Connector]

trait ConnectorRegistry:
  def resolve(config: ConnectorConfig): IO[LlmError, Connector]
  def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector]
  def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector]
  def available: UIO[List[ConnectorId]]
  def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]]

final case class ConnectorRegistryLive(factories: Map[ConnectorId, ConnectorFactory]) extends ConnectorRegistry:

  override def resolve(config: ConnectorConfig): IO[LlmError, Connector] =
    ZIO.fromOption(factories.get(config.connectorId))
      .orElseFail(LlmError.ConfigError(s"Unknown connector: ${config.connectorId.value}"))
      .flatMap(_.create(config))

  override def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector] =
    resolve(config).flatMap {
      case api: ApiConnector => ZIO.succeed(api)
      case other             => ZIO.fail(LlmError.ConfigError(s"Expected ApiConnector, got ${other.kind}"))
    }

  override def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector] =
    resolve(config).flatMap {
      case cli: CliConnector => ZIO.succeed(cli)
      case other             => ZIO.fail(LlmError.ConfigError(s"Expected CliConnector, got ${other.kind}"))
    }

  override def available: UIO[List[ConnectorId]] =
    ZIO.succeed(factories.keys.toList)

  override def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]] =
    ZIO.foreach(factories.toList) { case (id, factory) =>
      val defaultConfig: ConnectorConfig =
        if factory.kind == ConnectorKind.Api then ApiConnectorConfig(id)
        else CliConnectorConfig(id)
      factory.create(defaultConfig)
        .flatMap(_.healthCheck)
        .map(status => id -> status)
        .catchAll(_ => ZIO.succeed(id -> HealthStatus(Availability.Unknown, AuthStatus.Unknown, None)))
    }.map(_.toMap)

object ConnectorRegistry:
  def resolve(config: ConnectorConfig): ZIO[ConnectorRegistry, LlmError, Connector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolve(config))
  def resolveApi(config: ApiConnectorConfig): ZIO[ConnectorRegistry, LlmError, ApiConnector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolveApi(config))
  def resolveCli(config: CliConnectorConfig): ZIO[ConnectorRegistry, LlmError, CliConnector] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.resolveCli(config))
  def available: ZIO[ConnectorRegistry, Nothing, List[ConnectorId]] =
    ZIO.serviceWithZIO[ConnectorRegistry](_.available)
