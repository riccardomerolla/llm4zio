# llm4zio Project Review — 2026-04-17

Scope: state of `feature/connectors-ui-redesign` at HEAD (`5492f97`). Revised 2026-04-17 after the original 2026-04-16 pass — the branch-level critical items have since been resolved; this update records what shipped and redirects focus to the remaining strategic work.

---

## Executive Summary

The project is in solid shape and improving. Foundations are clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, `sbt compile` green), and the modularization has advanced to **295 Scala files under `modules/`** vs **103 still in `src/main/scala/`** — roughly 74% modularized.

The connectors-UI branch is near merge-ready: the dead agent-override helpers were removed in `684d1cb` / `42aff8e`, mode-scoped resolver tests were added, and the resolver now emits `ZIO.logDebug` on parse failures. What remains before merge is largely cosmetic.

The two risks worth acting on now:

1. **The orchestration ↔ gateway ↔ workspace circular dependency is still blocking ~27 root files from leaving `src/main/scala/`.** This is the single biggest drag on the modularization plan. `IssueController` alone is 2,750 LOC at root and depends on the cycle.
2. **29 files still import from legacy `db/`.** The `ChatRepository` / `TaskRepository` / `ConfigRepository` trio is the critical mass blocking `db/` deletion, and several of those 29 files are the same ones trapped by the cycle above.

Top three actions: (a) extract trait interfaces to break the orchestration/gateway cycle (same pattern already used for `AgentPoolManager`), (b) migrate the three legacy `db/` repositories to event-sourced equivalents and delete `db/`, (c) pick one P1 item from §6 to land this sprint.

---

## 1. Current Branch: `feature/connectors-ui-redesign`

Feature intent: split connector defaults into API/CLI cards, with per-agent overrides exposed via the Agents page rather than Settings. As of HEAD `5492f97`, the direction and landing are both in good shape.

### Resolved since 2026-04-16 review

- ✅ **Dead helpers + missing routes removed** (commits `684d1cb`, `42aff8e`). `agentRow()` / `agentOverrideForm()` and the unused `agents` / `agentOverrides` parameters on `connectorsTab` were deleted. `SettingsView.connectorsTab` is now a clean 3-arg method ([SettingsView.scala:25](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L25)).
- ✅ **`checkpointConfigStore` confirmed present** in the agent mode toggle at [AgentsController.scala:386](src/main/scala/config/boundary/AgentsController.scala#L386).
- ✅ **Mode-scoped resolver tests added** — `ConnectorConfigResolverSpec` is now 207 lines and exercises each branch of the 5-step resolution order (api-default, cli-default, agent mode-scoped override, agent flat, legacy `ai.*` fallback).
- ✅ **Debug logging on parse failures** added at [ConnectorConfigResolver.scala:36](src/main/scala/config/control/ConnectorConfigResolver.scala#L36) via `ZIO.logDebug`.

### Remaining — minor, optional before merge

- **Inline `<script>` still uses `var`** at [SettingsView.scala:369](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L369) (the `envVarsScript` / `addEnvVarRow` block). Prefer `const`/`let`, or replace with a Lit 3 component (`ab-env-vars-editor`) per the CLAUDE.md frontend convention. Not a merge blocker.
- **Env-var form roundtrip** relies entirely on client-side JS to reconstruct `KEY=VALUE` pairs. No server-side shape validation. Fragile but not exploitable — follow-up task.
- **Route-shape inconsistency** across the connector endpoints (split `/settings/connectors/api` vs `/settings/connectors/cli`, merged `/agents/{name}/connector/mode?mode=...`) is now the permanent shape. Document the convention so future additions match.

---

## 2. Architecture & Modularization

### Progress (per [CLAUDE.md](CLAUDE.md) and `.claude/plans/snoopy-tinkering-hejlsberg.md`)

- 6 foundation modules + 23 domain modules in place; all 23 have `entity/` layers.
- **295 Scala files under `modules/`** vs **103 still in `src/main/scala/`** — roughly 74% modularized.
- Recent gains on this branch (beyond connectors): `memory-domain` (`9792130`), `knowledge-domain` (`5492f97`), and phase-4A `daemon` / `sdlc` boundary moves (`79cca80`) all landed.
- In-flight (uncommitted on HEAD): `demo`, `evolution/control/EvolutionTemplates`, plus entity-test redistribution for `issues`, `knowledge`, `project`. Also `shared/store/DataStoreModule` moved to `shared-store-core` as a foundation improvement.

### Blockers

- **Orchestration ↔ gateway ↔ workspace cycle** remains unresolved. Orchestration `control/` still has 7 root files (`AgentConfigResolver`, `AgentDispatcher`, `IssueAssignmentOrchestrator`, `Llm4zioAdapters`, `PlannerAgentService`, `TaskExecutor`); gateway has 5 (`GatewayService`, `MessageRouter`, `WorkflowNotifier`, telegram boundary pair); workspace has 3 (`RunSessionManager`, `WorkspaceRunLifecycleSupport`, `WorkspaceRunService`). The trait-extraction pattern used for `AgentPoolManager`, `TaskExecutor`, and `WorkReportEventBus` is the known escape hatch — apply it to the remaining cross-cutting services.
- **Legacy `db/` module** still imported by **29 files** in `src/main/scala/` (not 5 as previously stated). `ChatRepository`, `TaskRepository`, `ConfigRepository` are the three repositories holding it alive. These are candidates for event-sourced rewrites so the whole `db/` module can be deleted.
- **`shared-web` holds ~20 multi-domain views** (e.g. `SettingsView`, `HealthDashboard`) awaiting distribution to owning domains. Documented as transitional; still the right target.

### Layer completeness

- 9 modules are missing a `boundary/` layer (analysis, conversation, evolution, knowledge, orchestration, plan, project, sdlc, + 1).
- 22 modules are missing a `control/` layer at module scope (logic still in root).
- **No compile-time enforcement** of BCE directionality. `control` could import `boundary` today and the build would pass.

---

## 3. Code Quality & Conventions

Healthy across the board:

- Zero `var` in `src/main/scala/`.
- Zero `db.PersistenceError` imports — `shared.errors.PersistenceError` migration is complete.
- All package names are singular.
- Only 6 `Throwable` sites remain, all at legitimate infrastructure boundaries: `WebServer`, `ApplicationDI`, `Logger`, `MemoryController`, `EmbeddingService`. These should be documented as allowed in CLAUDE.md rather than treated as debt.

---

## 4. Testing

- 1,121+ unit tests and 18 integration tests pass; `sbt compile` green on current HEAD.
- After recent moves, `memory`, `knowledge`, `issues`, `project`, `evolution`, `demo` have domain-local tests; `analysis`, `checkpoint`, `config`, `daemon`, `gateway`, `orchestration`, `plan` still have zero module-scoped tests (tests live in root or not at all).
- High-churn modules (`checkpoint`, `gateway`, `orchestration`) are the most urgent backfill targets.
- `ConnectorConfigResolverSpec` mode-scoped coverage added on this branch — no longer a gap.

---

## 5. Build Health

- `build.sbt`: 511 lines across 31 modules.
- Moderate complexity. Still manageable as a single file; revisit if another dozen modules get added.
- `-Werror` is on for unused imports. No module-layer or package-layer compile checks beyond that.

---

## 6. Recommendations (Prioritized)

### P0 — before merging `feature/connectors-ui-redesign`

All four original P0 items shipped in commits `684d1cb` and `42aff8e` along with the expanded `ConnectorConfigResolverSpec`. Nothing critical remains blocking merge. Optional polish:

1. Replace `var` with `const`/`let` in `SettingsView`'s inline `envVarsScript`, or extract to a Lit 3 component.
2. Document the mixed route-shape convention (`/settings/connectors/{api|cli}` split vs `/agents/{name}/connector/mode?mode=` merged) so future endpoints stay consistent.

### P1 — next sprint

3. Break the orchestration ↔ gateway ↔ workspace cycle by extracting trait interfaces into `orchestration-domain/entity` (same pattern as `AgentPoolManager`). This unblocks moving ~27 root files into modules.
4. Migrate `db/ChatRepository`, `db/TaskRepository`, `db/ConfigRepository` to event-sourced repositories, then delete the `db/` module entirely.
5. Distribute the top 5 views from `shared-web/` to their owning domain `boundary/` packages, starting with whichever has the cleanest single-domain dependency set.
6. Backfill module-scoped tests for `checkpoint`, `gateway`, `orchestration` first (highest churn + currently zero module tests).
7. Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component. Sets a precedent for removing other inline scripts from Scalatags views.

### P2 — strategic

8. Add compile-time BCE enforcement. Minimum viable: split each domain into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. Higher-effort option: ArchUnit-equivalent architecture tests.
9. Split `IssueController` (2,750 LOC) along sub-feature lines (lifecycle, comments, dispatch, timeline). This is the single largest boundary file and will only grow until split.
10. Introduce a `CHANGELOG.md` and a lightweight release-branch policy. The modularization plan would benefit from visible "epoch" markers.
11. Document the 6 allowed `Throwable` sites in CLAUDE.md so future reviewers don't re-litigate them.

---

## Appendix: Key File References

| Finding | File |
|---|---|
| `connectorsTab` (clean 3-arg form) | [SettingsView.scala:25](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L25) |
| Mode toggle with persistence checkpoint | [AgentsController.scala:381-423](src/main/scala/config/boundary/AgentsController.scala#L381) |
| Resolver debug logging | [ConnectorConfigResolver.scala:36](src/main/scala/config/control/ConnectorConfigResolver.scala#L36) |
| Mode-scoped resolution logic | [ConnectorConfigResolver.scala:83-90](src/main/scala/config/control/ConnectorConfigResolver.scala#L83) |
| Inline `var` in `envVarsScript` | [SettingsView.scala:369-404](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L369) |
| Legacy `db/` import sites (29) | grep `^import db\.` across `src/main/scala/` |
| Modularization plan | [snoopy-tinkering-hejlsberg.md](.claude/plans/snoopy-tinkering-hejlsberg.md) |
| Architecture conventions | [CLAUDE.md](CLAUDE.md) |
