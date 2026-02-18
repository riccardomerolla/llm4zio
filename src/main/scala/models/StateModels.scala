package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import scala.annotation.nowarn

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

object TaskStep:
  val Discovery: TaskStep      = "Discovery"
  val Analysis: TaskStep       = "Analysis"
  val Mapping: TaskStep        = "Mapping"
  val Transformation: TaskStep = "Transformation"
  val Validation: TaskStep     = "Validation"
  val Documentation: TaskStep  = "Documentation"

  // Default built-in steps used by legacy workflow definitions.
  val values: List[TaskStep] = List(Discovery, Analysis, Mapping, Transformation, Validation, Documentation)

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

case class MigrationConfig(
  sourceDir: Path,
  outputDir: Path,
  stateDir: Path = Paths.get(".migration-state"),
  aiProvider: Option[AIProviderConfig] = None,
  @deprecated("Use aiProvider.model instead", "0.2.0")
  geminiModel: String = "gemini-2.5-flash",
  @deprecated("Use aiProvider.timeout instead", "0.2.0")
  geminiTimeout: zio.Duration = zio.Duration.fromSeconds(90),
  @deprecated("Use aiProvider.maxRetries instead", "0.2.0")
  geminiMaxRetries: Int = 3,
  @deprecated("Use aiProvider.requestsPerMinute instead", "0.2.0")
  geminiRequestsPerMinute: Int = 60,
  @deprecated("Use aiProvider.burstSize instead", "0.2.0")
  geminiBurstSize: Int = 10,
  @deprecated("Use aiProvider.acquireTimeout instead", "0.2.0")
  geminiAcquireTimeout: zio.Duration = zio.Duration.fromSeconds(30),
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
  workflowId: Option[Long] = None,
  dryRun: Boolean = false,
  verbose: Boolean = false,
  basePackage: String = "com.example",
  projectName: Option[String] = None,
  projectVersion: String = "0.0.1-SNAPSHOT",
  maxCompileRetries: Int = 3,
  telegram: TelegramBotConfig = TelegramBotConfig(),
) derives JsonCodec:

  @nowarn("cat=deprecation")
  def resolvedProviderConfig: AIProviderConfig =
    aiProvider
      .map(AIProviderConfig.withDefaults)
      .getOrElse(
        AIProviderConfig(
          provider = AIProvider.GeminiCli,
          model = geminiModel,
          timeout = geminiTimeout,
          maxRetries = geminiMaxRetries,
          requestsPerMinute = geminiRequestsPerMinute,
          burstSize = geminiBurstSize,
          acquireTimeout = geminiAcquireTimeout,
        )
      )

case class TaskState(
  taskRunId: Option[Long] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
  // Backward-compatible fields retained during migration to task-centric state.
  runId: String = "run-unknown",
  startedAt: Instant = Instant.EPOCH,
  currentStep: TaskStep = TaskStep.Discovery,
  completedSteps: Set[TaskStep] = Set.empty,
  artifacts: Map[String, String] = Map.empty,
  errors: List[TaskError] = List.empty,
  config: MigrationConfig = MigrationConfig(
    sourceDir = Paths.get("."),
    outputDir = Paths.get("./workspace/output"),
  ),
  workspace: Option[WorkspaceMetadata] = None,
  lastCheckpoint: Instant = Instant.EPOCH,
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

object TaskState:
  def empty: UIO[TaskState] =
    Clock.instant.map { now =>
      TaskState(
        taskRunId = None,
        currentStepName = None,
        status = TaskStatus.Idle,
        runId = s"run-${now.toEpochMilli}",
        startedAt = now,
        currentStep = TaskStep.Discovery,
        lastCheckpoint = now,
      )
    }

case class TaskRunSummary(
  runId: String,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  errorCount: Int,
  // Generalized task state fields.
  taskRunId: Option[Long] = None,
  currentStepName: Option[String] = None,
  status: TaskStatus = TaskStatus.Idle,
  startedAt: Instant = Instant.EPOCH,
  updatedAt: Instant = Instant.EPOCH,
) derives JsonCodec
