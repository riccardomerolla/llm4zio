package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.*
import zio.json.*

import models.Codecs.given

/** Metadata for workspace associated with a task run */
case class WorkspaceMetadata(
  runId: String,
  workspaceRoot: Path,
  stateDir: Path,
  reportsDir: Path,
  outputDir: Path,
  tempDir: Path,
  createdAt: Instant,
) derives JsonCodec

type TaskStep = String

given JsonCodec[TaskStep] = JsonCodec.string.asInstanceOf[JsonCodec[TaskStep]]

case class TaskError(
  stepName: String,
  message: String,
  timestamp: Instant,
) derives JsonCodec

enum TaskStatus derives JsonCodec:
  case Idle, Running, Paused, Done, Failed

case class ProgressUpdate(
  runId: String,
  phase: String,
  itemsProcessed: Int,
  itemsTotal: Int,
  message: String,
  timestamp: Instant,
  status: String = "Running",
  percentComplete: Double = 0.0,
) derives JsonCodec

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
  geminiModel: String = AIProviderConfig().model,
  geminiTimeout: zio.Duration = AIProviderConfig().timeout,
  geminiMaxRetries: Int = AIProviderConfig().maxRetries,
  geminiRequestsPerMinute: Int = AIProviderConfig().requestsPerMinute,
  geminiBurstSize: Int = AIProviderConfig().burstSize,
  geminiAcquireTimeout: zio.Duration = AIProviderConfig().acquireTimeout,
  aiProvider: Option[AIProviderConfig] = None,
  dryRun: Boolean = false,
  verbose: Boolean = false,
  telegram: TelegramBotConfig = TelegramBotConfig(),
) derives JsonCodec:
  def resolvedProviderConfig: AIProviderConfig =
    AIProviderConfig.withDefaults(
      aiProvider.getOrElse(
        AIProviderConfig(
          model = geminiModel,
          timeout = geminiTimeout,
          maxRetries = geminiMaxRetries,
          requestsPerMinute = geminiRequestsPerMinute,
          burstSize = geminiBurstSize,
          acquireTimeout = geminiAcquireTimeout,
        )
      )
    )

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
    geminiModel: String = AIProviderConfig().model,
    geminiTimeout: zio.Duration = AIProviderConfig().timeout,
    geminiMaxRetries: Int = AIProviderConfig().maxRetries,
    geminiRequestsPerMinute: Int = AIProviderConfig().requestsPerMinute,
    geminiBurstSize: Int = AIProviderConfig().burstSize,
    geminiAcquireTimeout: zio.Duration = AIProviderConfig().acquireTimeout,
    aiProvider: Option[AIProviderConfig] = None,
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

case class TaskState(
  runId: String,
  startedAt: Instant,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  artifacts: Map[String, String],
  errors: List[TaskError],
  config: GatewayConfig,
  workspace: Option[WorkspaceMetadata],
  status: TaskStatus,
  lastCheckpoint: Instant,
  taskRunId: Option[String] = None,
  currentStepName: Option[String] = None,
) derives JsonCodec

case class Checkpoint(
  runId: String,
  step: String,
  createdAt: Instant,
  artifactPaths: Map[String, Path],
  checksum: String,
) derives JsonCodec

case class CheckpointSnapshot(
  checkpoint: Checkpoint,
  state: TaskState,
) derives JsonCodec

case class TaskRunSummary(
  runId: String,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  errorCount: Int = 0,
  taskRunId: Option[String] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
  startedAt: Instant = Instant.EPOCH,
  updatedAt: Instant = Instant.EPOCH,
) derives JsonCodec
