# ADT Event-Sourced Persistence with BCE Package Structure

**Date:** 2026-02-23
**Status:** Completed

## Problem

After migrating from SQLite to EclipseStore, the persistence layer retains relational-era patterns:
- **Row types** with flat Option bags (e.g., `AgentIssueRow` has 10+ optional fields)
- **~50 bidirectional ID conversions** (Long <-> String) with unsafe `getOrElse(0L)` sentinels
- **~20 enum-to-string round-trips** with silent fallbacks on corrupted data
- **Duplicate model layers** — domain models mirror row types, repositories manually map between them
- **Technical package layout** (`db/`, `models/`, `store/`) instead of domain-driven structure

EclipseStore stores objects natively — it doesn't need flat, nullable row types.

## Design Decisions

1. **Unified domain ADTs** — eliminate Row types; store domain objects directly in EclipseStore
2. **Event sourcing for core aggregates** (TaskRun, Issue, Conversation) — state derived from event replay
3. **Direct state storage for config** (Settings, Workflows, CustomAgents)
4. **Opaque String IDs** everywhere — standardize on MemoryId pattern, drop Long IDs
5. **BCE package structure** — reorganize by business component, not technical layer

## BCE Package Structure

Current (technical):
```
src/main/scala/
  db/                    # repository traits + ES implementations
  models/                # domain models (ChatModels, ActivityEvent)
  store/                 # EclipseStore modules (DataStore, ConfigStore, MemoryStore)
  web/controllers/       # HTTP controllers
  web/views/             # HTML views
  gateway/               # Telegram integration
  ...
```

Proposed (domain-driven BCE):
```
src/main/scala/
  taskrun/               # Task execution lifecycle
    boundary/            # TaskRunController, TaskRunRoutes
    control/             # TaskRunService (orchestrates events)
    entity/              # TaskRun, TaskRunState, TaskRunEvent, TaskRunRepository

  conversation/          # Chat/conversation management
    boundary/            # ChatController, ChatRoutes, WebSocketHandler
    control/             # ConversationService, MessageService
    entity/              # Conversation, ConversationState, ConversationEvent, ...

  issues/                # Agent issue tracking
    boundary/            # IssueController, IssueRoutes
    control/             # IssueService, AssignmentService
    entity/              # AgentIssue, IssueState, IssueEvent, ...

  config/                # Settings, workflows, custom agents (direct state, no events)
    boundary/            # ConfigController, ConfigRoutes
    control/             # ConfigService
    entity/              # Setting, Workflow, CustomAgent, ConfigRepository

  activity/              # Activity event log (append-only, cross-cutting)
    boundary/            # ActivityController
    control/             # ActivityService
    entity/              # ActivityEvent, ActivityEventType, ActivityRepository

  memory/                # Vector embeddings, semantic memory (already well-isolated)
    boundary/            # MemoryController
    control/             # MemoryService
    entity/              # MemoryEntry, MemoryModels, VectorIndex

  gateway/               # External integrations (Telegram, etc.)
    boundary/            # TelegramBot, TelegramRoutes
    control/             # GatewayRouter, IntentParser
    entity/              # GatewayModels

  orchestration/         # Cross-component workflow coordination
    control/             # OrchestrationService, AgentOrchestrator

  shared/                # Cross-cutting infrastructure
    store/               # EclipseStore modules (DataStore, ConfigStore, MemoryStore)
    ids/                 # Opaque ID types (TaskRunId, ConversationId, etc.)
    errors/              # PersistenceError, DomainError
    web/                 # Layout, shared views, static assets
```

### BCE Dependency Rules

- **B -> C -> E**: Boundaries delegate to controls; controls manipulate entities
- **B -> E**: Boundaries can access entities directly for simple reads
- **E -> C -> B**: Only via events (cross-component communication)
- **Cross-component**: Only through boundary or control layers, never direct entity access

## Opaque ID Types

Standardize all IDs on the MemoryId pattern:

```scala
// shared/ids/Ids.scala
object Ids:
  opaque type TaskRunId = String
  object TaskRunId:
    def apply(s: String): TaskRunId = s
    def generate: TaskRunId = java.util.UUID.randomUUID().toString
    extension (id: TaskRunId) def value: String = id

  opaque type ConversationId = String
  // ... same pattern for all entity IDs
```

This eliminates all Long<->String conversion code.

## Domain ADTs (replacing Option bags)

### TaskRun State

```scala
// taskrun/entity/TaskRun.scala
enum TaskRunState:
  case Pending(createdAt: Instant)
  case Running(startedAt: Instant, currentPhase: String)
  case Completed(startedAt: Instant, completedAt: Instant, summary: String)
  case Failed(startedAt: Instant, failedAt: Instant, error: String)
  case Cancelled(cancelledAt: Instant, reason: String)

case class TaskRun(
  id: TaskRunId,
  workflowId: WorkflowId,
  state: TaskRunState,
  agentName: String,
  source: String,
  reports: List[TaskReport],
  artifacts: List[TaskArtifact],
)
```

No more: `completedAt: Option[Instant]`, `errorMessage: Option[String]`, `currentPhase: Option[String]`.

### Issue State

```scala
enum IssueState:
  case Open(createdAt: Instant)
  case Assigned(agent: AgentId, assignedAt: Instant)
  case InProgress(agent: AgentId, startedAt: Instant)
  case Completed(agent: AgentId, completedAt: Instant, result: String)
  case Failed(agent: AgentId, failedAt: Instant, error: String)
  case Skipped(skippedAt: Instant, reason: String)

case class AgentIssue(
  id: IssueId,
  title: String,
  description: String,
  priority: IssuePriority,
  state: IssueState,
  tags: List[String],       // not Option[String] JSON blob
  contextPath: String,
  sourceFolder: String,
)
```

### Conversation State

```scala
enum ConversationState:
  case Active(startedAt: Instant)
  case Closed(startedAt: Instant, closedAt: Instant)

case class Conversation(
  id: ConversationId,
  channel: ChannelInfo,     // ADT instead of Option[String]
  state: ConversationState,
  description: String,      // empty string instead of Option
  messages: List[Message],
)

// Channel as ADT instead of Option[String]
enum ChannelInfo:
  case Telegram(channelName: String)
  case Web(sessionId: String)
  case Internal
```

## Event Sourcing (Core Aggregates)

### Event Types

```scala
// taskrun/entity/TaskRunEvent.scala
sealed trait TaskRunEvent:
  def runId: TaskRunId
  def occurredAt: Instant

object TaskRunEvent:
  case class Created(runId: TaskRunId, workflowId: WorkflowId, agentName: String,
                     source: String, occurredAt: Instant) extends TaskRunEvent
  case class Started(runId: TaskRunId, phase: String, occurredAt: Instant) extends TaskRunEvent
  case class PhaseChanged(runId: TaskRunId, phase: String, occurredAt: Instant) extends TaskRunEvent
  case class ReportAdded(runId: TaskRunId, report: TaskReport, occurredAt: Instant) extends TaskRunEvent
  case class ArtifactAdded(runId: TaskRunId, artifact: TaskArtifact, occurredAt: Instant) extends TaskRunEvent
  case class Completed(runId: TaskRunId, summary: String, occurredAt: Instant) extends TaskRunEvent
  case class Failed(runId: TaskRunId, error: String, occurredAt: Instant) extends TaskRunEvent
```

### Event Store Algebra

```scala
// shared/store/EventStore.scala
trait EventStore[Id, E]:
  def append(id: Id, event: E): IO[PersistenceError, Unit]
  def events(id: Id): IO[PersistenceError, List[E]]
  def eventsSince(id: Id, sequence: Long): IO[PersistenceError, List[E]]

// taskrun/entity/TaskRunRepository.scala
trait TaskRunRepository:
  def append(event: TaskRunEvent): IO[PersistenceError, Unit]
  def get(id: TaskRunId): IO[PersistenceError, TaskRun]     // replay events -> state
  def list(filter: TaskRunFilter): IO[PersistenceError, List[TaskRun]]
```

### Storage Layout in EclipseStore

```
events:taskrun:{id}:{sequence}  -> TaskRunEvent (append-only)
snapshot:taskrun:{id}           -> TaskRun (materialized for fast reads)
```

Snapshots are rebuilt periodically or on-demand. The event log is the source of truth.

### Direct State (Config)

```scala
// config/entity/ConfigRepository.scala
trait ConfigRepository:
  def getSetting(key: String): IO[PersistenceError, Setting]
  def putSetting(setting: Setting): IO[PersistenceError, Unit]
  def listWorkflows: IO[PersistenceError, List[Workflow]]
  def saveWorkflow(workflow: Workflow): IO[PersistenceError, Unit]
  def listAgents: IO[PersistenceError, List[CustomAgent]]
  def saveAgent(agent: CustomAgent): IO[PersistenceError, Unit]
```

No events, no replay — direct CRUD on EclipseStore.

## What Gets Deleted

| Current Code | Action |
|---|---|
| `DataStoreModule` row types (lines 14-96) | Delete |
| `ConfigStoreModule` row types (lines 14-38) | Delete |
| `TaskRepositoryES` mapping (lines 303-428) | Delete |
| `ChatRepositoryES` mapping (lines 291-414) | Delete |
| `ActivityRepositoryES` mapping (lines 73-97) | Delete |
| `extractLongField` manual JSON parser | Delete |
| All `toLongOption.getOrElse(0L)` (~50 sites) | Delete |
| All `Enum.values.find(...).getOrElse(...)` (~20 sites) | Delete |
| `db/Schema.scala` (old SQL schema) | Delete |

## Migration Strategy

1. Add new domain ADTs alongside existing code
2. Write one-time migration: read old Row format, emit events, store new ADTs
3. Keep `SchemaBinaryCodec` custom handlers for backward-compatible deserialization
4. Switch repositories to new implementations
5. Remove old Row types and mapping code
6. Restructure packages to BCE layout

## Implementation Phases

### Phase 1: Foundation
- Create `shared/ids/` with all opaque ID types
- Create `shared/store/EventStore` trait
- Define domain ADTs for TaskRun, Issue, Conversation states

### Phase 2: Event-Sourced TaskRun
- Implement `TaskRunEvent` types
- Implement `TaskRunRepository` with event sourcing
- Migrate existing `TaskRepositoryES` to new pattern
- Write data migration

### Phase 3: Event-Sourced Issues & Conversations
- Implement events and repositories for Issues and Conversations
- Migrate `ChatRepositoryES` to new pattern
- Write data migrations

### Phase 4: Config (Direct State)
- Simplify ConfigRepository — store domain objects directly
- Eliminate Row types and mapping

### Phase 5: Activity Log Alignment
- Refactor ActivityEvent to use new ID types
- Align with EventStore pattern (already close)

### Phase 6: BCE Package Restructure
- Move files to domain-driven BCE packages
- Update imports across codebase
- Verify compilation and tests

### Phase 7: Cleanup
- Delete old Row types, mapping code, Schema.scala
- Remove backward-compatibility code after migration verification
