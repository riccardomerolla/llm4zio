package di

import zio.*
import zio.http.netty.NettyConfig
import zio.http.{ Client, DnsResolver, ZClient }

import agents.*
import core.*
import db.*
import models.*
import orchestration.*
import web.WebServer
import web.controllers.*

object ApplicationDI:

  type CommonServices =
    FileService &
      MigrationConfig &
      ResponseParser &
      HttpAIClient &
      AIService &
      StateService &
      javax.sql.DataSource &
      MigrationRepository &
      ProgressTracker &
      ResultPersister &
      CobolDiscoveryAgent &
      CobolAnalyzerAgent &
      BusinessLogicExtractorAgent &
      DependencyMapperAgent &
      JavaTransformerAgent &
      ValidationAgent &
      DocumentationAgent

  def commonLayers(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, CommonServices] =
    ZLayer.make[CommonServices](
      // Core services and configuration
      FileService.live,
      ZLayer.succeed(config),
      ZLayer.succeed(config.resolvedProviderConfig),
      ZLayer.succeed(RateLimiterConfig.fromMigrationConfig(config)),

      // Service implementations
      RateLimiter.live,
      ResponseParser.live,
      httpClientLayer(config).orDie,
      HttpAIClient.live,
      AIService.fromConfig.mapError(e => new RuntimeException(e.message)).orDie,
      StateService.live(config.stateDir),
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:$dbPath")),
      Database.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MigrationRepository.live,
      ProgressTracker.live,
      ResultPersister.live,

      // Agent implementations
      CobolDiscoveryAgent.live,
      CobolAnalyzerAgent.live,
      BusinessLogicExtractorAgent.live,
      DependencyMapperAgent.live,
      JavaTransformerAgent.live,
      ValidationAgent.live,
      DocumentationAgent.live,
    )

  private def httpClientLayer(config: MigrationConfig): ZLayer[Any, Throwable, Client] =
    val timeout      = config.resolvedProviderConfig.timeout
    val idleTimeout  = timeout + 300.seconds
    val clientConfig = ZClient.Config.default.copy(
      idleTimeout = Some(idleTimeout),
      connectionTimeout = Some(300.seconds),
    )
    (ZLayer.succeed(clientConfig) ++ ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++
      DnsResolver.default) >>> Client.live

  def orchestratorLayer(config: MigrationConfig): ZLayer[Any, Nothing, MigrationOrchestrator] =
    orchestratorLayer(config, config.stateDir.resolve("migration.db"))

  def orchestratorLayer(config: MigrationConfig, dbPath: java.nio.file.Path)
    : ZLayer[Any, Nothing, MigrationOrchestrator] =
    ZLayer.make[MigrationOrchestrator](
      commonLayers(config, dbPath),

      // Orchestration
      MigrationOrchestrator.live,
    )

  def webServerLayer(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, WebServer] =
    ZLayer.make[WebServer](
      commonLayers(config, dbPath),
      ZLayer.succeed(config.resolvedProviderConfig),
      MigrationOrchestrator.live,
      RunsController.live,
      AnalysisController.live,
      GraphController.live,
      DashboardController.live,
      SettingsController.live,
      AgentsController.live,
      ChatRepository.live.mapError(_ => new RuntimeException("chat repository initialization failed")).orDie,
      IssueAssignmentOrchestrator.live,
      ChatController.live,
      WebServer.live,
    )
