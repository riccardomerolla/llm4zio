# llm4zio Project Review — 2026-04-17

Scope: state of `main` at HEAD `3ac72fd` (plus the pending 4E.B commit). Revised 2026-04-17 (third pass) after phases 4C, 4D, 4E.A, and 4E.B landed. The connectors-UI branch has merged, the `db/` legacy module has been deleted, orchestration-domain has grown its own `control/` layer, and the `WorkspaceRunService` trait now lives in `workspace-domain/entity`. This revision records what shipped and narrows focus to the remaining cycle work.

---

## Executive Summary

The project is in strong shape and accelerating. Foundations remain clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, `sbt compile` green). Modularization has advanced to **318 Scala files under `modules/`** vs **86 still in `src/main/scala/`** — roughly **79% modularized**, up from 74% at the previous review.

The connectors-UI branch merged. More importantly, two of the three P1 items from the previous review have landed:

- ✅ **`db/` module deleted** (phase 4D, commits `86579e6` → `06ec3d4`). `ChatRepository`, `TaskRepository`, and `ConfigRepository` were all migrated to their respective domain modules as event-sourced repositories, and the legacy package is gone. Zero `^import db\.` sites remain.
- ✅ **Orchestration leaf services moved** (phase 4E.A, commit `3ac72fd`). `AgentConfigResolver`, `AgentDispatcher`, `Llm4zioAdapters`, `OrchestratorControlPlane`, and `TaskExecutor` now live in `orchestration-domain/control/`.
- ✅ **`WorkspaceRunService` trait extracted** (phase 4E.B). The trait now lives at `workspace.entity.WorkspaceRunService`; the Live implementation and its ZLayer wiring stay in `workspace.control` (they pull in cross-domain deps that `workspace-domain` cannot take). `registerSlot` dropped from the trait (kept as a private self-call on Live) so the public interface no longer leaks `orchestration.entity.SlotHandle`.

The one remaining P1 risk is unchanged in shape but smaller in surface area:

1. **The orchestration ↔ gateway ↔ workspace logical cycle still traps ~21 root files under `src/main/scala/`.** Orchestration has 6 root control files left (AgentPoolManager, AutoDispatcher, IssueAssignmentOrchestrator, IssueDispatchStatusService, ParallelSessionCoordinator, PlannerAgentService); gateway has 6 (GatewayService, MessageRouter, WorkflowNotifier, DiscordGatewayService, IntentParser, TelegramPollingService); workspace has 9 control files. Breaking `WorkspaceRunService` into a trait in `workspace-domain/entity` is the next lever — that alone unblocks BoardOrchestrator, IssueApprovalService, AutoDispatcher, ParallelSessionCoordinator, and IssueAssignmentOrchestrator.

Top three actions now: (a) extract `WorkspaceRunService` trait to `workspace.entity` (resolves the SlotHandle/orchestration.entity dependency first), (b) extract `MessageRouter` trait to `gateway.entity` with OCP-dependent plumbing split off, (c) resolve the `agentDomain → orchestrationDomain` dependency so `AgentPoolManager` and `IssueDispatchStatusService` can move.

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

- 6 foundation modules + 25 domain modules in `build.sbt` (34 `lazy val` project declarations total, up from 31). All domain modules have `entity/` layers.
- **318 Scala files under `modules/`** vs **86 in `src/main/scala/`** — roughly **79% modularized** (was 74%).
- Domain modules that now have `control/` populated inside the module: `agent`, `analysis`, `board`, `checkpoint`, `daemon`, `demo`, `evolution`, `issues`, `knowledge`, `memory`, `orchestration` (new), `project`, `sdlc`, `taskrun`, `workspace` (partial).

### Recent gains since the 2026-04-17 review (first pass)

- **Phase 4C**: `config/control` and loader consolidated into `config-domain` (`551f797`).
- **Phase 4D.1–4D.4**: `ConfigRepositoryES` into config-domain; `ChatRepository` into conversation-domain; `TaskRepository` into taskrun-domain; `db/` package deleted (`86579e6`, `4fd26d5`, `54e4a21`, `06ec3d4`).
- **Phase 4E.A**: five orchestration leaf services moved into `orchestration-domain/control/` (`3ac72fd`).
- **IT fix**: `AssignRunRequest` relocated to `workspace.entity`; 3 integration specs updated (`c0eb1cd`).

### Remaining blockers

- **Orchestration ↔ gateway ↔ workspace logical cycle**. sbt itself has no actual `dependsOn` cycle — `workspaceDomain` and `gatewayDomain` do not depend on `orchestrationDomain` — but root files reference types across these three clusters. ~21 root files remain trapped:
  - `orchestration/control/`: AgentPoolManager, AutoDispatcher, IssueAssignmentOrchestrator, IssueDispatchStatusService, ParallelSessionCoordinator, PlannerAgentService.
  - `gateway/control/`: GatewayService, MessageRouter, WorkflowNotifier, DiscordGatewayService, IntentParser, TelegramPollingService.
  - `workspace/control/`: CliAgentRunner, ExecutionRuntime, GitWatcher, MergeAgentService, ProofOfWorkExtractor, RunSessionManager, WorkspaceRunLifecycleSupport, WorkspaceRunService, WorkspaceRunServiceFactory.
  - `board/control/`: BoardOrchestrator, IssueApprovalService (block on `WorkspaceRunService`).
- **`agentDomain → orchestrationDomain` edge** (for `orchestration.entity.AgentRegistry`) prevents `AgentPoolManager` and `IssueDispatchStatusService` from moving into `agent-domain` or `orchestration-domain` without first relocating `AgentRegistry` to a shared foundation spot.
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

- 1,121+ unit tests and 18 integration tests pass; `sbt compile` green on current HEAD.
- Module-local tests now exist for: `activity`, `conversation`, `memory`, `knowledge`, `issues`, `project`, `evolution`, `demo`, `board`, `taskrun`. Examples: `ActivityRepositoryESSpec`, `TaskRepositoryESSpec` (migrated from root when their repos moved).
- Still zero module-scoped tests: `analysis`, `checkpoint`, `config`, `daemon`, `gateway`, `orchestration`, `plan`. High-churn ones (`checkpoint`, `gateway`, `orchestration`) are the most urgent backfill targets.
- `ConnectorConfigResolverSpec` mode-scoped coverage added on the connectors branch — no longer a gap.

---

## 5. Build Health

- `build.sbt`: **529 lines across 34 projects** (up from 511 / 31). Moderate complexity, still manageable as a single file.
- `-Werror` is on for unused imports. No module-layer or package-layer compile checks beyond that.
- Test dependency discipline: `zio-eclipsestore-gigamap % Test` consistently added to domain modules that need ES-backed specs (activity, evolution, issues, knowledge, taskrun, conversation, config).

---

## 6. Recommendations (Prioritized)

### P0 — landed / none outstanding

The previous pass's P0 items all shipped. No critical items block day-to-day work.

### P1 — next sprint (reorganized around what's left)

1. ✅ ~~Extract `WorkspaceRunService` trait~~ — shipped in 4E.B. Follow-on: move `BoardOrchestrator`, `IssueApprovalService`, `AutoDispatcher`, `ParallelSessionCoordinator`, and `IssueAssignmentOrchestrator` into their domain modules now that they can type-reference the trait without crossing `workspace.control`.
2. **Extract `MessageRouter` trait to `gateway.entity`**. Split `attachControlPlaneRouting` (which references OCP) off from the trait. Unblocks moving `GatewayService` / `WorkflowNotifier` into `gateway-domain/control/`.
3. **Relocate `AgentRegistry`** (currently `orchestration.entity.AgentRegistry`) to a genuinely shared spot so `agentDomain` no longer needs `orchestrationDomain` as a dep. Unblocks moving `AgentPoolManager` and `IssueDispatchStatusService`.
4. **Distribute the top 5 multi-domain views out of `shared-web/`** to their owning `boundary/` packages, starting with the cleanest single-domain set.
5. **Backfill module-scoped tests** for `checkpoint`, `gateway`, `orchestration` first (highest churn, currently zero module-scoped tests).
6. **Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component**. Sets the precedent for removing inline scripts from other Scalatags views.

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
| WorkspaceRunService trait (P1 extraction target) | [WorkspaceRunService.scala:24-33](../src/main/scala/workspace/control/WorkspaceRunService.scala#L24) |
| Modularization plan | [snoopy-tinkering-hejlsberg.md](../.claude/plans/snoopy-tinkering-hejlsberg.md) |
| Architecture conventions | [CLAUDE.md](../CLAUDE.md) |
| Phase 4D.4 `db/` deletion | commit `06ec3d4` |
| Phase 4E.A orchestration leaf moves | commit `3ac72fd` |
