package store

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.schema.{ Schema, derived }

import conversation.entity.{ Conversation, ConversationEvent }
import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.schema.{ SchemaBinaryCodec, TypedStore, TypedStoreLive }
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import issues.entity.{ AgentIssue, Assignment, IssueEvent }
import models.ActivityEvent
import taskrun.entity.{ TaskRun, TaskRunEvent }

// ---------------------------------------------------------------------------
// Store-level row types — persistence-only.
// All fields use primitive types (String, Int, Boolean, Instant, Option[…])
// to maximise binary-serialization compatibility with EclipseStore.
// Enum values are stored as String and converted at the repository boundary.
// ---------------------------------------------------------------------------

final case class TaskRunRow(
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
) derives Schema

final case class TaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives Schema

final case class TaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives Schema

final case class ConversationRow(
  id: String,
  title: String,
  description: Option[String],
  channelName: Option[String],
  status: String,
  createdAt: Instant,
  updatedAt: Instant,
  runId: Option[String],
  createdBy: Option[String],
) derives Schema

final case class ChatMessageRow(
  id: String,
  conversationId: String,
  sender: String,
  senderType: String,
  content: String,
  messageType: String,
  metadata: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives Schema

final case class AgentIssueRow(
  id: String,
  runId: Option[String],
  conversationId: Option[String],
  title: String,
  description: String,
  issueType: String,
  tags: Option[String],
  preferredAgent: Option[String],
  contextPath: Option[String],
  sourceFolder: Option[String],
  priority: String,
  status: String,
  assignedAgent: Option[String],
  assignedAt: Option[Instant],
  completedAt: Option[Instant],
  errorMessage: Option[String],
  resultData: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class AgentAssignmentRow(
  id: String,
  issueId: String,
  agentName: String,
  status: String,
  assignedAt: Instant,
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  executionLog: Option[String],
  result: Option[String],
) derives Schema

// ---------------------------------------------------------------------------
// Schema binary handlers — only primitive-field row types needed now.
// No enum handlers required because all enums are stored as String.
// ---------------------------------------------------------------------------

private val dataStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[ConversationRow])
    ++ SchemaBinaryCodec.handlers(Schema[ChatMessageRow])
    ++ SchemaBinaryCodec.handlers(Schema[SessionContextRow])
    ++ SchemaBinaryCodec.handlers(Schema[AgentIssueRow])
    ++ SchemaBinaryCodec.handlers(Schema[AgentAssignmentRow])
    ++ SchemaBinaryCodec.handlers(Schema[ActivityEvent])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRunRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskReportRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskArtifactRow])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRun])
    ++ SchemaBinaryCodec.handlers(Schema[TaskRunEvent])
    ++ SchemaBinaryCodec.handlers(Schema[AgentIssue])
    ++ SchemaBinaryCodec.handlers(Schema[Assignment])
    ++ SchemaBinaryCodec.handlers(Schema[IssueEvent])
    ++ SchemaBinaryCodec.handlers(Schema[Conversation])
    ++ SchemaBinaryCodec.handlers(Schema[ConversationEvent])

object DataStoreModule:

  /** DataStoreService exposes a TypedStore for schema-validated CRUD and the raw EclipseStoreService for key-prefix
    * scanning (streamKeys). Mirrors ConfigStoreModule.ConfigStoreService for consistency.
    */
  trait DataStoreService:
    def store: TypedStore
    def rawStore: EclipseStoreService

  /** Shutdown-checkpoint finalizer layered on top of the data-store service. */
  private val withShutdownCheckpoint: ZLayer[DataStoreRef, EclipseStoreError, DataStoreRef] =
    ZLayer.scoped {
      for
        ref <- ZIO.service[DataStoreRef]
        svc  = ref.raw
        _   <- ZIO.logInfo("Data store: loading persisted roots...") *>
                 svc.reloadRoots *>
                 ZIO.logInfo("Data store: roots loaded.")
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Data store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Data store: shutdown checkpoint complete.")
               )
      yield ref
    }

  private val toDataStoreRef: ZLayer[EclipseStoreService, Nothing, DataStoreRef] =
    ZLayer.fromFunction(DataStoreRef.apply)

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, DataStoreRef] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { cfg =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(cfg.dataStorePath)),
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
          customTypeHandlers = dataStoreHandlers,
        )
      }
    ) >>> EclipseStoreService.live.fresh >>> toDataStoreRef >>> withShutdownCheckpoint

  val dataStore: ZLayer[DataStoreRef, Nothing, DataStoreService] =
    ZLayer.fromFunction((ref: DataStoreRef) =>
      val esc = ref.raw
      new DataStoreService:
        override val store: TypedStore             = TypedStoreLive(esc)
        override val rawStore: EclipseStoreService = esc
    )

  /** Simplified live layer — produces only DataStoreService (no memory/vector infrastructure). Memory/vector
    * infrastructure is now provided separately by MemoryStoreModule.
    */
  val live: ZLayer[StoreConfig, EclipseStoreError, DataStoreService] =
    baseStore >>> dataStore
