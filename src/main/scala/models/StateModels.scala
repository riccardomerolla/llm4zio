package models

import java.nio.file.{ Path, Paths }
import java.time.Instant

import scala.annotation.nowarn

import zio.*
import zio.json.*

import models.Codecs.given

/** Metadata for workspace associated with a migration run */
case class WorkspaceMetadata(
  runId: String,
  workspaceRoot: Path,
  stateDir: Path,
  reportsDir: Path,
  outputDir: Path,
  tempDir: Path,
  createdAt: Instant,
) derives JsonCodec

enum MigrationStep derives JsonCodec:
  case Discovery, Analysis, Mapping, Transformation, Validation, Documentation

case class MigrationError(
  step: MigrationStep,
  message: String,
  timestamp: Instant,
) derives JsonCodec

case class ProgressUpdate(
  runId: Long,
  phase: String,
  itemsProcessed: Int,
  itemsTotal: Int,
  message: String,
  timestamp: Instant,
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
  retryFromStep: Option[MigrationStep] = None,
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

case class MigrationState(
  runId: String,
  startedAt: Instant,
  currentStep: MigrationStep,
  completedSteps: Set[MigrationStep],
  artifacts: Map[String, String],
  errors: List[MigrationError],
  config: MigrationConfig,
  workspace: Option[WorkspaceMetadata] = None, // Workspace info for this run
  fileInventory: Option[FileInventory],
  analyses: List[CobolAnalysis],
  dependencyGraph: Option[DependencyGraph],
  projects: List[SpringBootProject],
  validationReports: List[ValidationReport],
  lastCheckpoint: Instant,
) derives JsonCodec

case class Checkpoint(
  runId: String,
  step: MigrationStep,
  createdAt: Instant,
  artifactPaths: Map[String, Path],
  checksum: String,
) derives JsonCodec

case class CheckpointSnapshot(
  checkpoint: Checkpoint,
  state: MigrationState,
) derives JsonCodec

object MigrationState:
  def empty: UIO[MigrationState] =
    Clock.instant.map { now =>
      MigrationState(
        runId = s"run-${now.toEpochMilli}",
        startedAt = now,
        currentStep = MigrationStep.Discovery,
        completedSteps = Set.empty,
        artifacts = Map.empty,
        errors = List.empty,
        config = MigrationConfig(
          sourceDir = Paths.get("cobol-source"),
          outputDir = Paths.get("java-output"),
        ),
        fileInventory = None,
        analyses = List.empty,
        dependencyGraph = None,
        projects = List.empty,
        validationReports = List.empty,
        lastCheckpoint = now,
      )
    }

case class MigrationRunSummary(
  runId: String,
  startedAt: Instant,
  currentStep: MigrationStep,
  completedSteps: Set[MigrationStep],
  errorCount: Int,
) derives JsonCodec
