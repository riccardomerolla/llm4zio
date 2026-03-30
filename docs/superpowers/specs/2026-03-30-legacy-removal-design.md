# Legacy Removal Design

**Date:** 2026-03-30
**Status:** Approved

---

## Context

The llm4zio codebase has accumulated orphaned view files, superseded JS components, legacy persistence patterns, backward-compatibility shims, and startup-time migration code. These were identified by tracing all reachable HTTP routes from the web UI (180+ routes across 24 controllers) and cross-referencing them against Scalatags views, Lit 3 web components, and the `db/` package.

---

## Route & Navigation Map

All HTTP paths reachable from the UI without typing a URL directly.

### Core / Dashboard
- `GET /` → Main dashboard
- `GET /tasks` → Tasks list
- `GET /tasks/new` → Create task form
- `GET /tasks/<id>` → Task detail
- `GET /reports` → Reports list
- `GET /reports/<id>` → Report detail
- `GET /graph` → Task graph visualisation
- `GET /logs` → Task logs

### Chat / Planner
- `GET /chat` → Chat dashboard
- `GET /chat/new` → New conversation
- `GET /chat/<id>` → Conversation view
- `GET /planner` → Redirects → `/chat/new?mode=plan`
- `GET /plans` → Plans list
- `GET /plans/<id>` → Plan detail

### Board / Issues
- `GET /board` → Issues board (alias)
- `GET /board/<workspaceId>` → Workspace board
- `GET /issues` → Issues list
- `GET /issues/board` → Issues board view
- `GET /issues/new` → Create issue form
- `GET /issues/<id>` → Issue detail
- `GET /issues/<id>/edit` → Edit issue

### Settings
- `GET /settings` → Main settings
- `GET /settings/ai` → AI settings tab
- `GET /settings/gateway` → Gateway settings tab
- `GET /settings/channels` → Channels settings tab
- `GET /settings/workspaces` → Workspaces settings tab
- `GET /settings/workspaces/<id>` → Workspace settings edit
- `GET /settings/system` → System health tab
- `GET /settings/advanced` → Advanced settings tab
- `GET /settings/issues-templates` → Issue templates settings
- `GET /config` → Config editor

### Agents & Workflows
- `GET /agents` → Agents list
- `GET /agents/new` → Create agent form
- `GET /agents/<id>` → Agent detail
- `GET /agents/<slug>/edit` → Edit agent
- `GET /agents/registry` → Agent registry
- `GET /agents/registry/new` → Register agent
- `GET /agents/registry/<id>` → Registered agent detail
- `GET /workflows` → Workflows list
- `GET /workflows/new` → Create workflow
- `GET /workflows/<id>` → Workflow detail
- `GET /workflows/<id>/edit` → Edit workflow

### ADE (Advanced Design & Evolution)
- `GET /specifications` → Specifications list
- `GET /specifications/<id>` → Specification detail
- `GET /specifications/<id>/diff` → Version diff
- `GET /checkpoints` → Checkpoints list
- `GET /checkpoints/<runId>` → Run checkpoints
- `GET /decisions` → Decisions inbox
- `GET /knowledge` → Knowledge base
- `GET /daemons` → Daemons management
- `GET /governance` → Governance policies
- `GET /evolution` → Evolution proposals
- `GET /sdlc` → SDLC dashboard

### Workspace & Projects
- `GET /workspaces` → Workspaces list
- `GET /workspace-templates` → Workspace templates
- `GET /runs` → Runs list
- `GET /projects` → Projects list
- `GET /projects/<id>` → Project detail

### Activity & Memory
- `GET /activity` → Activity log
- `GET /memory` → Memory / RAG storage

### Real-time
- `GET /ws/console` → WebSocket console
- `SSE /agent-monitor/stream` → Agent stats stream

### Dev / Health
- `GET /health` → Redirects → `/settings/system`
- `GET /api/health` → JSON health snapshot
- `GET /components` → Component catalog (dev only)

---

## Cleanup Areas

### 1. Orphaned Scalatags view files
Zero references in any controller or `HtmlViews` facade:
- `src/main/scala/shared/web/DashboardView.scala`
- `src/main/scala/shared/web/ComponentsCatalogView.scala`
- `src/main/scala/shared/web/AgentRegistryView.scala` (superseded per #477)
- `src/main/scala/shared/web/ProofOfWorkView.scala`

### 2. Orphaned frontend JS web components
- `src/main/resources/static/client/components/chat-message-stream.js` → superseded by `ab-chat-stream.js`
- `src/main/resources/static/client/components/ab-agent-monitor.js` → superseded by `agent-monitor.js`

### 3. Legacy `db/` package
Marked legacy in CLAUDE.md. Contains stub methods and old persistence patterns.
Files: `ChatRepository`, `ChatRepositoryLive`, `TaskRepository`, `TaskRepositoryLive`, `ConfigRepository`, `CompatTypes`, `LegacyTypes`

### 4. Legacy UI component aliases
In `src/main/scala/shared/web/Components.scala`:
- `loadingSpinner()` → alias for `spinner()`
- `emptyState()` → superseded by `emptyStateFull()`

### 5. Legacy issue status backward-compatibility shims
- `IssueApiModels.scala`: `Open/Assigned/Completed` → `Backlog/Todo/Done` aliases
- `IssueController.scala`: `appendLegacyStartedEvent` paths
- `PlannerAgentService.scala`: `createLegacyIssues()` method

### 6. Legacy startup data purge layers
In `src/main/scala/app/ApplicationDI.scala`:
- `purgeLegacyIssueDataLayer`
- `purgeSnapshotsAndLegacyKeys()` in `WorkspaceRepository`

---

## GitHub Tracking

All cleanup areas tracked under the `legacy-removal` milestone with one issue per area.
