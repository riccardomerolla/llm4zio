# llm4zio Project Review — 2026-04-17

Scope: state of `main` at HEAD `06669f9`. Revised 2026-04-17 (seventh pass) after phases 4C, 4D, 4E.A–4E.H landed. The connectors-UI branch has merged, the `db/` legacy module has been deleted, orchestration-domain has grown its own `control/` layer, `WorkspaceRunService` and `MessageRouter` trait halves now live in their domain modules' `entity/` packages, `AgentRegistry` has relocated to `agent-domain`, `AgentMatching` has moved out of `agent-domain` (dropping the `agentDomain → workspaceDomain` edge), and phases 4E.E–4E.H have drained 28 more root files into `orchestration-domain`, `config-domain`, `knowledge-domain`, and `shared-services`. Root `src/main/scala/` is down to **56 files**, mostly boundary controllers.

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

No structural P1 items remain. The modularization drive is effectively complete for control-layer code — remaining root files are boundary controllers (the structurally bound population) and `app/` DI wiring.

Top three actions now:

1. **Split `IssueController` (2,750 LOC)** — this is the biggest single boundary file and has clean sub-feature seams (lifecycle, comments, dispatch, timeline). Phase 4F.
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
  - Boundary controllers: `IssueController` (2,750 LOC), `AgentsController`, `SettingsController`, `ProjectsController`, plus smaller ones — cross-cut multiple domains. Structurally bound to stay in root until 4F-style sub-feature splits land.
  - `app/`: `WebServer`, `ApplicationDI`, `config/boundary/Main.scala` — the DI composition root; by design depends on everything and stays at root.
  - `mcp/`: cross-cutting MCP tool support.
- **Architectural note on 4E.H.2**: 11 of the 12 files drained in 4E.H.2 are physically housed under `orchestration-domain` but keep their original `workspace.control` / `analysis.control` package declarations. Same-package cross-module is legal in Scala, but it does mean the physical/logical layout is no longer 1:1 for these packages. A future pass could clean this up by either (a) extracting the orchestration-trait subset from `orchestration.control` so `workspace-domain` could depend on the trait without the full graph, or (b) renaming packages to match their physical home. Neither is urgent.
- **`shared-web` still holds ~20 multi-domain views** (e.g. `SettingsView`, `HealthDashboard`) awaiting distribution to owning domains.

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
6. **Split `IssueController` (2,750 LOC)** along sub-feature lines (lifecycle / comments / dispatch / timeline). Phase 4F. Biggest remaining boundary file; will only grow until split.
7. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages, starting with the cleanest single-domain set.
8. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` first (highest churn, currently zero module-scoped tests).
9. **Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component**. Sets the precedent for removing inline scripts from other Scalatags views.

### P2 — strategic

9. **Compile-time BCE enforcement**. Minimum viable: split each domain into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. Higher-effort: ArchUnit-equivalent architecture tests. This is phase 4G in the modularization plan.
10. **Split `IssueController`** (2,750 LOC) along sub-feature lines (lifecycle, comments, dispatch, timeline). Single largest boundary file; only grows until split. This is phase 4F.
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
