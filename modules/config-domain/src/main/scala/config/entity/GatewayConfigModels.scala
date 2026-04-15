package config.entity

import java.nio.file.{ Path, Paths }

import zio.*
import zio.json.*

import shared.entity.TaskStep
import shared.json.JsonCodecs.given

enum TelegramMode derives JsonCodec:
  case Webhook
  case Polling

case class TelegramPollingSettings(
  interval: zio.Duration = 1.second,
  batchSize: Int = 100,
  timeoutSeconds: Int = 30,
  requestTimeout: zio.Duration = 70.seconds,
) derives JsonCodec

case class TelegramBotConfig(
  enabled: Boolean = false,
  mode: TelegramMode = TelegramMode.Webhook,
  botToken: Option[String] = None,
  secretToken: Option[String] = None,
  webhookUrl: Option[String] = None,
  polling: TelegramPollingSettings = TelegramPollingSettings(),
) derives JsonCodec

case class GatewayConfig(
  sourceDir: Path = Paths.get("."),
  outputDir: Path = Paths.get("./workspace/output"),
  stateDir: Path = Paths.get(".migration-state"),
  discoveryMaxDepth: Int = 25,
  discoveryExcludePatterns: List[String] = List(
    "**/.git/**",
    "**/target/**",
    "**/node_modules/**",
    "**/.idea/**",
    "**/.vscode/**",
    "**/backup/**",
    "**/*.bak",
    "**/*.tmp",
    "**/*~",
  ),
  parallelism: Int = 4,
  batchSize: Int = 10,
  enableCheckpointing: Boolean = true,
  enableBusinessLogicExtractor: Boolean = false,
  resumeFromCheckpoint: Option[String] = None,
  retryFromRunId: Option[String] = None,
  retryFromStep: Option[TaskStep] = None,
  workflowId: Option[String] = None,
  basePackage: String = "com.example",
  projectName: Option[String] = None,
  projectVersion: String = "0.0.1-SNAPSHOT",
  maxCompileRetries: Int = 3,
  geminiModel: String = ProviderConfig().model,
  geminiTimeout: zio.Duration = ProviderConfig().timeout,
  geminiMaxRetries: Int = ProviderConfig().maxRetries,
  geminiRequestsPerMinute: Int = ProviderConfig().requestsPerMinute,
  geminiBurstSize: Int = ProviderConfig().burstSize,
  geminiAcquireTimeout: zio.Duration = ProviderConfig().acquireTimeout,
  aiProvider: Option[ProviderConfig] = None,
  dryRun: Boolean = false,
  verbose: Boolean = false,
  telegram: TelegramBotConfig = TelegramBotConfig(),
) derives JsonCodec:
  def resolvedProviderConfig: ProviderConfig =
    ProviderConfig.withDefaults(
      aiProvider.getOrElse(
        ProviderConfig(
          model = geminiModel,
          timeout = geminiTimeout,
          maxRetries = geminiMaxRetries,
          requestsPerMinute = geminiRequestsPerMinute,
          burstSize = geminiBurstSize,
          acquireTimeout = geminiAcquireTimeout,
        )
      )
    )

  def resolvedConnectorConfig: llm4zio.core.ConnectorConfig =
    resolvedProviderConfig.toConnectorConfig

type MigrationConfig = GatewayConfig

object MigrationConfig:
  def apply(
    sourceDir: Path = Paths.get("."),
    outputDir: Path = Paths.get("./workspace/output"),
    stateDir: Path = Paths.get(".migration-state"),
    discoveryMaxDepth: Int = 25,
    discoveryExcludePatterns: List[String] = List(
      "**/.git/**",
      "**/target/**",
      "**/node_modules/**",
      "**/.idea/**",
      "**/.vscode/**",
      "**/backup/**",
      "**/*.bak",
      "**/*.tmp",
      "**/*~",
    ),
    parallelism: Int = 4,
    batchSize: Int = 10,
    enableCheckpointing: Boolean = true,
    enableBusinessLogicExtractor: Boolean = false,
    resumeFromCheckpoint: Option[String] = None,
    retryFromRunId: Option[String] = None,
    retryFromStep: Option[TaskStep] = None,
    workflowId: Option[String] = None,
    basePackage: String = "com.example",
    projectName: Option[String] = None,
    projectVersion: String = "0.0.1-SNAPSHOT",
    maxCompileRetries: Int = 3,
    geminiModel: String = ProviderConfig().model,
    geminiTimeout: zio.Duration = ProviderConfig().timeout,
    geminiMaxRetries: Int = ProviderConfig().maxRetries,
    geminiRequestsPerMinute: Int = ProviderConfig().requestsPerMinute,
    geminiBurstSize: Int = ProviderConfig().burstSize,
    geminiAcquireTimeout: zio.Duration = ProviderConfig().acquireTimeout,
    aiProvider: Option[ProviderConfig] = None,
    dryRun: Boolean = false,
    verbose: Boolean = false,
    telegram: TelegramBotConfig = TelegramBotConfig(),
  ): GatewayConfig =
    GatewayConfig(
      sourceDir = sourceDir,
      outputDir = outputDir,
      stateDir = stateDir,
      discoveryMaxDepth = discoveryMaxDepth,
      discoveryExcludePatterns = discoveryExcludePatterns,
      parallelism = parallelism,
      batchSize = batchSize,
      enableCheckpointing = enableCheckpointing,
      enableBusinessLogicExtractor = enableBusinessLogicExtractor,
      resumeFromCheckpoint = resumeFromCheckpoint,
      retryFromRunId = retryFromRunId,
      retryFromStep = retryFromStep,
      workflowId = workflowId,
      basePackage = basePackage,
      projectName = projectName,
      projectVersion = projectVersion,
      maxCompileRetries = maxCompileRetries,
      geminiModel = geminiModel,
      geminiTimeout = geminiTimeout,
      geminiMaxRetries = geminiMaxRetries,
      geminiRequestsPerMinute = geminiRequestsPerMinute,
      geminiBurstSize = geminiBurstSize,
      geminiAcquireTimeout = geminiAcquireTimeout,
      aiProvider = aiProvider,
      dryRun = dryRun,
      verbose = verbose,
      telegram = telegram,
    )
