# Legacy Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all orphaned files, legacy patterns, and backward-compatibility shims identified in milestone `legacy-removal` (#47).

**Architecture:** Six sequential cleanup tasks ordered by risk and impact: (1) delete truly orphaned Scalatags views, (2) delete orphaned JS web components, (3) remove legacy UI component aliases, (4) remove startup purge layers, (5) remove legacy issue status shims, (6) migrate `db/` package to domain packages.

**Tech Stack:** Scala 3, ZIO 2, Scalatags, Lit 3, sbt, GitHub CLI (`gh`)

---

## Scope corrections vs. issue descriptions

Investigation revealed two items listed in issue #658 are NOT orphaned:
- `ComponentsCatalogView.scala` — referenced in `WebServer.scala:26` (dev `/components` route)
- `ProofOfWorkView.scala` — referenced in `IssuesView.scala:767` and `CheckpointsView.scala:125`

These two files are **kept**. Only `DashboardView.scala` and `AgentRegistryView.scala` are deleted.

---

## File Map

### DELETE
| File | Task | Reason |
|------|------|--------|
| `src/main/scala/shared/web/DashboardView.scala` | 1 | No references in any controller |
| `src/main/scala/shared/web/AgentRegistryView.scala` | 1 | Superseded by unified AgentsView (#477) |
| `src/main/resources/static/client/components/chat-message-stream.js` | 2 | Superseded by `ab-chat-stream.js` |
| `src/main/resources/static/client/components/ab-agent-monitor.js` | 2 | Superseded by `agent-monitor.js` |
| `src/main/scala/db/TaskRepository.scala` | 6 | Moved to `taskrun/control/` |
| `src/main/scala/db/TaskRepositoryLive.scala` | 6 | Moved to `taskrun/control/` |
| `src/main/scala/db/ChatRepository.scala` | 6 | Moved to `conversation/control/` |
| `src/main/scala/db/ChatRepositoryLive.scala` | 6 | Moved to `conversation/control/` |
| `src/main/scala/db/ConfigRepository.scala` | 6 | Implementation inlined into `config/entity/ConfigRepositoryES.scala` |
| `src/main/scala/db/CompatTypes.scala` | 6 | Re-exports replaced with direct imports |
| `src/main/scala/db/LegacyTypes.scala` | 6 | Unused `DatabaseConfig` class |

### MODIFY
| File | Task |
|------|------|
| `src/main/scala/shared/web/Components.scala` | 3 |
| `src/main/scala/shared/web/TasksView.scala` | 3 |
| `src/main/scala/shared/web/GraphView.scala` | 3 |
| `src/main/scala/shared/web/ChannelView.scala` | 3 |
| `src/main/scala/shared/web/ReportsView.scala` | 3 |
| `src/main/scala/app/ApplicationDI.scala` | 4, 6 |
| `src/main/scala/workspace/entity/WorkspaceRepository.scala` | 4 |
| `src/main/scala/issues/entity/api/IssueApiModels.scala` | 5 |
| `src/main/scala/issues/boundary/IssueController.scala` | 5 |
| `src/main/scala/orchestration/control/PlannerAgentService.scala` | 5 |
| `src/main/scala/config/entity/ConfigRepositoryES.scala` | 6 |
| 37 files importing from `db.*` | 6 |

### CREATE
| File | Task |
|------|------|
| `src/main/scala/taskrun/control/TaskRepository.scala` | 6 |
| `src/main/scala/taskrun/control/TaskRepositoryLive.scala` | 6 |
| `src/main/scala/conversation/control/ChatRepository.scala` | 6 |
| `src/main/scala/conversation/control/ChatRepositoryLive.scala` | 6 |

---

## Task 1: Delete orphaned Scalatags views (Issue #658)

**Files:**
- Delete: `src/main/scala/shared/web/DashboardView.scala`
- Delete: `src/main/scala/shared/web/AgentRegistryView.scala`

- [ ] **Step 1: Verify zero references**

```bash
grep -r "DashboardView\|AgentRegistryView" src/main/scala/
```

Expected: no output (both files are orphaned).

- [ ] **Step 2: Delete the files**

```bash
rm src/main/scala/shared/web/DashboardView.scala
rm src/main/scala/shared/web/AgentRegistryView.scala
```

- [ ] **Step 3: Compile**

```bash
sbt compile
```

Expected: success with no errors.

- [ ] **Step 4: Commit and close issue #658**

```bash
git add -u src/main/scala/shared/web/DashboardView.scala src/main/scala/shared/web/AgentRegistryView.scala
git commit -m "$(cat <<'EOF'
remove orphaned Scalatags view files

Deletes DashboardView.scala (no references) and AgentRegistryView.scala
(superseded by unified AgentsView in #477). ComponentsCatalogView and
ProofOfWorkView are retained as they have active references.

Closes #658
EOF
)"
```

---

## Task 2: Delete orphaned JS web components (Issue #659)

**Files:**
- Delete: `src/main/resources/static/client/components/chat-message-stream.js`
- Delete: `src/main/resources/static/client/components/ab-agent-monitor.js`

- [ ] **Step 1: Verify zero references**

```bash
grep -r "chat-message-stream\|ab-agent-monitor" src/
```

Expected: no output.

- [ ] **Step 2: Delete the files**

```bash
rm src/main/resources/static/client/components/chat-message-stream.js
rm src/main/resources/static/client/components/ab-agent-monitor.js
```

- [ ] **Step 3: Compile**

```bash
sbt compile
```

Expected: success.

- [ ] **Step 4: Commit and close issue #659**

```bash
git add -u src/main/resources/static/client/components/chat-message-stream.js \
          src/main/resources/static/client/components/ab-agent-monitor.js
git commit -m "$(cat <<'EOF'
remove orphaned frontend JS web components

Deletes chat-message-stream.js (superseded by ab-chat-stream.js) and
ab-agent-monitor.js (superseded by agent-monitor.js). Both files had
zero references in any view or script tag.

Closes #659
EOF
)"
```

---

## Task 3: Remove legacy UI component aliases (Issue #661)

**Files:**
- Modify: `src/main/scala/shared/web/Components.scala` (remove 2 methods)
- Modify: `src/main/scala/shared/web/TasksView.scala` (2 callsites)
- Modify: `src/main/scala/shared/web/GraphView.scala` (1 callsite)
- Modify: `src/main/scala/shared/web/ChannelView.scala` (1 callsite)
- Modify: `src/main/scala/shared/web/ReportsView.scala` (1 callsite)

Note: `DashboardView.scala` also had a callsite but was deleted in Task 1.

- [ ] **Step 1: Remove `loadingSpinner` from Components.scala**

In `src/main/scala/shared/web/Components.scala`, delete lines 50–54:

```scala
  /** Legacy alias used by views (wraps in htmx-indicator div for compatibility). */
  def loadingSpinner: Frag =
    div(cls := "htmx-indicator flex justify-center items-center p-4")(
      spinner()
    )
```

The `spinner()` method on line 47–48 stays.

- [ ] **Step 2: Remove `emptyState` from Components.scala**

In `src/main/scala/shared/web/Components.scala`, delete lines 95–99:

```scala
  /** Legacy single-message empty state (plain Scalatags, no web component). */
  def emptyState(message: String): Frag =
    tag("ab-empty-state")(
      attr("headline") := message
    )
```

The `emptyStateFull()` method on lines 101–108 stays.

- [ ] **Step 3: Migrate callsites in TasksView.scala**

`src/main/scala/shared/web/TasksView.scala:40` — replace:
```scala
if tasks.isEmpty then Components.emptyState("No tasks yet. Create one from the form.")
```
with:
```scala
if tasks.isEmpty then Components.emptyStateFull("No tasks yet. Create one from the form.")
```

`src/main/scala/shared/web/TasksView.scala:105` — replace:
```scala
if task.steps.isEmpty then Components.emptyState("No workflow steps available for this task.")
```
with:
```scala
if task.steps.isEmpty then Components.emptyStateFull("No workflow steps available for this task.")
```

- [ ] **Step 4: Migrate callsite in GraphView.scala**

`src/main/scala/shared/web/GraphView.scala:29` — replace:
```scala
if graphReports.isEmpty then Components.emptyState("No graph reports available for this task.")
```
with:
```scala
if graphReports.isEmpty then Components.emptyStateFull("No graph reports available for this task.")
```

- [ ] **Step 5: Migrate callsite in ChannelView.scala**

`src/main/scala/shared/web/ChannelView.scala:106` — replace:
```scala
if cards.isEmpty then Components.emptyState("No channels registered.")
```
with:
```scala
if cards.isEmpty then Components.emptyStateFull("No channels registered.")
```

- [ ] **Step 6: Migrate callsite in ReportsView.scala**

`src/main/scala/shared/web/ReportsView.scala:32` — replace:
```scala
if reports.isEmpty then Components.emptyState("No reports found for this task.")
```
with:
```scala
if reports.isEmpty then Components.emptyStateFull("No reports found for this task.")
```

- [ ] **Step 7: Verify no remaining callsites**

```bash
grep -rn "loadingSpinner\|Components\.emptyState[^F]" src/main/scala/
```

Expected: no output.

- [ ] **Step 8: Compile and test**

```bash
sbt compile && sbt test
```

Expected: all tests pass.

- [ ] **Step 9: Commit and close issue #661**

```bash
git add src/main/scala/shared/web/Components.scala \
        src/main/scala/shared/web/TasksView.scala \
        src/main/scala/shared/web/GraphView.scala \
        src/main/scala/shared/web/ChannelView.scala \
        src/main/scala/shared/web/ReportsView.scala
git commit -m "$(cat <<'EOF'
remove legacy UI component aliases in Components.scala

Deletes loadingSpinner() (zero callsites) and emptyState() (superseded
by emptyStateFull()). Migrates 5 callsites to emptyStateFull(), which
accepts the same headline string as first parameter.

Closes #661
EOF
)"
```

---

## Task 4: Remove legacy startup data purge layers (Issue #663)

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`
- Modify: `src/main/scala/workspace/entity/WorkspaceRepository.scala`

- [ ] **Step 1: Remove `purgeLegacyIssueDataLayer` wiring from ApplicationDI.scala**

In `src/main/scala/app/ApplicationDI.scala` line 277, delete:
```scala
      purgeLegacyIssueDataLayer,
```

- [ ] **Step 2: Remove `purgeLegacyIssueDataLayer` definition from ApplicationDI.scala**

Delete lines 365–389 (the full `private val purgeLegacyIssueDataLayer` definition):

```scala
  private val purgeLegacyIssueDataLayer: ZLayer[DataStoreModule.DataStoreService, Nothing, Unit] =
    ZLayer.fromZIO {
      for
        dataStore <- ZIO.service[DataStoreModule.DataStoreService]
        keys      <- dataStore.rawStore
                       .streamKeys[String]
                       .filter(key => key.startsWith("snapshot:issue:") || key.startsWith("events:issue:"))
                       .runCollect
                       .catchAll(_ => ZIO.succeed(Chunk.empty[String]))
        _         <- ZIO
                       .foreachDiscard(keys)(key => dataStore.remove[String](key).ignore)
                       .when(keys.nonEmpty)
        _         <- dataStore.rawStore
                       .maintenance(LifecycleCommand.Checkpoint)
                       .flatMap {
                         case LifecycleStatus.Failed(message) =>
                           ZIO.logWarning(s"Legacy issue data purge checkpoint failed: $message")
                         case _                               =>
                           ZIO.unit
                       }
                       .catchAll(err => ZIO.logWarning(s"Legacy issue data purge checkpoint failed: ${err.toString}"))
                       .when(keys.nonEmpty)
        _         <- ZIO.logInfo(s"Purged ${keys.size} legacy IssueRepositoryES key(s) from data store").when(keys.nonEmpty)
      yield ()
    }
```

After deleting, also check if any types used only by this val (`LifecycleCommand`, `LifecycleStatus`, `Chunk`) need their imports cleaned up.

- [ ] **Step 3: Remove `purgeSnapshotsAndLegacyKeys` call from WorkspaceRepository.scala**

In `src/main/scala/workspace/entity/WorkspaceRepository.scala`, remove line 31:
```scala
        _   <- repo.purgeSnapshotsAndLegacyKeys.ignoreLogged
```

The `live` ZLayer should become:
```scala
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, WorkspaceRepository] =
    ZLayer.fromZIO(
      for
        svc <- ZIO.service[DataStoreModule.DataStoreService]
        repo = WorkspaceRepositoryES(svc)
      yield repo
    )
```

- [ ] **Step 4: Remove `purgeSnapshotsAndLegacyKeys` method from WorkspaceRepository.scala**

Delete lines 214–240 (the full `private[entity] def purgeSnapshotsAndLegacyKeys` method):

```scala
  private[entity] def purgeSnapshotsAndLegacyKeys: IO[PersistenceError, Unit] =
    for
      allKeys <- dataStore.rawStore
                   .streamKeys[String]
                   .runCollect
                   .mapError(storeErr("purgeSnapshotsAndLegacyKeys"))
      toRemove = allKeys.filter { k =>
                   val isLegacy   =
                     (k.startsWith("workspace:") && !k.startsWith("workspace-run:") &&
                       !k.startsWith("workspace-events:")) ||
                     (k.startsWith("workspace-run:") && !k.startsWith("workspace-run-events:"))
                   val isSnapshot =
                     k.startsWith("snapshot:workspace:") || k.startsWith("snapshot:workspace-run:")
                   isLegacy || isSnapshot
                 }
      _       <- ZIO.when(toRemove.nonEmpty)(
                   ZIO.logWarning(s"Purging ${toRemove.size} legacy/snapshot workspace keys from store") *>
                     ZIO.foreachDiscard(toRemove)(key =>
                       dataStore.remove[String](key).mapError(storeErr("purgeSnapshotsAndLegacyKeys"))
                     )
                 )
    yield ()
```

- [ ] **Step 5: Compile and test**

```bash
sbt compile && sbt test
```

Expected: all tests pass.

- [ ] **Step 6: Commit and close issue #663**

```bash
git add src/main/scala/app/ApplicationDI.scala \
        src/main/scala/workspace/entity/WorkspaceRepository.scala
git commit -m "$(cat <<'EOF'
remove legacy startup data purge layers

Removes purgeLegacyIssueDataLayer from ApplicationDI (cleaned up
snapshot:issue:* and events:issue:* keys from pre-event-sourcing era)
and purgeSnapshotsAndLegacyKeys from WorkspaceRepository (cleaned up
workspace snapshot keys). Both are one-time migrations that became
no-ops after first run.

Closes #663
EOF
)"
```

---

## Task 5: Remove legacy issue status shims (Issue #662)

**Files:**
- Modify: `src/main/scala/issues/entity/api/IssueApiModels.scala`
- Modify: `src/main/scala/issues/boundary/IssueController.scala`
- Modify: `src/main/scala/orchestration/control/PlannerAgentService.scala`

- [ ] **Step 1: Remove legacy status enum values from IssueApiModels.scala**

In `src/main/scala/issues/entity/api/IssueApiModels.scala`, remove lines 13–14:
```scala
  // Legacy values kept for backward compatibility with existing data and clients.
  case Open, Assigned, Completed, Failed, Skipped
```

The enum should become:
```scala
enum IssueStatus derives JsonCodec, Schema:
  case Backlog, Todo, InProgress, HumanReview, Rework, Merging, Done, Canceled, Duplicated, Archived
```

- [ ] **Step 2: Compile to find any remaining references**

```bash
sbt compile
```

If compilation fails with references to `IssueStatus.Open`, `IssueStatus.Assigned`, `IssueStatus.Completed`, `IssueStatus.Failed`, or `IssueStatus.Skipped` — fix each reference by mapping to the canonical equivalent:
- `Open` → `Backlog`
- `Assigned` → `Todo`
- `Completed` → `Done`
- `Failed` → `Canceled`
- `Skipped` → `Canceled`

- [ ] **Step 3: Inline `appendLegacyStartedEvent` in IssueController.scala**

In `src/main/scala/issues/boundary/IssueController.scala`, find `assignWorkspaceRunAndMarkStarted` (around line 2258). Replace the local `val appendLegacyStartedEvent` + its call with the inlined for-comprehension:

Before (lines 2263–2298):
```scala
    val appendLegacyStartedEvent =
      for
        now <- Clock.instant
        _   <- issueRepository
                 .append(
                   IssueEvent.Started(
                     issueId = issue.id,
                     agent = AgentId(agentName),
                     startedAt = now,
                     occurredAt = now,
                   )
                 )
                 .mapError(mapIssueRepoError)
      yield ()

    for
      run <- workspaceRunService
               .assign(...)
               .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
      _   <- issue.workspaceId match
               case Some(_) =>
                 markWorkspaceBoardIssueStarted(...)
               case None    =>
                 appendLegacyStartedEvent
    yield ()
```

After — remove the named `val` and inline the body into the `None` arm:
```scala
    for
      run <- workspaceRunService
               .assign(
                 workspaceId,
                 AssignRunRequest(
                   issueRef = s"#${issue.id.value}",
                   prompt = executionPrompt(issue),
                   agentName = agentName,
                 ),
               )
               .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
      _   <- issue.workspaceId match
               case Some(_) =>
                 markWorkspaceBoardIssueStarted(
                   issueId = issue.id.value,
                   workspaceId = workspaceId,
                   agentName = agentName,
                   branchName = run.branchName,
                 )
               case None    =>
                 for
                   now <- Clock.instant
                   _   <- issueRepository
                            .append(
                              IssueEvent.Started(
                                issueId = issue.id,
                                agent = AgentId(agentName),
                                startedAt = now,
                                occurredAt = now,
                              )
                            )
                            .mapError(mapIssueRepoError)
                 yield ()
    yield ()
```

- [ ] **Step 4: Rename `createLegacyIssues` to `createStandaloneIssues` in PlannerAgentService.scala**

`createLegacyIssues` is still the active code path for plans without a workspace. Rename it to reflect its real purpose rather than deleting it.

In `src/main/scala/orchestration/control/PlannerAgentService.scala`:
1. Rename method definition `private def createLegacyIssues(` → `private def createStandaloneIssues(`
2. Update the call site at line ~483: `createLegacyIssues(` → `createStandaloneIssues(`

- [ ] **Step 5: Compile and run all tests**

```bash
sbt compile && sbt test && sbt it:test
```

Expected: all tests pass.

- [ ] **Step 6: Commit and close issue #662**

```bash
git add src/main/scala/issues/entity/api/IssueApiModels.scala \
        src/main/scala/issues/boundary/IssueController.scala \
        src/main/scala/orchestration/control/PlannerAgentService.scala
git commit -m "$(cat <<'EOF'
remove legacy issue status backward-compatibility shims

Removes Open/Assigned/Completed/Failed/Skipped from IssueStatus enum.
Inlines appendLegacyStartedEvent local val in IssueController (no
behavioral change, just removes the misleading name). Renames
createLegacyIssues to createStandaloneIssues to reflect its real
purpose (creating issues for workspace-less plans).

Closes #662
EOF
)"
```

---

## Task 6: Migrate and remove legacy `db/` package (Issue #660)

**Key insight from exploration:**
- `db/CompatTypes.scala` re-exports from actual homes: `taskrun.entity.{RunStatus, TaskArtifactRow, TaskReportRow, TaskRunRow}`, `config.entity.{CustomAgentRow, SettingRow, WorkflowRow}`, `shared.web.FileType`
- `db/TaskRepository.scala` and `db/ChatRepository.scala` contain the trait + companion object definitions
- `config/entity/ConfigRepositoryES.scala` is a thin wrapper around `db.ConfigRepositoryES` — the implementation stays in `db/ConfigRepository.scala`

**Migration strategy:**
- Move trait files to domain `control/` packages (change `package db` → `package taskrun.control` / `package conversation.control`)
- For `ConfigRepositoryES`: inline `db.ConfigRepositoryES` implementation directly into `config/entity/ConfigRepositoryES.scala`
- Replace all `import db.*` / `import db.X` with imports from the actual source packages
- Delete `db/`

**Files:**
- Create: `src/main/scala/taskrun/control/TaskRepository.scala`
- Create: `src/main/scala/taskrun/control/TaskRepositoryLive.scala`
- Create: `src/main/scala/conversation/control/ChatRepository.scala`
- Create: `src/main/scala/conversation/control/ChatRepositoryLive.scala`
- Modify: `src/main/scala/config/entity/ConfigRepositoryES.scala`
- Modify: 37+ files importing from `db.*`
- Delete: `src/main/scala/db/` (all files)

- [ ] **Step 1: Create `taskrun/control/TaskRepository.scala`**

Copy `src/main/scala/db/TaskRepository.scala` to `src/main/scala/taskrun/control/TaskRepository.scala`, changing only the first line:

```scala
package taskrun.control
```

All imports and the trait/companion body remain identical.

- [ ] **Step 2: Copy `TaskRepositoryLive.scala` to `taskrun/control/`**

```bash
cp src/main/scala/db/TaskRepositoryLive.scala src/main/scala/taskrun/control/TaskRepositoryLive.scala
```

Open `src/main/scala/taskrun/control/TaskRepositoryLive.scala` and change the first line:
```scala
package taskrun.control
```

- [ ] **Step 3: Create `conversation/control/ChatRepository.scala`**

Copy `src/main/scala/db/ChatRepository.scala` to `src/main/scala/conversation/control/ChatRepository.scala`, changing only the first line:

```scala
package conversation.control
```

- [ ] **Step 4: Copy `ChatRepositoryLive.scala` to `conversation/control/`**

```bash
cp src/main/scala/db/ChatRepositoryLive.scala src/main/scala/conversation/control/ChatRepositoryLive.scala
```

Open `src/main/scala/conversation/control/ChatRepositoryLive.scala` and change the first line:
```scala
package conversation.control
```

- [ ] **Step 5: Inline `db.ConfigRepositoryES` into `config/entity/ConfigRepositoryES.scala`**

Replace the entire contents of `src/main/scala/config/entity/ConfigRepositoryES.scala` with the implementation below (removes the thin wrapper / delegation pattern, promotes the `db.ConfigRepositoryES` body directly into `config.entity`):

```scala
package config.entity

import java.time.Instant

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.store.ConfigStoreModule

final case class ConfigRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService
) extends ConfigRepository:

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent", "code-agent", "task-planner", "web-search-agent",
    "file-agent", "report-agent", "router-agent",
  )

  private def settingKey(key: String): String = s"setting:$key"
  private def workflowKey(id: Long): String   = s"workflow:$id"
  private def agentKey(id: Long): String      = s"agent:$id"

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      keys <- configStore.rawStore.streamKeys[String]
                .filter(_.startsWith("setting:")).runCollect.mapError(storeErr("getAllSettings"))
      rows <- ZIO.foreach(keys.toList)(k =>
                configStore.fetch[String, String](k).mapError(storeErr("getAllSettings"))
                  .map(_.map(raw => decodeSetting(k.stripPrefix("setting:"), raw)).toList))
    yield rows.flatten.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    configStore.fetch[String, String](settingKey(key)).mapError(storeErr("getSetting"))
      .map(_.map(value => decodeSetting(key, value)))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    configStore.store(settingKey(key), value).mapError(storeErr("upsertSetting")) *>
      checkpointConfigStore("upsertSetting")

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    configStore.remove[String](settingKey(key)).mapError(storeErr("deleteSetting")) *>
      checkpointConfigStore("deleteSetting")

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- configStore.rawStore.streamKeys[String]
                .filter(k => k.startsWith(s"setting:$prefix")).runCollect
                .mapError(storeErr("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.toList)(k =>
                configStore.remove[String](k).mapError(storeErr("deleteSettingsByPrefix")))
      _    <- checkpointConfigStore("deleteSettingsByPrefix")
    yield ()

  override def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] =
    getAllSettings.map(_.filter(_.key.startsWith("agent.binding.")).flatMap(row => parseBindingKey(row.key)))

  override def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    upsertSetting(bindingKey(binding), "true")

  override def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    deleteSetting(bindingKey(binding))

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- configStore.store(workflowKey(id), toStoreWorkflowRow(workflow, id))
              .mapError(storeErr("createWorkflow"))
    yield id

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    configStore.fetch[String, shared.store.WorkflowRow](workflowKey(id))
      .map(_.flatMap(fromStoreWorkflowRow)).mapError(storeErr("getWorkflow"))

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    fetchAllByPrefix[shared.store.WorkflowRow]("workflow:", "getWorkflowByName")
      .map(_.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    fetchAllByPrefix[shared.store.WorkflowRow]("workflow:", "listWorkflows")
      .map(_.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO.fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <- configStore.fetch[String, shared.store.WorkflowRow](workflowKey(id))
                    .mapError(storeErr("updateWorkflow"))
      _        <- ZIO.fail(PersistenceError.NotFound("workflows", id.toString)).when(existing.isEmpty)
      _        <- configStore.store(workflowKey(id), toStoreWorkflowRow(workflow, id))
                    .mapError(storeErr("updateWorkflow"))
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- configStore.fetch[String, shared.store.WorkflowRow](workflowKey(id))
                    .mapError(storeErr("deleteWorkflow"))
      _        <- ZIO.fail(PersistenceError.NotFound("workflows", id.toString)).when(existing.isEmpty)
      _        <- configStore.remove[String](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
    yield ()

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- configStore.store(agentKey(id), toStoreAgentRow(agent, id))
              .mapError(storeErr("createCustomAgent"))
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    configStore.fetch[String, shared.store.CustomAgentRow](agentKey(id))
      .map(_.flatMap(fromStoreAgentRow)).mapError(storeErr("getCustomAgent"))

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    fetchAllByPrefix[shared.store.CustomAgentRow]("agent:", "getCustomAgentByName")
      .map(_.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    fetchAllByPrefix[shared.store.CustomAgentRow]("agent:", "listCustomAgents")
      .map(_.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO.fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <- configStore.fetch[String, shared.store.CustomAgentRow](agentKey(id))
                    .mapError(storeErr("updateCustomAgent"))
      _        <- ZIO.fail(PersistenceError.NotFound("custom_agents", id.toString)).when(existing.isEmpty)
      _        <- configStore.store(agentKey(id), toStoreAgentRow(agent, id))
                    .mapError(storeErr("updateCustomAgent"))
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- configStore.fetch[String, shared.store.CustomAgentRow](agentKey(id))
                    .mapError(storeErr("deleteCustomAgent"))
      _        <- ZIO.fail(PersistenceError.NotFound("custom_agents", id.toString)).when(existing.isEmpty)
      _        <- configStore.remove[String](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
    yield ()

  private def bindingKey(binding: AgentChannelBinding): String =
    val base = s"agent.binding.${binding.agentId.value}.${binding.channelName.trim.toLowerCase}"
    binding.accountId.map(_.trim).filter(_.nonEmpty).map(id => s"$base.$id").getOrElse(base)

  private def parseBindingKey(key: String): Option[AgentChannelBinding] =
    key.stripPrefix("agent.binding.").split("\\.", -1).toList match
      case agentId :: channelName :: Nil                                   =>
        Some(AgentChannelBinding(AgentId(agentId), channelName, None))
      case agentId :: channelName :: accountParts if accountParts.nonEmpty =>
        val accountId = accountParts.mkString(".").trim
        Some(AgentChannelBinding(AgentId(agentId), channelName, Option.when(accountId.nonEmpty)(accountId)))
      case _ => None

  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name"))
    else ZIO.unit

  private def fetchAllByPrefix[V](prefix: String, op: String)(using zio.schema.Schema[V])
    : IO[PersistenceError, List[V]] =
    configStore.rawStore.streamKeys[String].filter(_.startsWith(prefix)).runCollect.mapError(storeErr(op))
      .flatMap(keys => ZIO.foreach(keys.toList)(k => configStore.fetch[String, V](k).mapError(storeErr(op))).map(_.flatten))

  private def toStoreWorkflowRow(workflow: WorkflowRow, id: Long): shared.store.WorkflowRow =
    shared.store.WorkflowRow(id = id.toString, name = workflow.name, description = workflow.description,
      stepsJson = workflow.steps, isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt, updatedAt = workflow.updatedAt)

  private def fromStoreWorkflowRow(workflow: shared.store.WorkflowRow): Option[WorkflowRow] =
    workflow.id.toLongOption.map(parsedId => WorkflowRow(id = Some(parsedId), name = workflow.name,
      description = workflow.description, steps = workflow.stepsJson, isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt, updatedAt = workflow.updatedAt))

  private def toStoreAgentRow(agent: CustomAgentRow, id: Long): shared.store.CustomAgentRow =
    shared.store.CustomAgentRow(id = id.toString, name = agent.name, displayName = agent.displayName,
      description = agent.description, systemPrompt = agent.systemPrompt, tagsJson = agent.tags,
      enabled = agent.enabled, createdAt = agent.createdAt, updatedAt = agent.updatedAt)

  private def fromStoreAgentRow(agent: shared.store.CustomAgentRow): Option[CustomAgentRow] =
    agent.id.toLongOption.map(parsedId => CustomAgentRow(id = Some(parsedId), name = agent.name,
      displayName = agent.displayName, description = agent.description, systemPrompt = agent.systemPrompt,
      tags = agent.tagsJson, enabled = agent.enabled, createdAt = agent.createdAt, updatedAt = agent.updatedAt))

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO.attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErrThrowable(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeErr(op: String)(e: io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError)
    : PersistenceError = PersistenceError.QueryFailed(op, e.toString)

  private def storeErrThrowable(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  private def decodeSetting(key: String, raw: String): SettingRow =
    SettingRow(key = key, value = raw, updatedAt = Instant.EPOCH)

  private def checkpointConfigStore(op: String): IO[PersistenceError, Unit] =
    for
      status <- configStore.rawStore.maintenance(LifecycleCommand.Checkpoint)
                  .mapError(err => PersistenceError.QueryFailed(op, err.toString))
      _      <- status match
                  case LifecycleStatus.Failed(message) =>
                    ZIO.fail(PersistenceError.QueryFailed(op, s"Config store checkpoint failed: $message"))
                  case _ => ZIO.unit
    yield ()

object ConfigRepositoryES:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction((configStore: ConfigStoreModule.ConfigStoreService) =>
      ConfigRepositoryES(configStore)
    )
```

- [ ] **Step 6: Update `app/ApplicationDI.scala`**

Replace `import db.*` (line 42) with explicit imports from the real packages:

```scala
import taskrun.control.TaskRepository
import conversation.control.ChatRepository
import taskrun.entity.{ RunStatus, TaskArtifactRow, TaskReportRow, TaskRunRow }
import config.entity.{ CustomAgentRow, SettingRow, WorkflowRow }
```

Remove any `db.*` references from type aliases and ZLayer wiring. The `TaskRepository.live` and `ChatRepository.live` now resolve from `taskrun.control` and `conversation.control`.

- [ ] **Step 7: Update all other files importing from `db`**

For each file below, replace `import db.*` or `import db.X` with imports from the correct packages.

**Import translation table:**
| Old import | New import |
|-----------|-----------|
| `import db.TaskRepository` | `import taskrun.control.TaskRepository` |
| `import db.ChatRepository` | `import conversation.control.ChatRepository` |
| `import db.TaskRunRow` | `import taskrun.entity.TaskRunRow` |
| `import db.TaskReportRow` | `import taskrun.entity.TaskReportRow` |
| `import db.TaskArtifactRow` | `import taskrun.entity.TaskArtifactRow` |
| `import db.RunStatus` | `import taskrun.entity.RunStatus` |
| `import db.CustomAgentRow` | `import config.entity.CustomAgentRow` |
| `import db.SettingRow` | `import config.entity.SettingRow` |
| `import db.WorkflowRow` | `import config.entity.WorkflowRow` |
| `import db.FileType` | `import shared.web.FileType` |
| `import db.*` | split into relevant individual imports above |

**Files to update** (run `grep -rl "import db\." src/main/scala/` to get the full list):
- `analysis/control/AnalysisAgentRunner.scala`
- `analysis/control/WorkspaceAnalysisScheduler.scala`
- `app/control/HealthMonitor.scala`
- `checkpoint/control/CheckpointReviewService.scala`
- `conversation/boundary/ChatController.scala`
- `gateway/control/GatewayService.scala`
- `gateway/control/MessageRouter.scala`
- `issues/boundary/IssueController.scala`
- `knowledge/control/KnowledgeExtractionService.scala`
- `orchestration/control/AgentConfigResolver.scala`
- `orchestration/control/AgentDispatcher.scala`
- `orchestration/control/IssueAssignmentOrchestrator.scala`
- `orchestration/control/Llm4zioAdapters.scala`
- `orchestration/control/PlannerAgentService.scala`
- `orchestration/control/TaskExecutor.scala`
- `taskrun/boundary/DashboardController.scala`
- `workspace/control/RunSessionManager.scala`
- `workspace/control/WorkspaceRunService.scala`
- `config/entity/ConfigRepositoryES.scala` (handled in Step 5)
- `gateway/boundary/telegram/TaskProgressNotifier.scala`
- `shared/web/ChatDetailContext.scala`
- `shared/web/CommandCenterView.scala`
- `shared/web/Components.scala`
- `shared/web/GraphView.scala`
- `shared/web/HtmlViews.scala`
- `shared/web/ReportsView.scala`
- `shared/web/TasksView.scala`
- `gateway/boundary/telegram/InlineKeyboards.scala`
- `gateway/boundary/telegram/TelegramChannel.scala`
- `gateway/control/WorkflowNotifier.scala`
- `orchestration/control/AgentRegistry.scala`
- `orchestration/control/WorkflowService.scala`
- `taskrun/boundary/GraphController.scala`
- `taskrun/boundary/ReportsController.scala`
- `taskrun/boundary/TasksController.scala`
- `config/boundary/AgentsController.scala`

- [ ] **Step 8: Compile after all import updates**

```bash
sbt compile
```

Fix any remaining compilation errors by tracing the type not found back to the import table in Step 7.

- [ ] **Step 9: Delete the `db/` package**

```bash
rm -rf src/main/scala/db/
```

- [ ] **Step 10: Final compile and full test suite**

```bash
sbt compile && sbt test && sbt it:test
```

Expected: all tests pass, no references to `db` remain:
```bash
grep -r "import db\." src/main/scala/
```
Expected: no output.

- [ ] **Step 11: Commit and close issue #660**

```bash
git add -A src/main/scala/
git commit -m "$(cat <<'EOF'
migrate and remove legacy db/ package

Moves TaskRepository + TaskRepositoryLive to taskrun/control/ and
ChatRepository + ChatRepositoryLive to conversation/control/. Inlines
ConfigRepositoryES implementation into config/entity/ (removes the
db.ConfigRepositoryES delegation layer). Updates all 37 import sites to
reference types from their actual home packages (taskrun.entity,
config.entity, shared.web). Deletes db/ entirely.

Row types (TaskRunRow, RunStatus, etc.) were already defined in
taskrun.entity — db/CompatTypes.scala was just re-exporting them.

Closes #660
EOF
)"
```

---

## Verification

After all tasks:

```bash
# No db imports remain
grep -r "import db\." src/main/scala/
# No legacy enum values
grep -r "IssueStatus\.Open\|IssueStatus\.Assigned\|IssueStatus\.Completed" src/main/scala/
# No legacy method names
grep -r "loadingSpinner\|Components\.emptyState[^F]\|purgeLegacyIssueDataLayer\|purgeSnapshotsAndLegacyKeys\|appendLegacyStartedEvent\|createLegacyIssues" src/main/scala/
# Full test suite
sbt test && sbt it:test
# All 6 issues closed
gh issue list --milestone legacy-removal --state open
```

Expected: all grep commands return no output; all tests pass; only issue #657 (reference doc) remains open.
