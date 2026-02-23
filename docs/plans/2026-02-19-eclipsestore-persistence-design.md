# EclipseStore Dual-Store Persistence Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the SQLite/JDBC persistence layer with a dual-store EclipseStore architecture — one store for operational data (tasks, chat, activity, memory/vectors) and one for configuration (settings, workflows, custom agents) — while adding a new `MemoryRepository` with vector search for persistent conversation memory (issue #269).

**Architecture:** Two named `EclipseStoreService` instances backed by separate directories (`data/config-store/` and `data/data-store/`). Each domain gets a typed `GigaMap[K, V]` with field indexes. Repository traits remain unchanged; only `live` implementations swap from JDBC to GigaMap. A new `MemoryRepository` backed by `GigaMap[MemoryId, MemoryEntry]` + `VectorIndexService` closes issue #269.

**Tech Stack:** `zio-eclipsestore 2.0.0`, `zio-eclipsestore-gigamap 2.0.0`, EclipseStore 4.0.0-beta1, ZIO 2.1.24, Scala 3.5.2.

---

## Store Split

```
data/
  config-store/    ← StoreTag.Config  — settings, workflows, custom agents
  data-store/      ← StoreTag.Data    — tasks, reports, artifacts, chat, activity, memory
```

Config store is small, stable and survives data resets.
Wiping `data/data-store/` gives a clean operational slate without losing configuration.

## ZLayer Tags

```scala
object StoreTag:
  trait Config
  trait Data

type ConfigStore = EclipseStoreService @@ StoreTag.Config
type DataStore   = EclipseStoreService @@ StoreTag.Data
```

## GigaMap Inventory

| Map | Key type | Store | Indexes |
|-----|----------|-------|---------|
| `settings` | `String` | Config | — |
| `workflows` | `String` (UUID) | Config | name |
| `customAgents` | `String` (UUID) | Config | name, enabled |
| `taskRuns` | `String` (UUID) | Data | status, createdAt |
| `taskReports` | `String` (UUID) | Data | taskRunId, stepName |
| `taskArtifacts` | `String` (UUID) | Data | taskRunId |
| `conversations` | `String` (UUID) | Data | channelName, status |
| `messages` | `String` (UUID) | Data | conversationId, createdAt |
| `sessionContexts` | `String` (channelName:sessionKey) | Data | — |
| `activityEvents` | `String` (UUID) | Data | eventType, createdAt |
| `memoryEntries` | `String` (UUID) | Data | userId, kind |
| vector index | — | Data | embedding (cosine, dim=1536) |

## ID Strategy

All IDs switch from SQL `AUTOINCREMENT Long` to `java.util.UUID.randomUUID().toString`.
Existing `Long` IDs in model types change to `String`. Opaque type aliases enforce type-safety.

```scala
opaque type TaskRunId    = String
opaque type WorkflowId   = String
opaque type ConvId       = String
opaque type MessageId    = String
opaque type MemoryId     = String
```

## Memory Pipeline (issue #269)

1. Agent output includes `memory.*` artifact keys → `MemoryRepository.save()` called
2. `EmbeddingService.embed(text)` calls LLM provider embedding endpoint
3. `MemoryEntry(id, userId, text, embedding, tags, kind, createdAt)` stored in GigaMap
4. At conversation start: `MemoryRepository.searchRelevant(userId, query, limit=5)` via `GigaMapQuery.VectorSimilarity`
5. Top-N memories prepended as system context injection
6. Periodic summarization after N messages (configurable)

## Files to Create

```
src/main/scala/store/
  StoreConfig.scala          — StoreConfig case class, StoreTag, type aliases
  ConfigStoreModule.scala    — ZLayer for config store + all config GigaMaps
  DataStoreModule.scala      — ZLayer for data store + all data GigaMaps

src/main/scala/db/
  TaskRepositoryES.scala     — EclipseStore-backed TaskRepository live impl
  ChatRepositoryES.scala     — EclipseStore-backed ChatRepository live impl
  ActivityRepositoryES.scala — EclipseStore-backed ActivityRepository live impl
  ConfigRepositoryES.scala   — new: settings/workflows/agents via config store

src/main/scala/memory/
  MemoryModels.scala         — MemoryEntry, MemoryKind, MemoryFilter, MemoryId
  MemoryRepository.scala     — trait + EclipseStore live impl
  EmbeddingService.scala     — trait + LlmService-backed live impl
```

## Files to Delete (after replacement)

```
src/main/scala/db/Database.scala
src/main/scala/db/Schema.scala
src/main/resources/db/V*.sql   (all migration files)
```

## Files to Modify

```
build.sbt                          — remove sqlite-jdbc, add zio-eclipsestore deps
src/main/scala/di/ApplicationDI.scala — swap DataSource/Database layers for ES stores
src/main/scala/Main.scala          — replace DatabaseConfig with StoreConfig
src/main/scala/models/StateModels.scala — change Long IDs to String
src/main/scala/models/ChatModels.scala  — change Long IDs to String
src/main/scala/models/WorkflowModels.scala — change Long IDs to String
src/main/scala/config/SettingsApplier.scala — read from ConfigRepository instead of TaskRepository
src/main/scala/web/controllers/SettingsController.scala — use ConfigRepository
```
