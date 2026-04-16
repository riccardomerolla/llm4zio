# llm4zio Project Review — 2026-04-16

Scope: state of the `main` branch plus the in-flight `feature/connectors-ui-redesign` branch at commit `c3e7933`. Written to be scannable in under five minutes.

---

## Executive Summary

The project is in solid shape. Foundations are clean (no `var`, no `Throwable` leaks in business code, `PersistenceError` migration complete, 1,121+ tests green), and modularization is roughly 58% done with 23 domain modules established.

The two risks worth acting on now:

1. **The current branch ships broken UI.** `SettingsView` generates HTMX requests to `/settings/connectors/agent/{name}/override*` routes that no controller handles, and the helpers that emit that markup (`agentRow`, `agentOverrideForm`) are never called from `connectorsTab` anyway — so they're both dead code and a latent bug. Must reconcile before merge.
2. **The orchestration ↔ gateway ↔ workspace circular dependency is still blocking ~42% of the codebase from leaving `src/main/scala/`.** This is the single biggest drag on the modularization plan and keeps accumulating root-level files (IssueController alone is 2,750 LOC).

Top three actions: (a) decide the fate of the agent override table in `SettingsView` and either wire it or delete it, (b) land the ConnectorConfigResolver mode-scoped test coverage, (c) extract a trait interface to break the orchestration/gateway cycle.

---

## 1. Current Branch: `feature/connectors-ui-redesign`

Five commits, ~1,000 lines changed, 8 new unit tests. Feature intent is a clean split of connector defaults into API/CLI cards with per-agent overrides — the direction is good, the landing is incomplete.

### Critical — must fix before merge

- **Dead helpers + missing routes.** `agentRow()` and `agentOverrideForm()` in [SettingsView.scala:383](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L383) and [SettingsView.scala:446](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L446) emit HTMX URLs like `/settings/connectors/agent/{name}/override-form` and `/settings/connectors/agent/{name}/override`. A grep across `src/main/scala/config/boundary` finds **zero** matching routes. Separately, those helpers are never called from [SettingsView.scala:27 `connectorsTab`](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L27) — the `agents` and `agentOverrides` parameters are carried through unused (lines 31–32). Either wire the helpers + add the controller routes, or delete both helpers and the unused params.

### Important

- **Route-shape inconsistency.** [SettingsController](src/main/scala/config/boundary/SettingsController.scala) adopts split routes `/settings/connectors/api` and `/settings/connectors/cli`. The orphaned agent-override URLs use a single merged `…/override` endpoint with a `mode` query param. Pick one shape and apply it consistently when the routes land.
- **Silent fallbacks in resolver.** [ConnectorConfigResolver.scala:84-89](src/main/scala/config/control/ConnectorConfigResolver.scala#L84) chains `.flatMap(parseConnectorId).orElse(…).getOrElse(defaultConnector)` with no logging. A misspelled connector id produces a silent default. At minimum, log at `debug` when a parse attempt fails; ideally return a validation error at admin save time.
- **Test gap: mode-scoped key resolution.** The new `ConnectorConfigResolverSpec` covers global and flat-agent keys but does not exercise the mode-scoped filter at [ConnectorConfigResolver.scala:52-55](src/main/scala/config/control/ConnectorConfigResolver.scala#L52), which is the core logic of the redesign. Add at least one test per branch of the 5-step resolution order documented at lines 67–72.

### Minor

- Inline `<script>` block in [SettingsView.scala:623](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L623) uses `var`. Prefer `const`/`let` or — better — move to a Lit 3 component (`ab-env-vars-editor`) per the CLAUDE.md frontend convention.
- The env-var form roundtrip relies on client-side JS (`addEnvVarRow`) to reconstruct `KEY=VALUE` pairs before submit. No server-side validation of the assembled shape. Not exploitable, just fragile.

### Note on the `checkpointConfigStore` call

A prior pass flagged missing persistence in the agent mode toggle. Verified: [AgentsController.scala:381-382](src/main/scala/config/boundary/AgentsController.scala#L381) does call `checkpointConfigStore` after `upsertSetting`. Not an issue.

---

## 2. Architecture & Modularization

### Progress (per [CLAUDE.md](CLAUDE.md) and `.claude/plans/snoopy-tinkering-hejlsberg.md`)

- 6 foundation modules + 23 domain modules in place; all 23 have `entity/` layers.
- ~58% of code moved out of root; **~113 Scala files and 33,822 LOC still in `src/main/scala/`**.
- Controllers split: 26 in domain `boundary/` packages, 28 still in root.

### Blockers

- **Orchestration ↔ gateway ↔ workspace cycle** remains unresolved. 11 orchestration files and 16 gateway files still in root because they close the cycle. The trait-extraction pattern used for `AgentPoolManager`, `TaskExecutor`, and `WorkReportEventBus` is the known escape hatch — apply it to the remaining cross-cutting services.
- **Legacy `db/` module** still ships `ChatRepository`, `TaskRepository`, `ConfigRepository`. Five root files import from it. These are candidates for event-sourced rewrites so the whole `db/` module can be deleted.
- **`shared-web` holds 20 multi-domain views** (e.g. `SettingsView`, `HealthDashboard`) awaiting distribution to owning domains. Documented as transitional; still the right target.

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

- 1,121+ unit tests and 18 integration tests pass.
- 10 of 23 domain modules have **no** domain-scoped tests: `analysis`, `checkpoint`, `config`, `daemon`, `gateway`, `issues`, `knowledge`, `memory`, `orchestration`, `plan`. Their tests live in root or don't exist at all.
- High-churn modules (`issues`, `checkpoint`, `knowledge`) are the most urgent to backfill.
- New `ConnectorConfigResolverSpec` misses the mode-scoped path — see §1.

---

## 5. Build Health

- `build.sbt`: 508 lines, 63 `lazy val`s, 29 `dependsOn` edges across 31 modules.
- Moderate complexity. Still manageable as a single file; revisit if another dozen modules get added.
- `-Werror` is on for unused imports. No module-layer or package-layer compile checks beyond that.

---

## 6. Recommendations (Prioritized)

### P0 — before merging `feature/connectors-ui-redesign`

1. Decide the fate of the agent override table in `SettingsView`:
   - **Option A (recommended):** delete `agentRow`, `agentOverrideForm`, the unused `agents` and `agentOverrides` params on `connectorsTab`. Let the Agents page be the sole home for per-agent overrides — that's what the 5b681fd commit message promised.
   - **Option B:** implement the three missing controller routes (`GET .../override-form`, `POST .../override`, `DELETE .../override`) and call `agentRow`/`agentOverrideForm` from `connectorsTab`.
2. Reconcile route shape across controllers (split API/CLI vs merged `?mode=`). Pick one.
3. Add mode-scoped resolution tests to `ConnectorConfigResolverSpec` — at least one test hitting each of the five resolution steps.
4. Log (at `debug`) when `parseConnectorId` rejects an input and the resolver falls back to default.

### P1 — next sprint

5. Break the orchestration ↔ gateway ↔ workspace cycle by extracting trait interfaces into `orchestration-domain/entity` (same pattern as `AgentPoolManager`). This unblocks moving ~27 root files into modules.
6. Migrate `db/ChatRepository`, `db/TaskRepository`, `db/ConfigRepository` to event-sourced repositories, then delete the `db/` module entirely.
7. Distribute the top 5 views from `shared-web/` to their owning domain `boundary/` packages, starting with whichever has the cleanest single-domain dependency set.
8. Backfill module-scoped tests for `checkpoint`, `issues`, `knowledge` first (highest churn).
9. Replace the inline JS in `SettingsView` with a Lit 3 `ab-env-vars-editor` component. Sets a precedent for removing other inline scripts from Scalatags views.

### P2 — strategic

10. Add compile-time BCE enforcement. Minimum viable: split each domain into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. Higher-effort option: ArchUnit-equivalent architecture tests.
11. Consider splitting `IssueController` (2,750 LOC) along sub-feature lines (lifecycle, comments, dispatch, timeline).
12. Introduce a `CHANGELOG.md` and a lightweight release-branch policy. The modularization plan would benefit from visible "epoch" markers.
13. Document the 6 allowed `Throwable` sites in CLAUDE.md so future reviewers don't re-litigate them.

---

## Appendix: Key File References

| Finding | File |
|---|---|
| Dead helpers | [SettingsView.scala:383](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L383), [SettingsView.scala:446](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L446) |
| Unused params | [SettingsView.scala:31](modules/shared-web/src/main/scala/shared/web/SettingsView.scala#L31) |
| Missing routes target | `SettingsController` (search `/settings/connectors/agent` — 0 matches) |
| Persistence-OK mode toggle | [AgentsController.scala:377](src/main/scala/config/boundary/AgentsController.scala#L377) |
| Silent resolver fallback | [ConnectorConfigResolver.scala:84](src/main/scala/config/control/ConnectorConfigResolver.scala#L84) |
| Mode-scoped resolution logic | [ConnectorConfigResolver.scala:52](src/main/scala/config/control/ConnectorConfigResolver.scala#L52) |
| Modularization plan | [snoopy-tinkering-hejlsberg.md](.claude/plans/snoopy-tinkering-hejlsberg.md) |
| Architecture conventions | [CLAUDE.md](CLAUDE.md) |
