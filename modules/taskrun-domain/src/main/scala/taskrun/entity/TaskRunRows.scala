package taskrun.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum RunStatus derives JsonCodec:
  case Pending, Running, Paused, Completed, Failed, Cancelled

final case class TaskRunRow(
  id: Long,
  sourceDir: String = "",
  outputDir: String = "",
  status: RunStatus,
  startedAt: Instant,
  completedAt: Option[Instant],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
  currentPhase: Option[String],
  errorMessage: Option[String],
  workflowId: Option[Long] = None,
) derives JsonCodec

final case class TaskReportRow(
  id: Long,
  taskRunId: Long,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec

final case class TaskArtifactRow(
  id: Long,
  taskRunId: Long,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec

final case class StoredTaskRunRow(
  id: String,
  sourceDir: String,
  outputDir: String,
  status: String,
  workflowId: Option[String],
  currentPhase: Option[String],
  errorMessage: Option[String],
  startedAt: Instant,
  completedAt: Option[Instant],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
) derives JsonCodec, Schema

final case class StoredTaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec, Schema

final case class StoredTaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec, Schema
