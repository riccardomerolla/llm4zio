package config

import java.nio.file.{ Path, Paths }

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

import models.MigrationConfig

/** Configuration loader using ZIO Config with HOCON support
  *
  * Supports loading configuration from:
  *   - HOCON/JSON configuration files
  *   - Environment variables
  *   - Default values defined in MigrationConfig
  *
  * Configuration priority (highest to lowest):
  *   1. Environment variables (prefixed with MIGRATION_)
  *   2. Config file (application.conf or specified path)
  *   3. Default values
  */
object ConfigLoader:

  /** Config instances for custom types */
  given Config[Path]         = Config.string.map(str => Paths.get(str))
  given Config[zio.Duration] = Config.duration.map(d => zio.Duration.fromJava(d))

  /** Load configuration from default sources (application.conf + environment variables)
    *
    * @return
    *   ZIO effect that loads the configuration or fails with a configuration error
    */
  def load: IO[Config.Error, MigrationConfig] =
    ZIO.config(deriveConfig[MigrationConfig].nested("migration"))

  /** Load configuration from a specific file path
    *
    * @param configPath
    *   Path to the configuration file (HOCON or JSON)
    * @return
    *   ZIO effect that loads the configuration or fails with a configuration error
    */
  def loadFromFile(configPath: Path): IO[Config.Error, MigrationConfig] =
    ZIO
      .config(deriveConfig[MigrationConfig].nested("migration"))
      .provideLayer(
        ZLayer.succeed(ConfigProvider.fromHoconFile(configPath.toFile))
      )

  /** Load configuration with environment variable overrides
    *
    * Environment variables are prefixed with MIGRATION_ and use snake_case. For example:
    *   - MIGRATION_SOURCE_DIR
    *   - MIGRATION_GEMINI_MODEL
    *   - MIGRATION_PARALLELISM
    *
    * @return
    *   ZIO effect that loads the configuration with env var overrides
    */
  def loadWithEnvOverrides: IO[Config.Error, MigrationConfig] =
    val envProvider  = ConfigProvider.envProvider.nested("migration")
    val fileProvider = ConfigProvider.defaultProvider

    ZIO
      .config(deriveConfig[MigrationConfig])
      .provideLayer(ZLayer.succeed(envProvider.orElse(fileProvider)))

  /** Validate configuration values
    *
    * @param config
    *   The configuration to validate
    * @return
    *   ZIO effect that either succeeds with the config or fails with validation errors
    */
  def validate(config: MigrationConfig): IO[String, MigrationConfig] =
    for
      _ <- validateParallelism(config.parallelism)
      _ <- validateBatchSize(config.batchSize)
      _ <- validateRetries(config.geminiMaxRetries)
      _ <- validateTimeout(config.geminiTimeout)
      _ <- validateRateLimiter(config.geminiRequestsPerMinute, config.geminiBurstSize, config.geminiAcquireTimeout)
      _ <- validateDiscovery(config.discoveryMaxDepth, config.discoveryExcludePatterns)
    yield config

  private def validateParallelism(parallelism: Int): IO[String, Unit] =
    ZIO
      .fail(s"Parallelism must be between 1 and 64, got: $parallelism")
      .when(parallelism < 1 || parallelism > 64)
      .unit

  private def validateBatchSize(batchSize: Int): IO[String, Unit] =
    ZIO
      .fail(s"Batch size must be between 1 and 100, got: $batchSize")
      .when(batchSize < 1 || batchSize > 100)
      .unit

  private def validateRetries(retries: Int): IO[String, Unit] =
    ZIO
      .fail(s"Max retries must be between 0 and 10, got: $retries")
      .when(retries < 0 || retries > 10)
      .unit

  private def validateTimeout(timeout: zio.Duration): IO[String, Unit] =
    ZIO
      .fail(s"Timeout must be between 1 second and 10 minutes, got: ${timeout.toSeconds}s")
      .when(timeout.toSeconds < 1 || timeout.toSeconds > 600)
      .unit

  private def validateRateLimiter(
    requestsPerMinute: Int,
    burstSize: Int,
    acquireTimeout: zio.Duration,
  ): IO[String, Unit] =
    for
      _ <- ZIO
             .fail(s"Gemini requests per minute must be between 1 and 600, got: $requestsPerMinute")
             .when(requestsPerMinute < 1 || requestsPerMinute > 600)
      _ <- ZIO
             .fail(s"Gemini burst size must be between 1 and 100, got: $burstSize")
             .when(burstSize < 1 || burstSize > 100)
      _ <- ZIO
             .fail(s"Gemini acquire timeout must be between 1 and 300 seconds, got: ${acquireTimeout.toSeconds}s")
             .when(acquireTimeout.toSeconds < 1 || acquireTimeout.toSeconds > 300)
    yield ()

  private def validateDiscovery(maxDepth: Int, excludes: List[String]): IO[String, Unit] =
    for
      _ <- ZIO
             .fail(s"Discovery max depth must be between 1 and 100, got: $maxDepth")
             .when(maxDepth < 1 || maxDepth > 100)
      _ <- ZIO
             .fail("Discovery exclude patterns cannot contain empty values")
             .when(excludes.exists(_.trim.isEmpty))
    yield ()
