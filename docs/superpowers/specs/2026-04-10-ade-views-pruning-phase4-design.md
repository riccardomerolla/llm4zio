# ADE Views Pruning Phase 4 — Final Navigation Cleanup

## Goal

Fix all broken links and remove orphaned code left over from Phases 1-3. After this phase, every link in the UI resolves to a live page.

## Changes

### 1. CommandCenterView — Fix module grid

**File:** `modules/shared-web/src/main/scala/shared/web/CommandCenterView.scala`

The `adeModuleGrid()` contains cards pointing to deleted pages. Fix:

- Remove `/checkpoints` card (checkpoint domain deleted in Phase 1)
- Remove `/evolution` card (evolution UI deleted in Phase 1)
- Remove `/decisions` card (standalone decisions page deleted in Phase 1)
- Update `/governance` → `/settings/governance` (moved to Settings in Phase 2)
- Update `/daemons` → `/settings/daemons` (moved to Settings in Phase 2)

Remaining cards: SDLC Dashboard, Board, Governance (→Settings), Daemons (→Settings).

### 2. SdlcDashboardService — Remove broken lifecycle hrefs

**File:** `modules/sdlc-domain/src/main/scala/sdlc/control/SdlcDashboardService.scala`

Lifecycle stage cards link to deleted standalone pages. Make them non-clickable:

- `href = "/specifications"` → `href = ""` (Idea stage, line ~165)
- `href = "/specifications"` → `href = ""` (Spec stage, line ~172)
- `href = "/plans"` → `href = ""` (Plan stage, line ~179)

### 3. AgentMonitorView — Remove checkpoint link

**File:** `modules/shared-web/src/main/scala/shared/web/AgentMonitorView.scala`

Agent run rows navigate to `/checkpoints/$runId` which no longer exists. Make rows non-clickable:

- `reviewHref = info.runId.filter(_.trim.nonEmpty).map(runId => s"/checkpoints/$runId")` → `reviewHref = None`

### 4. NavBadgeController — Remove orphaned decisions badge route

**File:** `src/main/scala/app/boundary/NavBadgeController.scala`

The `GET /nav/badges/decisions` endpoint is no longer referenced by Layout (confirmed by LayoutSpec assertions). Remove:

- The `/nav/badges/decisions` route handler
- The `pendingDecisionCount` helper method
- The `DecisionInbox` parameter (no longer needed)
- Related imports (`decision.control.DecisionInbox`, `decision.entity.{DecisionFilter, DecisionStatus}`)

**Upstream:** Update `AdeRouteModule` to stop passing `DecisionInbox` to `NavBadgeController.routes`.

**Test:** Update `NavBadgeControllerSpec` to remove the decisions badge test case and stub.

## Verification

- `sbt compile && sbt test` pass
- Existing `LayoutSpec` already asserts `/nav/badges/decisions` and `/nav/badges/checkpoints` are absent from nav
- No remaining links to `/checkpoints`, `/evolution`, `/decisions`, `/plans`, `/specifications` in view code
