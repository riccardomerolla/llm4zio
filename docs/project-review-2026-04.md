# llm4zio Project Review — 2026-04-17

Scope: state of `main` at HEAD `826dff6` after phase 5A.7 landed. Revised 2026-04-18 (nineteenth pass) after phases 4C, 4D, 4E.A–4E.H, 4F.1–4F.4, 5A.1–5A.7, and 5B. The connectors-UI branch has merged, the `db/` legacy module has been deleted, orchestration-domain has grown its own `control/` layer (and, with 5A.7, its first `boundary/` layer), `WorkspaceRunService` and `MessageRouter` trait halves now live in their domain modules' `entity/` packages, `AgentRegistry` has relocated to `agent-domain`, `AgentMatching` has moved out of `agent-domain` (dropping the `agentDomain → workspaceDomain` edge), phases 4E.E–4E.H have drained 28 more root files into `orchestration-domain`, `config-domain`, `knowledge-domain`, and `shared-services`, and the `IssueController` sub-feature split (4F.1–4F.4) has shrunk the controller from 2,758 → 1,954 LOC. Phase 5A distributed seven views out of shared-web: `ProofOfWorkView` → `issues-domain/boundary/` (5A.1), `WorkspaceTemplatesView` → `workspace-domain/boundary/` (5A.2), `WorkflowsView` → `taskrun-domain/boundary/` (5A.3), `ProjectsView` → `project-domain/boundary/` (5A.4), `DemoView` → `demo-domain/boundary/` (5A.5), `CommandCenterView` → `taskrun-domain/boundary/` (5A.6), `AgentMonitorView` → `orchestration-domain/boundary/` (5A.7); phase 5B deleted three zero-caller dead views (`HealthDashboard`, `ConfigEditor`, `AgentMonitor`, 80 LOC). Root `src/main/scala/` is still **56 files**, mostly boundary controllers; `shared-web` drops from ~20 → **10 view files**.

---

## Executive Summary

The project is in strong shape and accelerating. Foundations remain clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, `sbt compile` green, 950 tests pass). Modularization has advanced to **351 Scala files under `modules/`** vs **56 in `src/main/scala/`** — roughly **86% modularized**. The remaining root files are almost entirely boundary controllers (`IssueController`, `AgentsController`, `SettingsController`, etc.) and `app/` DI wiring — the "structurally bound to stay" population from the plan.

The connectors-UI branch merged, all five outstanding P1 structural items from the previous review have landed, and three additional mechanical-move phases (4E.E–4E.G) drained 15 more root files:

- ✅ **`db/` module deleted** (phase 4D, commits `86579e6` → `06ec3d4`). `ChatRepository`, `TaskRepository`, and `ConfigRepository` were all migrated to their respective domain modules as event-sourced repositories, and the legacy package is gone. Zero `^import db\.` sites remain.
- ✅ **Orchestration leaf services moved** (phase 4E.A, commit `3ac72fd`). `AgentConfigResolver`, `AgentDispatcher`, `Llm4zioAdapters`, `OrchestratorControlPlane`, and `TaskExecutor` now live in `orchestration-domain/control/`.
- ✅ **`WorkspaceRunService` trait extracted** (phase 4E.B, commit `0c98cf5`). Trait at `workspace.entity.WorkspaceRunService`; Live stays in `workspace.control`. `registerSlot` dropped from the trait (kept as a private self-call on Live) so the public interface no longer leaks `orchestration.entity.SlotHandle`.
- ✅ **`MessageRouter` trait extracted** (phase 4E.C, commit `c38a4cf`). Trait + `MessageChannelError` in `gateway.entity`; `attachControlPlaneRouting` dropped from the trait and reborn as a standalone helper that pulls `ChannelRegistry` and `OrchestratorControlPlane` from the ZIO environment.
- ✅ **`AgentRegistry` relocated to `agent-domain`** (phase 4E.D, commit `e50eb95`). Trait in `agent.entity`; Live in `agent.control`. `orchestrationDomain` now `dependsOn(agentDomain)` instead of the reverse.
- ✅ **Phase 4E.E** (commit `6d92ce6`) — 8 root control files drained into `orchestration-domain/control/`: `IssueDispatchStatusService`, `AgentPoolManager` → `AgentPoolManagerLive`, `ParallelSessionCoordinator`, `AutoDispatcher`, `IssueAssignmentOrchestrator`, `BoardOrchestrator` (package `board.control` retained cross-module), `IssueApprovalService`, `WorkflowNotifier`.
- ✅ **Phase 4E.F** (commit `32f8835`) — 7 more root files relocated: `PromptLoader` and `PromptHelpers` → `config-domain` (package `prompts` retained, resources stay on root classpath); `GatewayService`, `IntentParser`, `MessageRouter` object, `DiscordGatewayService`, `TelegramPollingService` → `orchestration-domain` (since `gatewayDomain` cannot depend on `orchestrationDomain`).
- ✅ **Phase 4E.G** (commit `392c7dd`) — `Logger`, `RetryPolicy`, `LogTailer` from `app/control/` → `shared-services` (package `app.control` retained).
- ✅ **Phase 4E.H** (commits `a5920e7`, `06669f9`) — two-step drain:
  - **4E.H.1**: `AgentMatching` (+ spec) moved from `agent-domain/control` to `orchestration-domain/control`. Rationale: it's agent-vs-`WorkspaceRun` scoring logic, orchestration-shaped. `agentDomain.dependsOn(workspaceDomain)` edge removed.
  - **4E.H.2**: 12 root control files relocated — `KnowledgeExtractionService` → `knowledge-domain`; 9 `workspace/control/*` + 2 `analysis/control/*` files → `orchestration-domain` under their existing packages (same-package cross-module). Honest scope note: these files *conceptually* split between workspace-domain and orchestration-domain, but because `workspace/control/*` imports `orchestration.control.{OrchestratorControlPlane, WorkReportEventBus}` and `orchestrationDomain` already `dependsOn(workspaceDomain)`, any back-edge creates a cycle. Parking them under orchestration-domain keeps them out of root without destabilizing the graph; a later pass can split cleanly.
- ✅ **Phase 4F.1** (commit `6b47c82`) — `IssueTemplateService` extracted from `IssueController` into `issues-domain/control/` (~407 LOC trait + Live). `IssueController` shrinks from 2,758 → 2,394 LOC (−364). Built-in templates, custom-template CRUD, pipeline CRUD, and variable resolve/validate/apply all live behind the trait; controller delegates via `templateService.xxx`. `issuesDomain` gains `configDomain` edge. This is the first sub-feature extraction out of `IssueController` and establishes the seam pattern for 4F.2 (sub-controller splits).
- ✅ **Phase 4F.2** (commit `09c2571`) — `IssueTemplatesController` extracted from `IssueController` into `src/main/scala/issues/boundary/IssueTemplatesController.scala` (~94 LOC). Seven template + pipeline CRUD routes (`GET /settings/issues-templates`, `GET/POST /api/issue-templates`, `PUT/DELETE /api/issue-templates/:id`, `GET/POST /api/pipelines`) now live in the sub-controller, which depends only on `IssueTemplateService` and rendering helpers. Wired via `CoreRouteModule.issueTemplates.routes`. `IssueController` shrinks further from 2,394 → 2,339 LOC (−55). `POST /issues/from-template/:templateId` stays in `IssueController` because it creates issues and needs workspace board-issue helpers.
- ✅ **Phase 4F.3** (commit `d1eab33`) — `IssueBulkService` + `IssueBulkController` extracted from `IssueController`. Service (~260 LOC) filed under `modules/orchestration-domain/src/main/scala/issues/control/` via the same-package cross-module pattern used in 4E.H.2, because it depends on `IssueAssignmentOrchestrator` and `issuesDomain` cannot depend on `orchestrationDomain`. Controller (~80 LOC) at `src/main/scala/issues/boundary/IssueBulkController.scala` owns `POST /api/issues/bulk/assign`, `POST /api/issues/bulk/status`, `POST /api/issues/bulk/tags`, `DELETE /api/issues/bulk`. `IssueController` drops the four routes and four private bulk methods (plus `validateIssueIds`); `toBulkResponse` stays because import routes still use it. HumanReview analysis-attachment behaviour preserved via `statusToEventsEnriched` (composes `IssueControllerSupport.statusToEvents` with `IssueAnalysisAttachment.latestForHumanReview`). `IssueController` shrinks from 2,339 → 2,204 LOC (−135). Wired into `ApplicationDI` and `CoreRouteModule`.
- ✅ **Phase 4F.4** (commit `b2f9087`) — `IssueImportService` + `IssueImportController` extracted from `IssueController`, completing the sub-feature split started in 4F.1. Service (~239 LOC) lives in `modules/issues-domain/src/main/scala/issues/control/` — filed under `issues-domain` (not `orchestration-domain`) because it only needs `IssueRepository` + `TaskRepository` and `issuesDomain` already dependsOn `taskrunDomain`. Controller (~86 LOC) owns `POST /issues/import` (onboarding redirect), `POST /api/issues/import/folder{/preview}`, and `POST /api/issues/import/github{/preview}`. `IssueController` drops 5 routes + 9 private helpers (`previewIssuesFromFolder`, `importIssuesFromFolderDetailed`, `importIssuesFromConfiguredFolderDetailed`, `importIssuesFromConfiguredFolder`, `issueImportFolderFromSettings`, `issueImportMarkdownFiles`, `previewGitHubIssues`, `importGitHubIssues`, `ghListIssues`) plus `toBulkResponse` (no remaining callers) and the now-unused `java.nio.charset`/`java.nio.file.{Files, Path}`/`scala.jdk.CollectionConverters` imports. `IssueController` shrinks from 2,204 → 1,954 LOC (−250). Cumulative since pre-4F.1: **2,758 → 1,954 LOC (−804, −29%)**. Reuses `IssueControllerSupport.parseMarkdownIssue`. Wired into `ApplicationDI` and `CoreRouteModule`.
- ✅ **Phase 5A.1** (commit `6dc1609`) — `ProofOfWorkView` relocated from `modules/shared-web/src/main/scala/shared/web/` to `modules/issues-domain/src/main/scala/issues/boundary/`. Kicks off the shared-web view distribution pass. Rationale: the view's only external deps are `issues.entity.{IssueCiStatus, IssuePrStatus, IssueWorkReport}`, `workspace.entity.RequirementCheck`, and `scalatags`; `issuesDomain` already `dependsOn(workspaceDomain)`, so the dependency graph is clean and no cycle risk. Callers updated with an explicit `import issues.boundary.ProofOfWorkView` in `IssuesView.scala` (still in shared-web, already dependsOn issuesDomain) and in `ProofOfWorkViewSpec.scala`. All 15 existing tests pass unchanged. `shared-web` view file count drops by 1.
- ✅ **Phase 5A.2** (commit `76df331`) — `WorkspaceTemplatesView` (1,184 LOC including inline wizard JS) relocated to `modules/workspace-domain/src/main/scala/workspace/boundary/`. Rationale: the view has zero domain-entity deps (only `Layout` + `Components` from `shared-web-core` plus scalatags). The move required upgrading `workspaceDomain` from `domainDeps` → `domainBceDeps` (gains zio-http + scalatags) and adding `sharedWebCore` to its `dependsOn` list (`zio-streams` re-added explicitly since `domainBceDeps` doesn't bundle it). `WorkspacesController` already sits in `workspace.boundary`, so the stale `shared.web.WorkspaceTemplatesView` import was replaced with same-package cross-module access. Spec updated with `import workspace.boundary.WorkspaceTemplatesView`. Both existing tests pass.
- ✅ **Phase 5A.3** (commit `ec431f9`) — `WorkflowsView` (511 LOC including inline Mermaid-preview JS) relocated from `modules/shared-web/src/main/scala/shared/web/` to `modules/taskrun-domain/src/main/scala/taskrun/boundary/`. Cycle note: the obvious target was `config-domain` (owns `WorkflowDefinition`, `AgentInfo`, `WorkflowStepAgent`), but `taskrunDomain dependsOn configDomain` already exists, so reverse-depping would create a cycle. `taskrun-domain` already carries `domainBceDeps` + `sharedWebCore` and `dependsOn(configDomain)`, so zero build.sbt changes were needed. `TaskStep` import is now same-package (kept for diff minimalism); `Layout` pulled in via a new explicit `import shared.web.Layout`. `HtmlViews.scala` had `taskrun.boundary.{ GraphView, ReportsView }` already — extended to `{ GraphView, ReportsView, WorkflowsView }`. Spec updated with `import taskrun.boundary.WorkflowsView`. The single existing test (JSON-escaping embed check) passes; `sbt compile` green.
- ✅ **Phase 5A.4** (commit `c555efc`) — `ProjectsView` + four companion case classes (`ProjectListItem`, `ProjectWorkspaceRow`, `ProjectAnalysisRow`, `ProjectDetailPageData`) relocated to `modules/project-domain/src/main/scala/project/boundary/`. `projectDomain` gained `sharedWebCore` dep and upgraded to `domainBceDeps`. Cycle surgery: the view's board tab previously called `shared.web.IssuesView.boardColumnsFragment`, but `projectDomain` cannot `dependsOn(sharedWeb)` (sharedWeb already dependsOn projectDomain). Resolution: `ProjectDetailPageData` now carries a pre-rendered `boardFragmentHtml: Option[String]` instead of raw `boardIssues: List[AgentIssueView]` + `boardWorkspaces: List[(String, String)]`; the controller invokes `IssuesView.boardColumnsFragment` once before constructing the data bag, `None` triggers the empty state. This also dissolved the incidental `issues.entity.api.AgentIssueView` dep from the view, so no new inter-domain edges were needed. `ProjectsController` (still in root, same package `project.boundary`) simplified: the whole `shared.web.{Project*, ProjectsView}` import block dropped (same-package access now); added `import shared.web.IssuesView`. 4 controller tests + 7 module tests all pass.
- ✅ **Phase 5B** (commit `d0af48f`) — three zero-caller dead views deleted from `shared-web`: `HealthDashboard.scala` (19 LOC), `ConfigEditor.scala` (25 LOC), `AgentMonitor.scala` (36 LOC). Total 80 LOC removed. Confirmed dead via exhaustive `rg HealthDashboard\.`, `rg ConfigEditor\.`, `rg AgentMonitor\.page` across modules + root + tests — zero matches. Disambiguation: `AgentMonitor.scala` (dead) is distinct from `AgentMonitorView.scala` (live, 196 LOC, routed via `AgentMonitorController`); `ConfigEditor.scala` (dead view) is unrelated to `config-domain/entity/ConfigEditorModels.scala` (live — used by `ConfigController` + `ConfigValidator`). Candidate `ComponentsCatalogView.scala` retained because `src/main/scala/app/boundary/WebServer.scala:26` routes `/components` to it.
- ✅ **Phase 5A.5** (commit `4321f96`) — `DemoView` (273 LOC) relocated from `modules/shared-web/src/main/scala/shared/web/` to `modules/demo-domain/src/main/scala/demo/boundary/`. Cycle surgery: the view previously called `SettingsView.settingsShell(...)`, which would have forced `demoDomain → sharedWeb` (already forbidden, sharedWeb dependsOn demoDomain). Discovery: `SettingsView.settingsShell` is a 1-line passthrough to `SettingsShell.page` in `shared-web-core`. Rewrote the call site to use `SettingsShell.page` directly, dissolving both the cycle and the indirection. Build-graph deltas: `demoDomain` upgraded from a bespoke `Seq(zio, zioJsonDep)` to `domainBceDeps` + added `sharedWebCore` to its `dependsOn` list. `HtmlViews.scala` gained `import demo.boundary.DemoView`; `DemoController.scala` (already at `demo.boundary`) dropped its now-redundant `import shared.web.DemoView`. 20/20 demoDomain tests pass; `sbt compile` green.
- ✅ **Phase 5A.6** (commit `a151fd5`) — `CommandCenterView` (179 LOC) relocated from `modules/shared-web/src/main/scala/shared/web/` to `modules/taskrun-domain/src/main/scala/taskrun/boundary/`. Rationale: all data inputs (`PipelineSummary`, `ActivityEvent`, `TaskRunRow`) are taskrun/activity-shaped, and the only caller (`DashboardController`) already sits in `taskrun.boundary` — same-package access cleans up the call site. Cycle surgery: the live-section used to call `AgentMonitorView.statsHeaderFragment(AgentGlobalStats.empty)` directly, but `AgentMonitorView` still lives in shared-web and `taskrun-domain` cannot dependsOn sharedWeb. Resolution follows the 5A.4 pattern — `page()` now accepts a pre-rendered `statsHeaderHtml: String`, and `HtmlViews.dashboard` renders the empty-state fragment once (same-package as `AgentMonitorView`) before handing it in. Build-graph delta: `taskrunDomain` gains `dependsOn(activityDomain)` so the view can reach `activity.boundary.ActivityView` + `activity.entity.ActivityEvent`; no cycle risk because `activityDomain` dep list is foundation-only. `DashboardController` drops the `shared.web.` prefix on `CommandCenterView.PipelineSummary`. 18/18 taskrunDomain tests pass; `sbt compile` green.
- ✅ **Phase 5A.7** (commit `826dff6`) — `AgentMonitorView` (197 LOC) relocated from `modules/shared-web/src/main/scala/shared/web/` to `modules/orchestration-domain/src/main/scala/orchestration/boundary/`. Clean move — all data inputs (`AgentExecutionInfo`, `AgentExecutionState`, `AgentMonitorSnapshot`, `MetricsSnapshot`) already live in `orchestration.entity` or `llm4zio.observability`, both modules that `orchestration-domain` already depends on. No build-graph changes, no cycle risk. Creates the **first `boundary/` package inside `orchestration-domain`** (previously entity + control only). Call-site updates: `AgentMonitorController` (root, `app.boundary`) swapped `import shared.web.AgentMonitorView` → `import orchestration.boundary.AgentMonitorView`; `HtmlViews.scala` gained an explicit import (previously same-package); `AgentMonitorViewSpec` import updated. 22/22 spec tests pass; `sbt compile` green.

No structural P1 items remain. The modularization drive is effectively complete for control-layer code — remaining root files are boundary controllers (the structurally bound population) and `app/` DI wiring. Shared-web view distribution is now underway (5A.x series).

Top three actions now:

1. **`IssueController` sub-feature split is effectively done (1,954 LOC after 4F.4).** Templates, bulk, and import sub-controllers are all extracted. Remaining content is the issue CRUD + board fragment + auto-assign + status-update core plus a handful of timeline/agent-suggestion helpers. Optional 4F.5 (`IssueTimelineService`, extracting timeline fetching + rendering helpers) would knock another ~200–300 LOC off but is low-priority — the file is now tractable.
2. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages, starting with the cleanest single-domain set.
3. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` (highest churn, currently zero module-scoped tests).

---

## 1. Recently Merged: `feature/connectors-ui-redesign`

Feature intent: split connector defaults into API/CLI cards, with per-agent overrides exposed via the Agents page rather than Settings. All branch-level review items shipped and the branch is now on `main`.

### What shipped

- ✅ Dead helpers + missing routes removed (`684d1cb`, `42aff8e`). `agentRow()` / `agentOverrideForm()` and the unused `agents` / `agentOverrides` parameters on `connectorsTab` deleted.
- ✅ `checkpointConfigStore` present in the agent mode toggle ([AgentsController.scala:386](../src/main/scala/config/boundary/AgentsController.scala#L386)).
- ✅ `ConnectorConfigResolverSpec` now 207 lines, covering api-default, cli-default, agent mode-scoped override, agent flat, legacy `ai.*` fallback.
- ✅ `ZIO.logDebug` on resolver parse failures ([ConnectorConfigResolver.scala:36](../src/main/scala/config/control/ConnectorConfigResolver.scala#L36)).

### Remaining — optional cleanup

- **Inline `<script>` still uses `var`** at [SettingsView.scala:369](../modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L369) (`envVarsScript` / `addEnvVarRow`). Prefer `const`/`let`, or replace with a Lit 3 component (`ab-env-vars-editor`) per CLAUDE.md frontend convention.
- **Env-var form roundtrip** reconstructs `KEY=VALUE` pairs entirely client-side, no server-side shape validation.
- **Route-shape inconsistency** (`/settings/connectors/{api|cli}` split vs `/agents/{name}/connector/mode?mode=` merged) is now the permanent shape — document the convention so future endpoints stay consistent.

---

## 2. Architecture & Modularization

### Progress

- 6 foundation modules + 25 domain modules in `build.sbt` (34 `lazy val` project declarations total). All domain modules have `entity/` layers.
- **351 Scala files under `modules/`** vs **56 in `src/main/scala/`** — roughly **86% modularized**.
- Domain modules that now have `control/` populated inside the module: `agent` (incl. `AgentRegistryLive`), `analysis`, `board`, `checkpoint`, `daemon`, `demo`, `evolution`, `issues`, `knowledge` (incl. `KnowledgeExtractionService`), `memory`, `orchestration` (hosts the full gateway channel stack, the workspace run lifecycle stack, the analysis runner/scheduler, `AgentMatching`, and the same-package `board.control` orchestrator pair), `project`, `sdlc`, `taskrun`, `workspace` (partial).
- `config-domain` now owns the `prompts` package.
- `shared-services` now owns `app.control.{Logger, RetryPolicy, LogTailer}`.
- **`agentDomain` no longer depends on `workspaceDomain`** (4E.H.1). The only remaining `dependsOn` edges from agent-domain are to foundation modules + `configDomain`.

### Recent gains since the 2026-04-17 review (first pass)

- **Phase 4C**: `config/control` and loader consolidated into `config-domain` (`551f797`).
- **Phase 4D.1–4D.4**: `ConfigRepositoryES` into config-domain; `ChatRepository` into conversation-domain; `TaskRepository` into taskrun-domain; `db/` package deleted (`86579e6`, `4fd26d5`, `54e4a21`, `06ec3d4`).
- **Phase 4E.A**: five orchestration leaf services moved into `orchestration-domain/control/` (`3ac72fd`).
- **Phase 4E.B**: `WorkspaceRunService` trait extracted to `workspace.entity` (`0c98cf5`).
- **Phase 4E.C**: `MessageRouter` trait + `MessageChannelError` extracted to `gateway.entity`; `attachControlPlaneRouting` split off the trait as a standalone helper (`c38a4cf`).
- **Phase 4E.D**: `AgentRegistry` relocated from `orchestration-domain` to `agent-domain`; `orchestrationDomain` now `dependsOn(agentDomain)` — arrow inverted to natural direction (`e50eb95`).
- **Phase 4E.E**: 8 root control files drained into `orchestration-domain/control/` — `IssueDispatchStatusService`, `AgentPoolManager → AgentPoolManagerLive`, `ParallelSessionCoordinator`, `AutoDispatcher`, `IssueAssignmentOrchestrator`, `BoardOrchestrator`, `IssueApprovalService`, `WorkflowNotifier`. `build.sbt` `orchestrationDomain.dependsOn(...)` expanded to include `boardDomain`, `projectDomain`, `governanceDomain`, `decisionDomain`, `conversationDomain`, `memoryDomain`, `llm4zio` (`6d92ce6`).
- **Phase 4E.F**: `PromptLoader` + `PromptHelpers` → `config-domain` (package `prompts` retained, resources stay on root classpath); `GatewayService`, `IntentParser`, `MessageRouter` object, `DiscordGatewayService`, `TelegramPollingService` → `orchestration-domain` (since `gatewayDomain` cannot depend on `orchestrationDomain` without creating a cycle) (`32f8835`).
- **Phase 4E.G**: `Logger`, `RetryPolicy`, `LogTailer` → `shared-services` under package `app.control` (same-package cross-module) (`392c7dd`).
- **Phase 4E.H.1**: `AgentMatching` + spec → `orchestration-domain/control/`; `agentDomain → workspaceDomain` edge dropped (`a5920e7`).
- **Phase 4E.H.2**: `KnowledgeExtractionService` → `knowledge-domain/control/`; `workspace/control/*` (9 files) and `analysis/control/*` (2 files) → `orchestration-domain` (pragmatic drain — see 4E.H note above re: cycle constraints). `knowledgeDomain` gains `conversationDomain`, `issuesDomain`, `llm4zio`; `orchestrationDomain` gains `demoDomain`, `knowledgeDomain`, `analysisDomain` (`06669f9`).
- **IT fix**: `AssignRunRequest` relocated to `workspace.entity`; 3 integration specs updated (`c0eb1cd`).

### Remaining blockers

- **56 root files** (down from 83 → 68 → 56). The population is now almost entirely boundary controllers + DI wiring, which is the expected shape at the end of the control-layer drain:
  - `orchestration/control/` (1): `PlannerAgentService` — still root; could move alongside the other orchestration control if its imports permit.
  - `board/control/` (3): `IssueCreationWizard`, `IssueTimelineService`, `IssueWorkReportProjectionFactory` — candidates for a small follow-on drain.
  - `app/control/` (2): `HealthMonitor`, `Tracing` — `HealthMonitor` could move once cross-service deps are vetted; `Tracing` has OTEL deps not yet in `shared-services`.
  - Boundary controllers: `IssueController` (1,954 LOC after 4F.4, down from 2,758 pre-4F.1, −29%), `IssueTemplatesController` (94 LOC, extracted 4F.2), `IssueBulkController` (80 LOC, extracted 4F.3), `IssueImportController` (86 LOC, extracted 4F.4), `AgentsController`, `SettingsController`, `ProjectsController`, plus smaller ones — cross-cut multiple domains. Structurally bound to stay in root.
  - `app/`: `WebServer`, `ApplicationDI`, `config/boundary/Main.scala` — the DI composition root; by design depends on everything and stays at root.
  - `mcp/`: cross-cutting MCP tool support.
- **Architectural note on 4E.H.2**: 11 of the 12 files drained in 4E.H.2 are physically housed under `orchestration-domain` but keep their original `workspace.control` / `analysis.control` package declarations. Same-package cross-module is legal in Scala, but it does mean the physical/logical layout is no longer 1:1 for these packages. A future pass could clean this up by either (a) extracting the orchestration-trait subset from `orchestration.control` so `workspace-domain` could depend on the trait without the full graph, or (b) renaming packages to match their physical home. Neither is urgent.
- **`shared-web` still holds 10 multi-domain views** (e.g. `SettingsView`, `IssuesView`, `PlanPreviewComponents`, `ChatView`) awaiting distribution. Landed so far: `ProofOfWorkView` → `issues-domain/boundary/` (5A.1), `WorkspaceTemplatesView` → `workspace-domain/boundary/` (5A.2), `WorkflowsView` → `taskrun-domain/boundary/` (5A.3), `ProjectsView` → `project-domain/boundary/` (5A.4), `DemoView` → `demo-domain/boundary/` (5A.5), `CommandCenterView` → `taskrun-domain/boundary/` (5A.6), `AgentMonitorView` → `orchestration-domain/boundary/` (5A.7); three dead view shells deleted in 5B (`HealthDashboard`, `ConfigEditor`, `AgentMonitor`, −80 LOC). `ComponentsCatalogView` kept — routed from `WebServer`.

### Layer completeness

- 9 modules still lack a `boundary/` layer (analysis, conversation, evolution, orchestration, plan, project, sdlc, + 1–2 others).
- 10+ modules still have no `control/` at module scope.
- **No compile-time enforcement** of BCE directionality. `control` could import `boundary` today and the build would pass.

---

## 3. Code Quality & Conventions

Healthy across the board:

- Zero `var` in `src/main/scala/`.
- Zero `db.PersistenceError` imports (and no `db/` package at all).
- All package names are singular.
- Only 6 `Throwable` sites remain, all at legitimate infrastructure boundaries (`WebServer`, `ApplicationDI`, `Logger` — now in `shared-services`, `MemoryController`, `EmbeddingService`). Should be documented as allowed in CLAUDE.md.

---

## 4. Testing

- 950 unit tests pass and 18 integration tests pass; `sbt compile` green on current HEAD.
- Module-local tests now exist for: `activity`, `conversation`, `memory`, `knowledge`, `issues`, `project`, `evolution`, `demo`, `board`, `taskrun`. Examples: `ActivityRepositoryESSpec`, `TaskRepositoryESSpec` (migrated from root when their repos moved).
- Still zero module-scoped tests: `analysis`, `checkpoint`, `config`, `daemon`, `gateway`, `orchestration`, `plan`. High-churn ones (`checkpoint`, `gateway`, `orchestration`) are the most urgent backfill targets.
- `ConnectorConfigResolverSpec` mode-scoped coverage added on the connectors branch — no longer a gap.

---

## 5. Build Health

- `build.sbt`: **~530 lines across 34 projects**. Moderate complexity, still manageable as a single file. After 4E.D–4E.F the `dependsOn` graph has a cleaner shape: `orchestrationDomain → agentDomain → configDomain` (no back-edge). `orchestrationDomain` now explicitly depends on the full domain set it stitches together (board, project, governance, decision, conversation, memory, llm4zio) since it hosts the cross-cutting orchestrator services.
- `-Werror` is on for unused imports. No module-layer or package-layer compile checks beyond that.
- Test dependency discipline: `zio-eclipsestore-gigamap % Test` consistently added to domain modules that need ES-backed specs (activity, evolution, issues, knowledge, taskrun, conversation, config).

---

## 6. Recommendations (Prioritized)

### P0 — landed / none outstanding

The previous pass's P0 items all shipped. No critical items block day-to-day work.

### P1 — next sprint (reorganized around what's left)

1. ✅ ~~Extract `WorkspaceRunService` trait~~ — shipped in 4E.B.
2. ✅ ~~Extract `MessageRouter` trait to `gateway.entity`~~ — shipped in 4E.C.
3. ✅ ~~Relocate `AgentRegistry` out of `orchestration-domain`~~ — shipped in 4E.D.
4. ✅ ~~Move trapped root control files~~ — phases 4E.E/F/G/H shipped 30 relocations (8 + 7 + 3 + 12).
5. ✅ ~~Drop `agentDomain → workspaceDomain` edge~~ — shipped in 4E.H.1 (via `AgentMatching` relocation).
6. **Optional: phase 4F.5 — extract `IssueTimelineService`** from the remaining 1,954 LOC of `IssueController`. Lower priority than the structural P1 items above; the file is already tractable after 4F.4.
7. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages. **Landed: 5A.1 `ProofOfWorkView → issues-domain` (`6dc1609`), 5A.2 `WorkspaceTemplatesView → workspace-domain` (`76df331`), 5A.3 `WorkflowsView → taskrun-domain` (`ec431f9`), 5A.4 `ProjectsView → project-domain` (`c555efc`), 5A.5 `DemoView → demo-domain` (`4321f96`), 5A.6 `CommandCenterView → taskrun-domain` (`a151fd5`), 5A.7 `AgentMonitorView → orchestration-domain` (`826dff6`); 5B dead-view cleanup (`d0af48f`).** Next candidate among remaining 10 residents: `PlanPreviewComponents` (plan-domain; would need plan-domain to dependsOn orchestration-domain or a pre-render seam). Blocked on graph surgery: `SettingsView` (config + gateway + agent), `IssuesView` (multi-domain aggregator), `ChatView`, `ChatDetailContext`, `WorkspacesView`. Stay-by-design: `HtmlViews` (multi-domain aggregator), `ComponentsCatalogView` (routed from `WebServer`).
8. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` first (highest churn, currently zero module-scoped tests).
9. **Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component**. Sets the precedent for removing inline scripts from other Scalatags views.

### P2 — strategic

9. **Compile-time BCE enforcement**. Minimum viable: split each domain into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. Higher-effort: ArchUnit-equivalent architecture tests. This is phase 4G in the modularization plan.
10. **Optional phase 4F.5 — `IssueTimelineService` extraction** (currently 1,954 LOC after 4F.4). Templates, bulk, and import services + controllers are all extracted; timeline is the only remaining sub-feature seam of note, and at ~200–300 LOC it's the lowest-leverage extraction. Skip unless the file grows again.
11. **Introduce `CHANGELOG.md` and a lightweight release-branch policy**. The modularization plan would benefit from visible "epoch" markers.
12. **Document the 6 allowed `Throwable` sites in CLAUDE.md** so future reviewers don't re-litigate them.
13. **Document the mixed route-shape convention** (`/settings/connectors/{api|cli}` split vs `/agents/{name}/connector/mode?mode=` merged).

---

## Appendix: Key File References

| Finding | File |
|---|---|
| `connectorsTab` (clean 3-arg form) | [SettingsView.scala:25](../modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L25) |
| Mode toggle with persistence checkpoint | [AgentsController.scala:381-423](../src/main/scala/config/boundary/AgentsController.scala#L381) |
| Resolver debug logging | [ConnectorConfigResolver.scala:36](../src/main/scala/config/control/ConnectorConfigResolver.scala#L36) |
| Mode-scoped resolution logic | [ConnectorConfigResolver.scala:83-90](../src/main/scala/config/control/ConnectorConfigResolver.scala#L83) |
| Inline `var` in `envVarsScript` | [SettingsView.scala:369-404](../modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L369) |
| WorkspaceRunService trait (in entity after 4E.B) | [WorkspaceRunService.scala](../modules/workspace-domain/src/main/scala/workspace/entity/WorkspaceRunService.scala) |
| MessageRouter trait (in entity after 4E.C) | [MessageRouter.scala](../modules/gateway-domain/src/main/scala/gateway/entity/MessageRouter.scala) |
| `attachControlPlaneRouting` standalone helper | [MessageRouter.scala](../src/main/scala/gateway/control/MessageRouter.scala) |
| AgentRegistry trait (in agent-domain after 4E.D) | [AgentRegistry.scala](../modules/agent-domain/src/main/scala/agent/entity/AgentRegistry.scala) |
| AgentRegistryLive (in agent-domain after 4E.D) | [AgentRegistryLive.scala](../modules/agent-domain/src/main/scala/agent/control/AgentRegistryLive.scala) |
| Modularization plan | [snoopy-tinkering-hejlsberg.md](../.claude/plans/snoopy-tinkering-hejlsberg.md) |
| Architecture conventions | [CLAUDE.md](../CLAUDE.md) |
| Phase 4D.4 `db/` deletion | commit `06ec3d4` |
| Phase 4E.A orchestration leaf moves | commit `3ac72fd` |
| Phase 4E.B WorkspaceRunService trait extraction | commit `0c98cf5` |
| Phase 4E.C MessageRouter trait extraction | commit `c38a4cf` |
| Phase 4E.D AgentRegistry relocation to agent-domain | commit `e50eb95` |
| Phase 4E.E 8 root control files → orchestration-domain | commit `6d92ce6` |
| Phase 4E.F prompts → config-domain; gateway channel stack → orchestration-domain | commit `32f8835` |
| Phase 4E.G app/control utilities → shared-services | commit `392c7dd` |
| Phase 4E.H.1 AgentMatching → orchestration-domain; agentDomain→workspaceDomain edge dropped | commit `a5920e7` |
| Phase 4E.H.2 workspace/analysis control → orchestration-domain; KnowledgeExtractionService → knowledge-domain | commit `06669f9` |
| Phase 4F.1 IssueTemplateService extraction (IssueController 2,758 → 2,394 LOC) | commit `6b47c82` |
| Phase 4F.2 IssueTemplatesController extraction (IssueController 2,394 → 2,339 LOC) | commit `09c2571` |
| Phase 4F.3 IssueBulkService + IssueBulkController extraction (IssueController 2,339 → 2,204 LOC) | commit `d1eab33` |
| Phase 4F.4 IssueImportService + IssueImportController extraction (IssueController 2,204 → 1,954 LOC) | commit `b2f9087` |
| Phase 5A.1 ProofOfWorkView → issues-domain/boundary (shared-web view distribution begins) | commit `6dc1609` |
| ProofOfWorkView (after 5A.1) | [ProofOfWorkView.scala](../modules/issues-domain/src/main/scala/issues/boundary/ProofOfWorkView.scala) |
| Phase 5A.2 WorkspaceTemplatesView → workspace-domain/boundary (workspaceDomain upgraded to domainBceDeps) | commit `76df331` |
| WorkspaceTemplatesView (after 5A.2) | [WorkspaceTemplatesView.scala](../modules/workspace-domain/src/main/scala/workspace/boundary/WorkspaceTemplatesView.scala) |
| Phase 5A.3 WorkflowsView → taskrun-domain/boundary (config-domain would have cycled via taskrun→config) | commit `ec431f9` |
| WorkflowsView (after 5A.3) | [WorkflowsView.scala](../modules/taskrun-domain/src/main/scala/taskrun/boundary/WorkflowsView.scala) |
| Phase 5A.4 ProjectsView → project-domain/boundary (projectDomain upgraded to domainBceDeps; board fragment pre-rendered in controller to avoid sharedWeb cycle) | commit `c555efc` |
| ProjectsView (after 5A.4) | [ProjectsView.scala](../modules/project-domain/src/main/scala/project/boundary/ProjectsView.scala) |
| Phase 5B dead-view cleanup (HealthDashboard + ConfigEditor + AgentMonitor deleted, 80 LOC) | commit `d0af48f` |
| Phase 5A.5 DemoView → demo-domain/boundary (demoDomain upgraded to domainBceDeps + sharedWebCore; SettingsView.settingsShell → SettingsShell.page direct call) | commit `4321f96` |
| DemoView (after 5A.5) | [DemoView.scala](../modules/demo-domain/src/main/scala/demo/boundary/DemoView.scala) |
| Phase 5A.6 CommandCenterView → taskrun-domain/boundary (taskrunDomain gains activityDomain dep; AgentMonitorView stats-header pre-rendered in HtmlViews) | commit `a151fd5` |
| CommandCenterView (after 5A.6) | [CommandCenterView.scala](../modules/taskrun-domain/src/main/scala/taskrun/boundary/CommandCenterView.scala) |
| Phase 5A.7 AgentMonitorView → orchestration-domain/boundary (first `boundary/` package in orchestration-domain; no build-graph changes, zero cycle risk) | commit `826dff6` |
| AgentMonitorView (after 5A.7) | [AgentMonitorView.scala](../modules/orchestration-domain/src/main/scala/orchestration/boundary/AgentMonitorView.scala) |
