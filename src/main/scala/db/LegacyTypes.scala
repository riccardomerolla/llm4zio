package db

import java.time.Instant

import zio.json.*

given JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)

enum RunStatus derives JsonCodec:
  case Pending, Running, Paused, Completed, Failed, Cancelled

enum FileType derives JsonCodec:
  case Program, Copybook, JCL, Unknown

enum PersistenceError derives JsonCodec:
  case ConnectionFailed(cause: String)
  case QueryFailed(sql: String, cause: String)
  case NotFound(entity: String, id: Long)
  case SchemaInitFailed(cause: String)

case class DatabaseConfig(
  jdbcUrl: String
) derives JsonCodec

case class TaskRunRow(
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

case class WorkflowRow(
  id: Option[Long] = None,
  name: String,
  description: Option[String] = None,
  steps: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class TaskReportRow(
  id: Long,
  taskRunId: Long,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec

case class TaskArtifactRow(
  id: Long,
  taskRunId: Long,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec

case class SettingRow(
  key: String,
  value: String,
  updatedAt: Instant,
) derives JsonCodec

case class CustomAgentRow(
  id: Option[Long] = None,
  name: String,
  displayName: String,
  description: Option[String] = None,
  systemPrompt: String,
  tags: Option[String] = None,
  enabled: Boolean = true,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec
