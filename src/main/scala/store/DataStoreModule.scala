package store

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.config.{ GigaMapDefinition, GigaMapIndex }
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.gigamap.vector.VectorIndexService
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import memory.MemoryEntry

given dataStoreInstantCodec: JsonCodec[Instant] = JsonCodec[String].transform(
  str => Instant.parse(str),
  instant => instant.toString,
)

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
) derives JsonCodec

final case class TaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec

final case class TaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec

final case class ConversationRow(
  id: Long,
  title: String,
  description: Option[String],
  channelName: Option[String],
  status: String,
  createdAt: Instant,
  updatedAt: Instant,
  runId: Option[Long],
  createdBy: Option[String],
) derives JsonCodec

final case class ChatMessageRow(
  id: Long,
  conversationId: Long,
  sender: String,
  senderType: String,
  content: String,
  messageType: String,
  metadata: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives JsonCodec

final case class ActivityEventRow(
  id: String,
  eventType: String,
  source: String,
  runId: Option[String],
  conversationId: Option[String],
  agentName: Option[String],
  summary: String,
  payload: Option[String],
  createdAt: Instant,
) derives JsonCodec

final case class AgentIssueRow(
  id: Long,
  runId: Option[Long],
  conversationId: Option[Long],
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
) derives JsonCodec

final case class AgentAssignmentRow(
  id: Long,
  issueId: Long,
  agentName: String,
  status: String,
  assignedAt: Instant,
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  executionLog: Option[String],
  result: Option[String],
) derives JsonCodec

object DataStoreModule:
  trait DataStoreService:
    def store: EclipseStoreService

  trait TaskRunsStore:
    def map: GigaMap[TaskRunId, TaskRunRow]

  trait TaskReportsStore:
    def map: GigaMap[ReportId, TaskReportRow]

  trait TaskArtifactsStore:
    def map: GigaMap[ArtifactId, TaskArtifactRow]

  trait ConversationsStore:
    def map: GigaMap[Long, ConversationRow]

  trait MessagesStore:
    def map: GigaMap[Long, ChatMessageRow]

  trait SessionContextsStore:
    def map: GigaMap[String, SessionContextRow]

  trait ActivityEventsStore:
    def map: GigaMap[EventId, ActivityEventRow]

  trait AgentIssuesStore:
    def map: GigaMap[Long, AgentIssueRow]

  trait AgentAssignmentsStore:
    def map: GigaMap[Long, AgentAssignmentRow]

  trait MemoryEntriesStore:
    def map: GigaMap[MemoryId, MemoryEntry]

  def taskRunsMap: URIO[TaskRunsStore, GigaMap[TaskRunId, TaskRunRow]] =
    ZIO.serviceWith[TaskRunsStore](_.map)

  def conversationsMap: URIO[ConversationsStore, GigaMap[Long, ConversationRow]] =
    ZIO.serviceWith[ConversationsStore](_.map)

  def messagesMap: URIO[MessagesStore, GigaMap[Long, ChatMessageRow]] =
    ZIO.serviceWith[MessagesStore](_.map)

  def sessionContextsMap: URIO[SessionContextsStore, GigaMap[String, SessionContextRow]] =
    ZIO.serviceWith[SessionContextsStore](_.map)

  def agentIssuesMap: URIO[AgentIssuesStore, GigaMap[Long, AgentIssueRow]] =
    ZIO.serviceWith[AgentIssuesStore](_.map)

  def agentAssignmentsMap: URIO[AgentAssignmentsStore, GigaMap[Long, AgentAssignmentRow]] =
    ZIO.serviceWith[AgentAssignmentsStore](_.map)

  def memoryEntriesMap: URIO[MemoryEntriesStore, GigaMap[MemoryId, MemoryEntry]] =
    ZIO.serviceWith[MemoryEntriesStore](_.map)

  private val taskRunsDefinition = GigaMapDefinition[TaskRunId, TaskRunRow](
    name = "taskRuns",
    indexes = Chunk(
      GigaMapIndex.single("status", _.status),
      GigaMapIndex.single("createdAt", _.startedAt),
    ),
  )

  private val taskReportsDefinition = GigaMapDefinition[ReportId, TaskReportRow](
    name = "taskReports",
    indexes = Chunk(
      GigaMapIndex.single("taskRunId", _.taskRunId),
      GigaMapIndex.single("stepName", _.stepName),
    ),
  )

  private val taskArtifactsDefinition = GigaMapDefinition[ArtifactId, TaskArtifactRow](
    name = "taskArtifacts",
    indexes = Chunk(
      GigaMapIndex.single("taskRunId", _.taskRunId)
    ),
  )

  private val conversationsDefinition = GigaMapDefinition[Long, ConversationRow](
    name = "conversations",
    indexes = Chunk(
      GigaMapIndex.single("channelName", _.channelName.getOrElse("")),
      GigaMapIndex.single("status", _.status),
    ),
  )

  private val messagesDefinition = GigaMapDefinition[Long, ChatMessageRow](
    name = "messages",
    indexes = Chunk(
      GigaMapIndex.single("conversationId", _.conversationId),
      GigaMapIndex.single("createdAt", _.createdAt),
    ),
  )

  private val sessionContextsDefinition = GigaMapDefinition[String, SessionContextRow](
    name = "sessionContexts"
  )

  private val activityEventsDefinition = GigaMapDefinition[EventId, ActivityEventRow](
    name = "activityEvents",
    indexes = Chunk(
      GigaMapIndex.single("eventType", _.eventType),
      GigaMapIndex.single("createdAt", _.createdAt),
    ),
  )

  private val agentIssuesDefinition = GigaMapDefinition[Long, AgentIssueRow](
    name = "agentIssues",
    indexes = Chunk(
      GigaMapIndex.single("runId", _.runId.getOrElse(-1L)),
      GigaMapIndex.single("conversationId", _.conversationId.getOrElse(-1L)),
      GigaMapIndex.single("status", _.status),
      GigaMapIndex.single("assignedAgent", _.assignedAgent.getOrElse("")),
      GigaMapIndex.single("updatedAt", _.updatedAt),
    ),
  )

  private val agentAssignmentsDefinition = GigaMapDefinition[Long, AgentAssignmentRow](
    name = "agentAssignments",
    indexes = Chunk(
      GigaMapIndex.single("issueId", _.issueId),
      GigaMapIndex.single("assignedAt", _.assignedAt),
    ),
  )

  private val memoryEntriesDefinition = GigaMapDefinition[MemoryId, MemoryEntry](
    name = "memoryEntries",
    indexes = Chunk(
      GigaMapIndex.single("userId", _.userId.value),
      GigaMapIndex.single("kind", _.kind.value),
    ),
  )

  /** Passthrough layer that adds a shutdown-checkpoint finalizer on top of `EclipseStoreService`. */
  private val withShutdownCheckpoint: ZLayer[EclipseStoreService, Nothing, EclipseStoreService] =
    ZLayer.scoped {
      for
        svc <- ZIO.service[EclipseStoreService]
        _   <- ZIO.addFinalizer(
                 ZIO.logInfo("Data store: performing shutdown checkpoint...") *>
                   svc.maintenance(LifecycleCommand.Checkpoint).ignoreLogged *>
                   ZIO.logInfo("Data store: shutdown checkpoint complete.")
               )
      yield svc
    }

  val baseStore: ZLayer[StoreConfig, EclipseStoreError, EclipseStoreService] =
    ZLayer.fromZIO(
      ZIO.serviceWith[StoreConfig] { storeConfig =>
        EclipseStoreConfig(
          storageTarget = StorageTarget.FileSystem(Paths.get(storeConfig.dataStorePath)),
          // Aggressive checkpoint: flush to disk every 5 seconds to ensure data persistence
          // This is critical for durability - the auto-checkpoint thread runs in the background
          // and flushes buffered writes to the filesystem. A shorter interval provides better
          // durability guarantees at the cost of slightly more I/O.
          autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
        )
      }
    ) >>> EclipseStoreService.live >>> withShutdownCheckpoint

  val dataStore: ZLayer[EclipseStoreService, Nothing, DataStoreService] =
    ZLayer.fromFunction((svc: EclipseStoreService) =>
      new DataStoreService:
        override val store: EclipseStoreService = svc
    )

  val taskRuns: ZLayer[EclipseStoreService, GigaMapError, TaskRunsStore] =
    GigaMap.make(taskRunsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[TaskRunId, TaskRunRow]) =>
      new TaskRunsStore:
        override val map: GigaMap[TaskRunId, TaskRunRow] = gm
    )

  val taskReports: ZLayer[EclipseStoreService, GigaMapError, TaskReportsStore] =
    GigaMap.make(taskReportsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[ReportId, TaskReportRow]) =>
      new TaskReportsStore:
        override val map: GigaMap[ReportId, TaskReportRow] = gm
    )

  val taskArtifacts: ZLayer[EclipseStoreService, GigaMapError, TaskArtifactsStore] =
    GigaMap.make(taskArtifactsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[ArtifactId, TaskArtifactRow]) =>
      new TaskArtifactsStore:
        override val map: GigaMap[ArtifactId, TaskArtifactRow] = gm
    )

  val conversations: ZLayer[EclipseStoreService, GigaMapError, ConversationsStore] =
    GigaMap.make(conversationsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[Long, ConversationRow]) =>
      new ConversationsStore:
        override val map: GigaMap[Long, ConversationRow] = gm
    )

  val messages: ZLayer[EclipseStoreService, GigaMapError, MessagesStore] =
    GigaMap.make(messagesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[Long, ChatMessageRow]) =>
      new MessagesStore:
        override val map: GigaMap[Long, ChatMessageRow] = gm
    )

  val sessionContexts: ZLayer[EclipseStoreService, GigaMapError, SessionContextsStore] =
    GigaMap.make(sessionContextsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[String, SessionContextRow]) =>
      new SessionContextsStore:
        override val map: GigaMap[String, SessionContextRow] = gm
    )

  val activityEvents: ZLayer[EclipseStoreService, GigaMapError, ActivityEventsStore] =
    GigaMap.make(activityEventsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[EventId, ActivityEventRow]) =>
      new ActivityEventsStore:
        override val map: GigaMap[EventId, ActivityEventRow] = gm
    )

  val agentIssues: ZLayer[EclipseStoreService, GigaMapError, AgentIssuesStore] =
    GigaMap.make(agentIssuesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[Long, AgentIssueRow]) =>
      new AgentIssuesStore:
        override val map: GigaMap[Long, AgentIssueRow] = gm
    )

  val agentAssignments: ZLayer[EclipseStoreService, GigaMapError, AgentAssignmentsStore] =
    GigaMap.make(agentAssignmentsDefinition) >>> ZLayer.fromFunction((gm: GigaMap[Long, AgentAssignmentRow]) =>
      new AgentAssignmentsStore:
        override val map: GigaMap[Long, AgentAssignmentRow] = gm
    )

  val memoryEntries: ZLayer[EclipseStoreService, GigaMapError, MemoryEntriesStore] =
    GigaMap.make(memoryEntriesDefinition) >>> ZLayer.fromFunction((gm: GigaMap[MemoryId, MemoryEntry]) =>
      new MemoryEntriesStore:
        override val map: GigaMap[MemoryId, MemoryEntry] = gm
    )

  private val mapsLive: ZLayer[
    EclipseStoreService,
    GigaMapError,
    DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
      SessionContextsStore & ActivityEventsStore & AgentIssuesStore & AgentAssignmentsStore & MemoryEntriesStore,
  ] =
    ZLayer.makeSome[
      EclipseStoreService,
      DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
        SessionContextsStore & ActivityEventsStore & AgentIssuesStore & AgentAssignmentsStore & MemoryEntriesStore,
    ](
      dataStore,
      taskRuns,
      taskReports,
      taskArtifacts,
      conversations,
      messages,
      sessionContexts,
      activityEvents,
      agentIssues,
      agentAssignments,
      memoryEntries,
    )

  val live: ZLayer[
    StoreConfig,
    EclipseStoreError | GigaMapError,
    DataStoreService & TaskRunsStore & TaskReportsStore & TaskArtifactsStore & ConversationsStore & MessagesStore &
      SessionContextsStore & ActivityEventsStore & AgentIssuesStore & AgentAssignmentsStore & MemoryEntriesStore &
      VectorIndexService,
  ] =
    baseStore >>> (mapsLive ++ VectorIndexService.live)
