# ADE Views Pruning — Phase 1: Remove Dead Code & Standalone Pages

**Date:** 2026-04-10  
**Status:** Draft  
**Part of:** ADE UX Revamp (4 phases)

## Problem

The ADE features added 8 standalone pages (Plans, Specifications, Knowledge, Checkpoints, Decisions, Governance, Evolution, Daemons) that feel disconnected from the Board issue workflow. They are information-overload admin panels that have never been used in practice. The navigation is overwhelming (~13 items).

## Phase Overview

| Phase | Scope | Status |
|-------|-------|--------|
| **1. Prune dead code (this spec)** | Delete checkpoint domain, delete Evolution/Plans/Specs/Decisions standalone pages | Current |
| 2. Settings consolidation | Move Governance + Daemons to Settings sub-sections | Future |
| 3. Issue detail enrichment | Add Plan/Spec/Decision contextual panels to Issue timeline | Future |
| 4. Navigation cleanup | Final nav reduction to 6 items | Future |

## Phase 1 Goals

1. **Delete the checkpoint domain entirely** — dead code, zero external references
2. **Delete Evolution UI** — remove view + controller + routes (keep domain module — used by daemon/sdlc)
3. **Delete Plans standalone page** — remove view + controller + routes (keep domain module)
4. **Delete Specifications standalone page** — remove view + controller + routes (keep domain module)
5. **Delete Decisions standalone page** — remove view + controller + routes, but **preserve the side-panel fragment** in DecisionsView (used by run panels)
6. **Update navigation** — remove links to all deleted pages
7. **Update route modules and DI** — remove controller wiring for deleted pages

## Non-Goals

- NOT touching Governance or Daemons pages (Phase 2)
- NOT adding new UI panels to Issue detail (Phase 3)
- NOT changing the Knowledge page (stays as-is)
- NOT removing domain modules for plan, specification, decision (they're actively used by sdlc/orchestration/evolution)
- NOT removing repository/service wiring in ApplicationDI (only controller layers)

---

## Files to Delete

### Checkpoint Domain (entire module)

| File | Type |
|------|------|
| `modules/checkpoint-domain/` | Entire directory |
| `src/main/scala/checkpoint/boundary/CheckpointsController.scala` | Controller |
| `src/main/scala/checkpoint/control/CheckpointReviewService.scala` | Service |
| `src/main/scala/shared/web/CheckpointsView.scala` | View |
| `src/test/scala/checkpoint/boundary/CheckpointsControllerSpec.scala` | Test |
| `src/test/scala/checkpoint/control/CheckpointReviewServiceSpec.scala` | Test |
| `src/test/scala/shared/web/CheckpointsViewSpec.scala` | Test |

### Evolution UI

| File | Type |
|------|------|
| `src/main/scala/evolution/boundary/EvolutionController.scala` | Controller |
| `modules/shared-web/src/main/scala/shared/web/EvolutionView.scala` | View |
| `src/test/scala/evolution/boundary/EvolutionControllerSpec.scala` | Test |
| `src/test/scala/shared/web/EvolutionViewSpec.scala` | Test |

### Plans UI

| File | Type |
|------|------|
| `src/main/scala/plan/boundary/PlansController.scala` | Controller |
| `modules/shared-web/src/main/scala/shared/web/PlansView.scala` | View |
| `modules/shared-web/src/main/scala/shared/web/PlanPreviewComponents.scala` | View helper |
| `src/test/scala/plan/boundary/PlansControllerSpec.scala` | Test |
| `src/test/scala/shared/web/PlansViewSpec.scala` | Test |
| `src/test/scala/shared/web/PlanPreviewComponentsSpec.scala` | Test |

### Specifications UI

| File | Type |
|------|------|
| `modules/specification-domain/src/main/scala/specification/boundary/SpecificationsController.scala` | Controller |
| `modules/specification-domain/src/main/scala/specification/boundary/SpecificationsView.scala` | View |
| `src/test/scala/specification/boundary/SpecificationsControllerSpec.scala` | Test |
| `src/test/scala/shared/web/SpecificationsViewSpec.scala` | Test |

### Decisions UI (partial — keep side panel)

| File | Action |
|------|--------|
| `src/main/scala/decision/boundary/DecisionsController.scala` | Delete (full standalone controller) |
| `modules/decision-domain/src/main/scala/decision/boundary/DecisionsView.scala` | **Modify** — remove standalone page methods, keep `sidePanelFragment` |
| `src/test/scala/decision/boundary/DecisionsControllerSpec.scala` | Delete |

---

## Files to Modify

### `build.sbt`

- Remove `lazy val checkpointDomain` definition
- Remove `checkpointDomain` from `allModules` list
- Remove `checkpointDomain` from `sharedWeb.dependsOn(...)` if present

### `modules/shared-web-core/src/main/scala/shared/web/Layout.scala`

Remove nav items for:
- `/specifications` (Specifications)
- `/plans` (Plans)
- Checkpoints
- Decisions
- Evolution

Keep nav items for (untouched in Phase 1):
- Dashboard, Board, Issues, Projects, Agents, Knowledge, Governance, Daemons, Settings

### `src/main/scala/app/boundary/AdeRouteModule.scala`

- Remove imports for: `CheckpointsController`, `CheckpointReviewService`, `DecisionsController`, `EvolutionController`, `PlansController`, `SpecificationsController`
- Remove their service injections from the ZLayer
- Remove their route aggregations from the yield block
- Keep: Any remaining routes (Governance, Daemons, NavBadge if still needed)
- If AdeRouteModule becomes empty or nearly empty after removals, consider whether to keep it or fold remaining routes into another module

### `src/main/scala/app/ApplicationDI.scala`

Remove ONLY controller layers:
- `CheckpointsController.live` / `CheckpointReviewService.live`
- `PlansController.live`
- `SpecificationsController.live`
- `DecisionsController.live`

**Keep** domain repository/service layers (used by other modules):
- `PlanEventStoreES.live`, `PlanRepositoryES.live` — used by SdlcDashboardService
- `SpecificationEventStoreES.live`, `SpecificationRepositoryES.live` — used by SdlcDashboardService
- `DecisionInbox.live`, `DecisionLogEventStoreES.live`, `DecisionLogRepositoryES.live` — used by EvolutionEngine
- `GovernancePolicyEventStoreES.live`, `GovernancePolicyRepositoryES.live` — used everywhere
- `EvolutionEngine.live`, `EvolutionProposalEventStoreES.live`, `EvolutionProposalRepositoryES.live` — used by DaemonAgentScheduler

### NavBadgeController

If `NavBadgeController` provides badge counts for removed pages (Decisions, Checkpoints), either:
- Remove the badge routes for deleted pages
- Or remove NavBadgeController entirely if all its badges are for deleted pages

---

## Key Constraint: Decision Side Panel

`DecisionsView.sidePanelFragment` renders a compact decision list for agent run views. This is referenced from other views (IssuesView, run detail). When modifying `DecisionsView.scala`:

1. Keep the `sidePanelFragment` method and its supporting helpers
2. Remove: `listPage`, `detailPage`, and any other standalone page methods
3. The file becomes a fragment-only utility, not a full page view

---

## Verification

1. `sbt compile` — no compilation errors after all deletions
2. `sbt test` — all remaining tests pass (deleted test files don't count)
3. `sbt run` — gateway starts, all remaining pages work:
   - Dashboard: http://localhost:8080/
   - Board: http://localhost:8080/board
   - Issues: http://localhost:8080/issues
   - Projects: http://localhost:8080/projects
   - Agents: http://localhost:8080/agents
   - Knowledge: http://localhost:8080/knowledge
   - Governance: http://localhost:8080/governance (still exists in Phase 1)
   - Daemons: http://localhost:8080/daemons (still exists in Phase 1)
   - Settings: http://localhost:8080/settings
4. Removed URLs return 404:
   - http://localhost:8080/plans → 404
   - http://localhost:8080/specifications → 404
   - http://localhost:8080/checkpoints → 404
   - http://localhost:8080/decisions → 404
   - http://localhost:8080/evolution → 404
5. Navigation sidebar no longer shows links to removed pages
6. Decision side panel still works in agent run views
