# llm4zio Project Review — 2026-04-17

Scope: state of `main` at HEAD `e50eb95`. Revised 2026-04-17 (fifth pass) after phases 4C, 4D, 4E.A, 4E.B, 4E.C, and 4E.D landed. The connectors-UI branch has merged, the `db/` legacy module has been deleted, orchestration-domain has grown its own `control/` layer, `WorkspaceRunService` and `MessageRouter` trait halves now live in their domain modules' `entity/` packages, and `AgentRegistry` has relocated from `orchestration-domain` to `agent-domain` — dissolving the last structural edge that made `agentDomain` depend on `orchestrationDomain`. The `dependsOn` arrow is now inverted: `orchestrationDomain → agentDomain`.

---

## Executive Summary

The project is in strong shape and accelerating. Foundations remain clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, `sbt compile` green, 950 tests pass). Modularization has advanced to **322 Scala files under `modules/`** vs **83 still in `src/main/scala/`** — roughly **80% modularized**.

The connectors-UI branch merged, and all five outstanding P1 structural items from the previous review have landed:

- ✅ **`db/` module deleted** (phase 4D, commits `86579e6` → `06ec3d4`). `ChatRepository`, `TaskRepository`, and `ConfigRepository` were all migrated to their respective domain modules as event-sourced repositories, and the legacy package is gone. Zero `^import db\.` sites remain.
- ✅ **Orchestration leaf services moved** (phase 4E.A, commit `3ac72fd`). `AgentConfigResolver`, `AgentDispatcher`, `Llm4zioAdapters`, `OrchestratorControlPlane`, and `TaskExecutor` now live in `orchestration-domain/control/`.
- ✅ **`WorkspaceRunService` trait extracted** (phase 4E.B, commit `0c98cf5`). The trait now lives at `workspace.entity.WorkspaceRunService`; the Live implementation and its ZLayer wiring stay in `workspace.control`. `registerSlot` dropped from the trait (kept as a private self-call on Live) so the public interface no longer leaks `orchestration.entity.SlotHandle`.
- ✅ **`MessageRouter` trait extracted** (phase 4E.C, commit `c38a4cf`). The trait and `MessageChannelError` now live in `gateway.entity`; `attachControlPlaneRouting` was dropped from the trait and reborn as a standalone helper on `gateway.control.MessageRouter` that pulls `ChannelRegistry` and `OrchestratorControlPlane` from the ZIO environment — the trait no longer references orchestration types.
- ✅ **`AgentRegistry` relocated to `agent-domain`** (phase 4E.D, commit `e50eb95`). Trait moved to `agent.entity.AgentRegistry`; Live implementation moved to `agent.control.AgentRegistryLive`. The `agentDomain → orchestrationDomain` `dependsOn` edge is gone; `orchestrationDomain` now `dependsOn(agentDomain)` instead (the natural direction). `private[orchestration]` helpers were rescoped to `private[agent]`.

No structural P1 items remain. Remaining work is follow-on mechanical moves now unblocked by the four extractions above.

Top three actions now:

1. **Move the 5 orchestration-domain root control files** (`AutoDispatcher`, `IssueAssignmentOrchestrator`, `ParallelSessionCoordinator`, plus `BoardOrchestrator` and `IssueApprovalService` from `board/control/`) into their domain modules — unblocked by 4E.B (`WorkspaceRunService` trait) and 4E.D (`AgentRegistry` relocation).
2. **Move `GatewayService` and `WorkflowNotifier` into `gateway-domain/control/`** — unblocked by 4E.C.
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
- **322 Scala files under `modules/`** vs **83 in `src/main/scala/`** — roughly **80% modularized**.
- Domain modules that now have `control/` populated inside the module: `agent` (includes `AgentRegistryLive` after 4E.D), `analysis`, `board`, `checkpoint`, `daemon`, `demo`, `evolution`, `issues`, `knowledge`, `memory`, `orchestration`, `project`, `sdlc`, `taskrun`, `workspace` (partial).

### Recent gains since the 2026-04-17 review (first pass)

- **Phase 4C**: `config/control` and loader consolidated into `config-domain` (`551f797`).
- **Phase 4D.1–4D.4**: `ConfigRepositoryES` into config-domain; `ChatRepository` into conversation-domain; `TaskRepository` into taskrun-domain; `db/` package deleted (`86579e6`, `4fd26d5`, `54e4a21`, `06ec3d4`).
- **Phase 4E.A**: five orchestration leaf services moved into `orchestration-domain/control/` (`3ac72fd`).
- **Phase 4E.B**: `WorkspaceRunService` trait extracted to `workspace.entity` (`0c98cf5`).
- **Phase 4E.C**: `MessageRouter` trait + `MessageChannelError` extracted to `gateway.entity`; `attachControlPlaneRouting` split off the trait as a standalone helper (`c38a4cf`).
- **Phase 4E.D**: `AgentRegistry` relocated from `orchestration-domain` to `agent-domain` (trait in `agent.entity`, Live in `agent.control`); `orchestrationDomain` now `dependsOn(agentDomain)` — the arrow is inverted to its natural direction (`e50eb95`).
- **IT fix**: `AssignRunRequest` relocated to `workspace.entity`; 3 integration specs updated (`c0eb1cd`).

### Remaining blockers

- **Orchestration ↔ gateway ↔ workspace logical cycle**. sbt has no actual `dependsOn` cycle, but root files still reference types across these three clusters. ~19 root files remain trapped:
  - `orchestration/control/`: AgentPoolManager, AutoDispatcher, IssueAssignmentOrchestrator, IssueDispatchStatusService, ParallelSessionCoordinator, PlannerAgentService. (`AgentRegistry` already moved in 4E.D; `AgentPoolManager` and `IssueDispatchStatusService` are now unblocked.)
  - `gateway/control/`: GatewayService, MessageRouter (object only — the trait is now in `gateway.entity`), WorkflowNotifier, DiscordGatewayService, IntentParser, TelegramPollingService.
  - `workspace/control/`: CliAgentRunner, ExecutionRuntime, GitWatcher, MergeAgentService, ProofOfWorkExtractor, RunSessionManager, WorkspaceRunLifecycleSupport, WorkspaceRunService (Live only — the trait is now in `workspace.entity`), WorkspaceRunServiceFactory.
  - `board/control/`: BoardOrchestrator, IssueApprovalService (unblocked by 4E.B — eligible to move now that they can type-reference the trait from `workspace.entity`).
- No remaining `dependsOn` inversion work. After 4E.D, all extractions needed to move the trapped root files into their domain modules are in place; the remaining work is mechanical.
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
- Only 6 `Throwable` sites remain, all at legitimate infrastructure boundaries (`WebServer`, `ApplicationDI`, `Logger`, `MemoryController`, `EmbeddingService`). Should be documented as allowed in CLAUDE.md.

---

## 4. Testing

- 950 unit tests pass and 18 integration tests pass; `sbt compile` green on current HEAD.
- Module-local tests now exist for: `activity`, `conversation`, `memory`, `knowledge`, `issues`, `project`, `evolution`, `demo`, `board`, `taskrun`. Examples: `ActivityRepositoryESSpec`, `TaskRepositoryESSpec` (migrated from root when their repos moved).
- Still zero module-scoped tests: `analysis`, `checkpoint`, `config`, `daemon`, `gateway`, `orchestration`, `plan`. High-churn ones (`checkpoint`, `gateway`, `orchestration`) are the most urgent backfill targets.
- `ConnectorConfigResolverSpec` mode-scoped coverage added on the connectors branch — no longer a gap.

---

## 5. Build Health

- `build.sbt`: **529 lines across 34 projects**. Moderate complexity, still manageable as a single file. After 4E.D the `dependsOn` graph has a cleaner shape: `orchestrationDomain → agentDomain → configDomain` (no back-edge).
- `-Werror` is on for unused imports. No module-layer or package-layer compile checks beyond that.
- Test dependency discipline: `zio-eclipsestore-gigamap % Test` consistently added to domain modules that need ES-backed specs (activity, evolution, issues, knowledge, taskrun, conversation, config).

---

## 6. Recommendations (Prioritized)

### P0 — landed / none outstanding

The previous pass's P0 items all shipped. No critical items block day-to-day work.

### P1 — next sprint (reorganized around what's left)

1. ✅ ~~Extract `WorkspaceRunService` trait~~ — shipped in 4E.B.
2. ✅ ~~Extract `MessageRouter` trait to `gateway.entity`~~ — shipped in 4E.C.
3. ✅ ~~Relocate `AgentRegistry` out of `orchestration-domain`~~ — shipped in 4E.D. The `agentDomain → orchestrationDomain` edge is gone.
4. **Move the trapped root control files into their domain modules**. All extractions needed to do this are now in place:
   - `board/control/`: `BoardOrchestrator`, `IssueApprovalService` (unblocked by 4E.B).
   - `orchestration/control/`: `AutoDispatcher`, `IssueAssignmentOrchestrator`, `ParallelSessionCoordinator`, `AgentPoolManager`, `IssueDispatchStatusService` (the last two unblocked by 4E.D).
   - `gateway/control/`: `GatewayService`, `WorkflowNotifier` (unblocked by 4E.C).
5. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages, starting with the cleanest single-domain set.
6. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` first (highest churn, currently zero module-scoped tests).
7. **Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component**. Sets the precedent for removing inline scripts from other Scalatags views.

### P2 — strategic

7. **Compile-time BCE enforcement**. Minimum viable: split each domain into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. Higher-effort: ArchUnit-equivalent architecture tests. This is phase 4G in the modularization plan.
8. **Split `IssueController`** (2,750 LOC) along sub-feature lines (lifecycle, comments, dispatch, timeline). Single largest boundary file; only grows until split. This is phase 4F.
9. **Introduce `CHANGELOG.md` and a lightweight release-branch policy**. The modularization plan would benefit from visible "epoch" markers.
10. **Document the 6 allowed `Throwable` sites in CLAUDE.md** so future reviewers don't re-litigate them.
11. **Document the mixed route-shape convention** (`/settings/connectors/{api|cli}` split vs `/agents/{name}/connector/mode?mode=` merged).

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
