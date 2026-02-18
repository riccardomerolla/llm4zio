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
  runId: Long,
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
  aiProvider: Option[AIProviderConfig] = None,
  dryRun: Boolean = false,
  verbose: Boolean = false,
  telegram: TelegramBotConfig = TelegramBotConfig(),
) derives JsonCodec:
  def resolvedProviderConfig: AIProviderConfig =
    aiProvider.map(AIProviderConfig.withDefaults).getOrElse(AIProviderConfig.withDefaults(AIProviderConfig()))

  def sourceDir: Path                        = Paths.get(".")
  def outputDir: Path                        = Paths.get("./workspace/output")
  def stateDir: Path                         = Paths.get(".migration-state")
  def discoveryMaxDepth: Int                 = 25
  def discoveryExcludePatterns: List[String] = List(
    "**/.git/**",
    "**/target/**",
    "**/node_modules/**",
    "**/.idea/**",
    "**/.vscode/**",
    "**/backup/**",
    "**/*.bak",
    "**/*.tmp",
    "**/*~",
  )
  def parallelism: Int                       = 4
  def batchSize: Int                         = 10
  def enableCheckpointing: Boolean           = true
  def enableBusinessLogicExtractor: Boolean  = false
  def resumeFromCheckpoint: Option[String]   = None
  def retryFromRunId: Option[Long]           = None
  def retryFromStep: Option[TaskStep]        = None
  def workflowId: Option[Long]               = None
  def basePackage: String                    = "com.example"
  def projectName: Option[String]            = None
  def projectVersion: String                 = "0.0.1-SNAPSHOT"
  def maxCompileRetries: Int                 = 3
  def geminiModel: String                    = resolvedProviderConfig.model
  def geminiTimeout: zio.Duration            = resolvedProviderConfig.timeout
  def geminiMaxRetries: Int                  = resolvedProviderConfig.maxRetries
  def geminiRequestsPerMinute: Int           = resolvedProviderConfig.requestsPerMinute
  def geminiBurstSize: Int                   = resolvedProviderConfig.burstSize
  def geminiAcquireTimeout: zio.Duration     = resolvedProviderConfig.acquireTimeout

object GatewayConfig:
  extension (config: GatewayConfig)
    def copy(
      sourceDir: Path = config.sourceDir,
      outputDir: Path = config.outputDir,
      stateDir: Path = config.stateDir,
      discoveryMaxDepth: Int = config.discoveryMaxDepth,
      discoveryExcludePatterns: List[String] = config.discoveryExcludePatterns,
      parallelism: Int = config.parallelism,
      batchSize: Int = config.batchSize,
      enableCheckpointing: Boolean = config.enableCheckpointing,
      resumeFromCheckpoint: Option[String] = config.resumeFromCheckpoint,
      retryFromRunId: Option[Long] = config.retryFromRunId,
      retryFromStep: Option[TaskStep] = config.retryFromStep,
      enableBusinessLogicExtractor: Boolean = config.enableBusinessLogicExtractor,
      basePackage: String = config.basePackage,
      projectName: Option[String] = config.projectName,
      projectVersion: String = config.projectVersion,
      maxCompileRetries: Int = config.maxCompileRetries,
      geminiModel: String = config.geminiModel,
      geminiTimeout: zio.Duration = config.geminiTimeout,
      geminiMaxRetries: Int = config.geminiMaxRetries,
      geminiRequestsPerMinute: Int = config.geminiRequestsPerMinute,
      geminiBurstSize: Int = config.geminiBurstSize,
      geminiAcquireTimeout: zio.Duration = config.geminiAcquireTimeout,
      aiProvider: Option[AIProviderConfig] = config.aiProvider,
      dryRun: Boolean = config.dryRun,
      verbose: Boolean = config.verbose,
      telegram: TelegramBotConfig = config.telegram,
    ): GatewayConfig =
      val providerConfig = AIProviderConfig.withDefaults(
        aiProvider.getOrElse(config.resolvedProviderConfig).copy(
          model = geminiModel,
          timeout = geminiTimeout,
          maxRetries = geminiMaxRetries,
          requestsPerMinute = geminiRequestsPerMinute,
          burstSize = geminiBurstSize,
          acquireTimeout = geminiAcquireTimeout,
        )
      )

      GatewayConfig(
        aiProvider = Some(providerConfig),
        dryRun = dryRun,
        verbose = verbose,
        telegram = telegram,
      )

type MigrationConfig = GatewayConfig

object MigrationConfig:
  def apply(
    sourceDir: Path,
    outputDir: Path,
  ): GatewayConfig =
    GatewayConfig()

  def apply(
    sourceDir: Path,
    outputDir: Path,
    stateDir: Path,
    parallelism: Int,
    batchSize: Int,
    geminiModel: String,
    geminiTimeout: zio.Duration,
    geminiMaxRetries: Int,
    geminiRequestsPerMinute: Int,
    geminiBurstSize: Int,
    geminiAcquireTimeout: zio.Duration,
    discoveryMaxDepth: Int,
    discoveryExcludePatterns: List[String],
    dryRun: Boolean,
    verbose: Boolean,
    enableCheckpointing: Boolean,
    enableBusinessLogicExtractor: Boolean,
    resumeFromCheckpoint: Option[String],
    retryFromRunId: Option[Long],
    retryFromStep: Option[TaskStep],
    basePackage: String,
    projectName: Option[String],
    projectVersion: String,
    maxCompileRetries: Int,
    aiProvider: Option[AIProviderConfig],
    telegram: TelegramBotConfig,
  ): GatewayConfig =
    GatewayConfig(aiProvider = aiProvider, dryRun = dryRun, verbose = verbose, telegram = telegram).copy(
      geminiModel = geminiModel,
      geminiTimeout = geminiTimeout,
      geminiMaxRetries = geminiMaxRetries,
      geminiRequestsPerMinute = geminiRequestsPerMinute,
      geminiBurstSize = geminiBurstSize,
      geminiAcquireTimeout = geminiAcquireTimeout,
    )

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
    retryFromRunId: Option[Long] = None,
    retryFromStep: Option[TaskStep] = None,
    basePackage: String = "com.example",
    projectName: Option[String] = None,
    projectVersion: String = "0.0.1-SNAPSHOT",
    maxCompileRetries: Int = 3,
    geminiModel: String = AIProviderConfig.withDefaults(AIProviderConfig()).model,
    geminiTimeout: zio.Duration = AIProviderConfig.withDefaults(AIProviderConfig()).timeout,
    geminiMaxRetries: Int = AIProviderConfig.withDefaults(AIProviderConfig()).maxRetries,
    geminiRequestsPerMinute: Int = AIProviderConfig.withDefaults(AIProviderConfig()).requestsPerMinute,
    geminiBurstSize: Int = AIProviderConfig.withDefaults(AIProviderConfig()).burstSize,
    geminiAcquireTimeout: zio.Duration = AIProviderConfig.withDefaults(AIProviderConfig()).acquireTimeout,
    aiProvider: Option[AIProviderConfig] = None,
    dryRun: Boolean = false,
    verbose: Boolean = false,
    telegram: TelegramBotConfig = TelegramBotConfig(),
  ): GatewayConfig =
    GatewayConfig(
      aiProvider = aiProvider,
      dryRun = dryRun,
      verbose = verbose,
      telegram = telegram,
    ).copy(
      geminiModel = geminiModel,
      geminiTimeout = geminiTimeout,
      geminiMaxRetries = geminiMaxRetries,
      geminiRequestsPerMinute = geminiRequestsPerMinute,
      geminiBurstSize = geminiBurstSize,
      geminiAcquireTimeout = geminiAcquireTimeout,
    )

case class TaskState(
  taskRunId: Option[Long] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
) derives JsonCodec:
  def runId: String                        = taskRunId.map(_.toString).getOrElse("run-unknown")
  def startedAt: Instant                   = Instant.EPOCH
  def currentStep: TaskStep                = currentStepName.getOrElse("unknown")
  def completedSteps: Set[TaskStep]        = Set.empty
  def artifacts: Map[String, String]       = Map.empty
  def errors: List[TaskError]              = List.empty
  def config: GatewayConfig                = GatewayConfig()
  def workspace: Option[WorkspaceMetadata] = None
  def lastCheckpoint: Instant              = Instant.EPOCH

object TaskState:
  def apply(
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
    taskRunId: Option[Long],
    currentStepName: Option[String],
  ): TaskState =
    TaskState(
      taskRunId = taskRunId.orElse(runId.toLongOption),
      currentStepName = currentStepName.orElse(Some(currentStep)),
      status = status,
    )

  extension (state: TaskState)
    def copy(
      runId: String = state.runId,
      startedAt: Instant = state.startedAt,
      currentStep: TaskStep = state.currentStep,
      completedSteps: Set[TaskStep] = state.completedSteps,
      artifacts: Map[String, String] = state.artifacts,
      errors: List[TaskError] = state.errors,
      config: GatewayConfig = state.config,
      workspace: Option[WorkspaceMetadata] = state.workspace,
      lastCheckpoint: Instant = state.lastCheckpoint,
      taskRunId: Option[Long] = state.taskRunId,
      currentStepName: Option[String] = state.currentStepName,
      status: TaskStatus = state.status,
    ): TaskState =
      TaskState(
        taskRunId = taskRunId.orElse(runId.toLongOption),
        currentStepName = currentStepName.orElse(Some(currentStep)),
        status = status,
      )

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
  errorCount: Int = 0,
  taskRunId: Option[Long] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
  startedAt: Instant = Instant.EPOCH,
  updatedAt: Instant = Instant.EPOCH,
) derives JsonCodec:
  def runId: String                 = taskRunId.map(_.toString).getOrElse("run-unknown")
  def currentStep: TaskStep         = currentStepName.getOrElse("unknown")
  def completedSteps: Set[TaskStep] = Set.empty
