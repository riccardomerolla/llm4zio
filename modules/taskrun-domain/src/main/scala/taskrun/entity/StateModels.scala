package taskrun.entity

import java.time.Instant

import zio.json.*

import shared.json.JsonCodecs.given

/** Metadata for workspace associated with a task run */
case class WorkspaceMetadata(
  runId: String,
  workspaceRoot: java.nio.file.Path,
  stateDir: java.nio.file.Path,
  reportsDir: java.nio.file.Path,
  outputDir: java.nio.file.Path,
  tempDir: java.nio.file.Path,
  createdAt: Instant,
) derives JsonCodec

case class TaskError(
  stepName: String,
  message: String,
  timestamp: Instant,
) derives JsonCodec

enum TaskStatus derives JsonCodec:
  case Idle, Running, Paused, Done, Failed

case class TaskState(
  runId: String,
  startedAt: Instant,
  currentStep: TaskStep,
  completedSteps: Set[TaskStep],
  artifacts: Map[String, String],
  errors: List[TaskError],
  config: _root_.config.entity.GatewayConfig,
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
  artifactPaths: Map[String, java.nio.file.Path],
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
