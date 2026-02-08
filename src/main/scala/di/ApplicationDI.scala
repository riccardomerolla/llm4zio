package di

import zio.*
import zio.http.Client

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
      MigrationRepository &
      ProgressTracker &
      ResultPersister &
      CobolDiscoveryAgent &
      CobolAnalyzerAgent &
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
      Client.default.orDie,
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
      DependencyMapperAgent.live,
      JavaTransformerAgent.live,
      ValidationAgent.live,
      DocumentationAgent.live,
    )

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
      MigrationOrchestrator.live,
      RunsController.live,
      AnalysisController.live,
      GraphController.live,
      DashboardController.live,
      SettingsController.live,
      WebServer.live,
    )
