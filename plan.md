## ADE Sidebar Navigation Improvements Plan

### Group 1: Sidebar status data and routing
- [x] Add a sidebar status aggregate for pending decisions, pending checkpoints, and in-progress board items.
- [x] Expose a sidebar status fragment route that can refresh badge counts without changing every page controller.
- [x] Add a minimal evolution web page and `/evolution` route backed by the existing evolution proposal repository.
- [x] Expand controller and view tests for the new sidebar fragment and evolution route.

### Group 2: Sidebar layout updates
- [x] Rework the sidebar navigation into clear core gateway and ADE groups.
- [x] Add badge counts for decisions, checkpoints, and board in-progress items.
- [x] Add the evolution link and ensure governance stays visible in the ADE group.
- [x] Strengthen active state highlighting for the current page.
- [x] Expand layout tests to verify grouping, badges, and active-state behavior.

### Group 3: Verify and finalize
- [x] Run formatting for updated Scala sources.
- [x] Run focused sidebar and evolution test suites and fix regressions.
- [x] Review the implementation against the Scala 3 + ZIO guidance and mark all tasks complete in this plan.
