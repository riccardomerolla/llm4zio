package db

import java.time.Instant

import zio.json.*

given JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)

enum RunStatus derives JsonCodec:
  case Pending, Running, Completed, Failed, Cancelled

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

case class MigrationRunRow(
  id: Long,
  sourceDir: String,
  outputDir: String,
  status: RunStatus,
  startedAt: Instant,
  completedAt: Option[Instant],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
  currentPhase: Option[String],
  errorMessage: Option[String],
) derives JsonCodec

case class CobolFileRow(
  id: Long,
  runId: Long,
  path: String,
  name: String,
  fileType: FileType,
  size: Long,
  lineCount: Long,
  encoding: String,
  createdAt: Instant,
) derives JsonCodec

case class CobolAnalysisRow(
  id: Long,
  runId: Long,
  fileId: Long,
  analysisJson: String,
  createdAt: Instant,
) derives JsonCodec

case class DependencyRow(
  id: Long,
  runId: Long,
  sourceNode: String,
  targetNode: String,
  edgeType: String,
) derives JsonCodec

case class PhaseProgressRow(
  id: Long,
  runId: Long,
  phase: String,
  status: String,
  itemTotal: Int,
  itemProcessed: Int,
  errorCount: Int,
  updatedAt: Instant,
) derives JsonCodec
