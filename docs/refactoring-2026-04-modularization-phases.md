# Modularization — Remaining Phases (2026-04-16)

Successor to `.claude/plans/snoopy-tinkering-hejlsberg.md` (Phases 0–3). Captures the plan for draining the remaining **113 main sources and 159 test sources** out of `src/main/scala/` and `src/test/scala/` into their domain modules.

---

## Context

Current build output:

```
[info] compiling 113 Scala sources to target/scala-3.5.2/classes
[info] compiling 159 Scala sources to target/scala-3.5.2/test-classes
```

Target:

- `~25` main sources in root (app bootstrap, MCP bridge, shared utilities, legacy glue only)
- `~40` test sources in root (shared fixtures, cross-domain integration tests, app bootstrap tests)

The rest belongs in `modules/*-domain/`. Phases are ordered by (a) risk, (b) prerequisite chain, (c) value delivered per unit of work. Each phase should land as **one PR / one commit per domain moved**, with compile + targeted tests green before the next phase starts.

---

## Inventory by Domain (what remains in root)

### Main sources (`src/main/scala/`, 113 files)

| Package | Files | Target module | Risk |
|---|---:|---|---|
| `app/` | 18 | **stays** (DI hub, WebServer) | n/a |
| `mcp/` | 4 | **stays** (cross-cutting) | n/a |
| `prompts/`, `shared/`, `web/` | ~6 | **stays** (foundation / cross-cutting) | n/a |
| `gateway/` | 16 | `gateway-domain` | **high** — cycle with orchestration/workspace |
| `orchestration/` | 11 | `orchestration-domain` | **high** — cycle nexus |
| `workspace/` | 10 | `workspace-domain` | **high** — cycle |
| `config/` | 10 | `config-domain` | low |
| `board/` | 6 | `board-domain` | low |
| `conversation/` | 5 | `conversation-domain` | medium — legacy `db/ChatRepository` |
| `taskrun/` | 5 | `taskrun-domain` | medium — legacy `db/TaskRepository` |
| `db/` | 6 | **delete** after migrations | medium |
| `memory/` | 3 | `memory-domain` | low |
| `analysis/` | 2 | `analysis-domain` | low (~1.4k LOC total) |
| `demo/` | 3 | `demo-domain` | low |
| `knowledge/` | 2 | `knowledge-domain` | low |
| `issues/` | 2 | `issues-domain` | medium (`IssueController` is 2,750 LOC) |
| `daemon/` | 1 | `daemon-domain` | **trivial** |
| `sdlc/` | 1 | `sdlc-domain` | **trivial** |
| `project/` | 1 | `project-domain` | low |
| `evolution/` | 1 | `evolution-domain` | low |

### Test sources (`src/test/scala/`, 159 files)

- **Stay in root (~40 files):** `shared/` (testfixtures), `app/` (bootstrap), `web/` (multi-domain routing), `integration/` (cross-domain E2E), `architecture/`.
- **Move with their domain:** orchestration (19), workspace (16), gateway (13), board (6), knowledge (4), issues (4), config (4), agent (4), analysis (3), daemon (3), demo (3), memory (2), decision (2), evolution (2), conversation (2), plan (2), project (2), mcp (2), specification (1), taskrun (1), sdlc (1), governance (1), prompts (1), db (4 — delete with legacy), models (4 — split across domains).

### Fat-file split candidates (>500 LOC, separate from phase plan)

- `issues/boundary/IssueController.scala` — 2,750 LOC
- `mcp/GatewayMcpTools.scala` — 1,483 LOC
- `orchestration/control/PlannerAgentService.scala` — 1,467 LOC
- `conversation/boundary/ChatController.scala` — 1,427 LOC
- `config/boundary/AgentsController.scala` — 1,263 LOC
- `workspace/boundary/WorkspacesController.scala` — 1,166 LOC
- `gateway/boundary/telegram/TelegramChannel.scala` — 925 LOC
- `workspace/control/WorkspaceRunService.scala` — 924 LOC
- `analysis/control/AnalysisAgentRunner.scala` — 895 LOC
- `config/boundary/SettingsController.scala` — 688 LOC

These files should move first, split second (within the destination module, in a follow-up).

---

## Phases

Target cadence: roughly one phase per working week. Adjust to taste.

### Phase 4A — Trivial single-domain leaves (~½ day)

Move files with zero cross-module coupling. Low review cost, quick morale win, shrinks root visibly.

**Moves:**
- `daemon/boundary/DaemonsController.scala` → `modules/daemon-domain/src/main/scala/daemon/boundary/`
- `sdlc/boundary/SdlcDashboardController.scala` → `modules/sdlc-domain/src/main/scala/sdlc/boundary/`
- `analysis/control/AnalysisAgentRunner.scala` → `modules/analysis-domain/src/main/scala/analysis/control/`
- `analysis/control/WorkspaceAnalysisScheduler.scala` → `modules/analysis-domain/src/main/scala/analysis/control/`
- Matching test files (daemon 3, sdlc 1, analysis 3).

**Build changes:** each destination module gains `boundary/` or `control/` layers and `libraryDependencies` matching `domainBceDeps` or `domainDeps`. No new `dependsOn` edges expected; verify by grepping imports before the move.

**Verification:** `sbt compile` + `sbt 'daemonDomain/test'` + `sbt 'sdlcDomain/test'` + `sbt 'analysisDomain/test'`. Main count should drop by ~4, test count by ~7.

**Rollback:** `git revert` per-commit.

---

### Phase 4B — Low-risk single-domain groups (~2 days)

Move the next ring of leaves. Larger batches but still no new cross-domain edges.

**Moves:**
- `memory/` (3 main + 2 test) → `memory-domain`
- `knowledge/` (2 main + 4 test) → `knowledge-domain`
- `issues/control/IssueStateService.scala` only (leave 2,750-LOC `IssueController` for Phase 4F)
- `demo/` (3 main + 3 test) → `demo-domain`
- `board/` (6 main + 6 test) → `board-domain`
- `project/boundary/ProjectsController.scala` → `project-domain` (verify imports — uses `analysis`, `issues`, `orchestration`, all leaves here)
- `evolution/control/EvolutionTemplates.scala` → `evolution-domain`. Add `dependsOn(daemonDomain, governanceDomain)` to evolution module.
- Matching tests.

**Verification:** module-level `testOnly` for each; full `sbt test` at end.

**One commit per domain.** If any single move breaks compile, revert just that commit.

---

### Phase 4C — Config and taskrun cleanup (~1 day)

Boundary-heavy, low control-layer cross-talk. Also drops `config/entity/ConfigRepositoryES` dependency on legacy `db.ConfigRepository`.

**Moves:**
- `config/` remainder (10 main + 4 test) → `config-domain`. The `_root_.config` shadowing noted in CLAUDE.md still applies — keep imports explicit.
- `taskrun/` (5 main + 1 test) → `taskrun-domain`.

**Blocker:** `taskrun` controllers (Dashboard, Graph, Reports, Tasks) import `db.TaskRepository`. Either:
  - (a) do Phase 4D first and come back, or
  - (b) move the controllers now, keep the `db.TaskRepository` import, fix it in Phase 4D.
  Recommended: **(b)** — don't let the legacy blocker gate 4 other controllers.

**Verification:** `sbt configDomain/test taskrunDomain/test`.

---

### Phase 4D — Legacy `db/` migration (~3 days)

Delete `db/` by migrating its three trait+impl pairs to event-sourced equivalents in the owning domains. **This is the single biggest tech-debt reduction in the plan.**

**Steps (in order):**

1. **`db.ConfigRepository` → `config-domain`.** `config/entity/ConfigRepositoryES` already exists. Redirect the 1–2 remaining legacy importers to it, delete `db/ConfigRepository.scala`.
2. **`db.ChatRepository` → `conversation-domain`.** Introduce `ConversationEvent` sealed trait + `ConversationEventStore` + projection. Update the 5–6 importers (`ChatController`, `ChatSessionSupport`, `MessageRouter`, telegram channels, app bootstrap).
3. **`db.TaskRepository` → `taskrun-domain`.** Introduce `TaskRunEvent` sealed trait + event store + projection. Update the ~10 importers (taskrun controllers, workspace run services, orchestration dispatchers).
4. **Delete `db/` directory** and its test package once zero imports remain.

**Verification:** `sbt test` full run + `sbt it:test`. Data migration: if the app has live state in the old DB, a one-shot backfill script reads old rows and emits synthetic events into the new `EventStore`. This is scope-dependent — verify with the deployment surface before writing.

**Blocker-aware ordering:** do step 1 first (cheapest, unblocks config-domain moves), then 2 and 3 in parallel.

---

### Phase 4E — Break the orchestration ↔ gateway ↔ workspace cycle (~4 days)

The highest-value structural change. Uses the trait-extraction pattern already applied to `AgentPoolManager`, `TaskExecutor`, `WorkReportEventBus`.

**Traits to extract (new files in `orchestration-domain/src/main/scala/orchestration/entity/`):**

- `MessageRouter` — currently concrete in `gateway/control/MessageRouter.scala`. Extract the public trait and keep the impl as `MessageRouterLive`.
- `OrchestratorControlPlane` — currently both trait and impl in `orchestration/control/`. Keep the trait in `entity`, leave `OrchestratorControlPlaneLive` in `control`.
- Any additional interfaces discovered during the move (check each import at move time).

**Moves (after traits are extracted and compile):**

- `gateway/*` (16 files + 13 tests) → `gateway-domain`. Module gains `dependsOn(orchestrationDomain)` for the traits.
- `orchestration/*` (11 files + 19 tests) → `orchestration-domain`. Must not `dependsOn(gatewayDomain)` — if anything requires gateway types, it's either a trait that belongs in orchestration.entity or a sign the logic lives in the wrong place.
- `workspace/*` (10 files + 16 tests) → `workspace-domain`. `dependsOn(orchestrationDomain)`.

**Build-graph invariant to enforce:** `orchestrationDomain` has zero outbound `dependsOn` on `gatewayDomain` or `workspaceDomain`. Document this in `build.sbt` with a comment.

**Verification:**
- `sbt compile` from scratch (`sbt clean compile`) to catch stale `dependsOn` caching.
- `sbt orchestrationDomain/test gatewayDomain/test workspaceDomain/test` + `sbt it:test`.
- Manual smoke: start the gateway (`sbt run`) and exercise a workflow end-to-end — message routing, task dispatch, workspace run lifecycle.

**Rollback:** this phase lands as several commits (one trait extraction, one domain move each). Revert domain-by-domain.

---

### Phase 4F — Large controller splits (~3 days, optional, can defer)

After Phase 4E's move, `IssueController` (2,750 LOC), `ChatController` (1,427), `AgentsController` (1,263), `WorkspacesController` (1,166), `PlannerAgentService` (1,467), `TelegramChannel` (925), `WorkspaceRunService` (924), `SettingsController` (688) all live in their owning domain modules. Split them by sub-feature **within the module** — e.g., `IssueController` → `IssueCrudController`, `IssueDispatchController`, `IssueTimelineController`, `IssueReviewController`. This phase is bookkeeping, no architecture change.

---

### Phase 4G — Compile-time BCE enforcement (~2 days, optional)

Once every domain has its three BCE layers, split each module into `<domain>-entity`, `<domain>-control`, `<domain>-boundary` sbt submodules so the build refuses `control → boundary` imports. This is the P2 item from `docs/project-review-2026-04.md`.

Alternative: a single ArchUnit-equivalent test (Scala has `takka.dis-architecture-test` or custom compiler plugin options). Less invasive but weaker.

Decide at phase start based on how much 4A–4E revealed about layer discipline.

---

## Dependencies Between Phases

```
4A ──► 4B ──► 4C ─┬─► 4F
                  │
                  └─► 4D ─► 4E ─► 4G
```

- 4A/4B/4C can proceed in parallel with 4D step 1 (config).
- 4D steps 2 and 3 should complete before 4E because 4E moves files that currently import `db.*`.
- 4F is independent of 4G and either can be last.

---

## Success Metrics

Track after each phase in a comment on the tracking issue or in the PR description.

| Metric | Start | Phase 4B | Phase 4D | Phase 4E | Phase 4F |
|---|---:|---:|---:|---:|---:|
| `src/main/scala/` file count | 113 | ~90 | ~80 | ~25 | ~25 |
| `src/test/scala/` file count | 159 | ~120 | ~105 | ~45 | ~45 |
| Modules without `boundary/` layer | 9 | 6 | 5 | 2 | 1 |
| Modules without tests | 10 | 5 | 4 | 1 | 1 |
| Files importing `db.*` | 29 | 29 | 0 | 0 | 0 |
| Root fat files (>500 LOC) | 15 | 13 | 10 | 3 | 0 |

Numbers are approximate; recount at phase end.

---

## Per-Move Checklist

For every file moved, follow the CLAUDE.md "How to Move Code to a Module" steps. Quick version:

1. `grep -r "<old.package>.<symbol>"` — know every importer before touching.
2. Move the file, preserve the package declaration.
3. Update `build.sbt`: new module `dependsOn` edges and `libraryDependencies`.
4. `sbt clean compile` — `-Werror` catches unused imports and stale `dependsOn` edges.
5. Run the domain's `testOnly` and then full `sbt test`.
6. Commit with message `refactor(<domain>): move X to <module>-domain` — one logical move per commit.

---

## Out of Scope

- Adding new features during refactor. Behavior must not change.
- Introducing new patterns (new DI, new effect libraries, new persistence). Stay on ZIO 2 + EclipseStore + typed `PersistenceError`.
- Rewriting the frontend. Scalatags + Lit 3 + HTMX is a given.
- Git worktrees or branch strategy changes. One feature branch per phase, merged linearly.
