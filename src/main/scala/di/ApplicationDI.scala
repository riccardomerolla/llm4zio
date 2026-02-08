package di

import zio.*
import zio.http.Client

import agents.*
import core.*
import models.*
import orchestration.*

object ApplicationDI:

  def orchestratorLayer(config: MigrationConfig): ZLayer[Any, Nothing, MigrationOrchestrator] =
    ZLayer.make[MigrationOrchestrator](
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

      // Agent implementations
      CobolDiscoveryAgent.live,
      CobolAnalyzerAgent.live,
      DependencyMapperAgent.live,
      JavaTransformerAgent.live,
      ValidationAgent.live,
      DocumentationAgent.live,

      // Orchestration
      MigrationOrchestrator.live,
    )
