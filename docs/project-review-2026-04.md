# llm4zio Project Review — 2026-04-17

Scope: state of `main` at HEAD `392c7dd`. Revised 2026-04-17 (sixth pass) after phases 4C, 4D, 4E.A–4E.G landed. The connectors-UI branch has merged, the `db/` legacy module has been deleted, orchestration-domain has grown its own `control/` layer, `WorkspaceRunService` and `MessageRouter` trait halves now live in their domain modules' `entity/` packages, `AgentRegistry` has relocated to `agent-domain` (inverting the `orchestrationDomain → agentDomain` edge to its natural direction), and phases 4E.E–4E.G have drained 15 more root files into `orchestration-domain`, `config-domain`, and `shared-services`. Root `src/main/scala/` is down to **68 files**.

---

## Executive Summary

The project is in strong shape and accelerating. Foundations remain clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, `sbt compile` green, 950+ tests pass). Modularization has advanced to **339 Scala files under `modules/`** vs **68 in `src/main/scala/`** — roughly **83% modularized**.

The connectors-UI branch merged, all five outstanding P1 structural items from the previous review have landed, and three additional mechanical-move phases (4E.E–4E.G) drained 15 more root files:

- ✅ **`db/` module deleted** (phase 4D, commits `86579e6` → `06ec3d4`). `ChatRepository`, `TaskRepository`, and `ConfigRepository` were all migrated to their respective domain modules as event-sourced repositories, and the legacy package is gone. Zero `^import db\.` sites remain.
- ✅ **Orchestration leaf services moved** (phase 4E.A, commit `3ac72fd`). `AgentConfigResolver`, `AgentDispatcher`, `Llm4zioAdapters`, `OrchestratorControlPlane`, and `TaskExecutor` now live in `orchestration-domain/control/`.
- ✅ **`WorkspaceRunService` trait extracted** (phase 4E.B, commit `0c98cf5`). Trait at `workspace.entity.WorkspaceRunService`; Live stays in `workspace.control`. `registerSlot` dropped from the trait (kept as a private self-call on Live) so the public interface no longer leaks `orchestration.entity.SlotHandle`.
- ✅ **`MessageRouter` trait extracted** (phase 4E.C, commit `c38a4cf`). Trait + `MessageChannelError` in `gateway.entity`; `attachControlPlaneRouting` dropped from the trait and reborn as a standalone helper that pulls `ChannelRegistry` and `OrchestratorControlPlane` from the ZIO environment.
- ✅ **`AgentRegistry` relocated to `agent-domain`** (phase 4E.D, commit `e50eb95`). Trait in `agent.entity`; Live in `agent.control`. `orchestrationDomain` now `dependsOn(agentDomain)` instead of the reverse.
- ✅ **Phase 4E.E** (commit `6d92ce6`) — 8 root control files drained into `orchestration-domain/control/`: `IssueDispatchStatusService`, `AgentPoolManager` → `AgentPoolManagerLive`, `ParallelSessionCoordinator`, `AutoDispatcher`, `IssueAssignmentOrchestrator`, `BoardOrchestrator` (package `board.control` retained cross-module), `IssueApprovalService`, `WorkflowNotifier`.
- ✅ **Phase 4E.F** (commit `32f8835`) — 7 more root files relocated: `PromptLoader` and `PromptHelpers` → `config-domain` (package `prompts` retained, resources stay on root classpath); `GatewayService`, `IntentParser`, `MessageRouter` object, `DiscordGatewayService`, `TelegramPollingService` → `orchestration-domain` (since `gatewayDomain` cannot depend on `orchestrationDomain`).
- ✅ **Phase 4E.G** (commit `392c7dd`) — `Logger`, `RetryPolicy`, `LogTailer` from `app/control/` → `shared-services` (package `app.control` retained).

No structural P1 items remain. The remaining root files cluster around an `agent.entity.{AgentPermissions, TrustLevel}` ↔ `workspace.control` type cycle that will need its own extraction phase before the last `workspace/control/` files can move.

Top three actions now:

1. **Break the `agent.entity.{AgentPermissions, TrustLevel}` ↔ `workspace.control` cycle** — extract these types (plus any shared workspace/agent trust metadata) to a shared module so `workspace/control/{CliAgentRunner, ExecutionRuntime, …}` can move into `workspace-domain` and unblock `analysis/control/{AnalysisAgentRunner, WorkspaceAnalysisScheduler}`.
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
- **339 Scala files under `modules/`** vs **68 in `src/main/scala/`** — roughly **83% modularized**.
- Domain modules that now have `control/` populated inside the module: `agent` (incl. `AgentRegistryLive`), `analysis`, `board` (incl. `BoardOrchestrator`, `IssueApprovalService` — same-package cross-module, located under `orchestration-domain` to satisfy `dependsOn`), `checkpoint`, `daemon`, `demo`, `evolution`, `issues`, `knowledge`, `memory`, `orchestration` (incl. the full gateway channel stack after 4E.F), `project`, `sdlc`, `taskrun`, `workspace` (partial).
- `config-domain` now owns the `prompts` package (template loader + helpers).
- `shared-services` now owns `app.control.{Logger, RetryPolicy, LogTailer}`.

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
- **IT fix**: `AssignRunRequest` relocated to `workspace.entity`; 3 integration specs updated (`c0eb1cd`).

### Remaining blockers

- **One structural cycle left**: `workspace/control/*` files reference `agent.entity.{AgentPermissions, TrustLevel}`, but `agentDomain.dependsOn(workspaceDomain)` — so `workspaceDomain` cannot depend back on `agentDomain`. These 9 workspace files remain root-stuck until the trust/permissions types are extracted to a neutral module (candidate: a new `shared-trust` module or `shared-ids`).
- **68 root files** (down from 83):
  - `workspace/control/` (9): CliAgentRunner, ExecutionRuntime, GitWatcher, MergeAgentService, ProofOfWorkExtractor, RunSessionManager, WorkspaceRunLifecycleSupport, WorkspaceRunService (Live only), WorkspaceRunServiceFactory — all blocked by the `agent.entity.{AgentPermissions, TrustLevel}` cycle.
  - `analysis/control/` (2): AnalysisAgentRunner, WorkspaceAnalysisScheduler — blocked until `CliAgentRunner` moves.
  - `orchestration/control/` (1): `PlannerAgentService` — similar trust-type blocker.
  - `board/control/` (3): `IssueCreationWizard`, `IssueTimelineService`, `IssueWorkReportProjectionFactory` — candidates for 4E.H.
  - `app/control/` (2): `HealthMonitor`, `Tracing` — `HealthMonitor` can move once cross-service deps are vetted; `Tracing` has OTEL deps not yet in `shared-services`.
  - Remainder: domain boundary controllers (`IssueController`, `ProjectsController`, etc.) that cross-cut multiple domains — structurally bound to stay in root until 4F-style splits land.
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
4. ✅ ~~Move trapped root control files~~ — phases 4E.E (8 files), 4E.F (7 files), 4E.G (3 files) shipped 18 relocations.
5. **Break the `agent.entity.{AgentPermissions, TrustLevel}` ↔ `workspace.control` cycle**. Options:
   - Extract these types (and any shared trust/capability metadata) to a neutral module — `shared-trust` or fold into `shared-ids` if the types are value-typed.
   - Once extracted, all 9 remaining `workspace/control/*` files move into `workspace-domain/control/`, which unblocks the 2 `analysis/control/*` files.
6. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages, starting with the cleanest single-domain set.
7. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` first (highest churn, currently zero module-scoped tests).
8. **Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component**. Sets the precedent for removing inline scripts from other Scalatags views.

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
