## SDLC Dashboard ADE Metrics Plan

### Group 1: Extend dashboard snapshot data
- [x] Add ADE-oriented snapshot models for governance, daemon health, evolution, and trend indicators.
- [x] Wire the SDLC dashboard service to load governance policies, daemon statuses, and evolution proposals.
- [x] Derive dashboard aggregates for policy activity, evaluation pass/fail counts, daemon lifecycle/health counts, pending proposals, recently applied evolutions, and stat-card trends.
- [x] Expand service tests to cover the new aggregates and trend calculations.

### Group 2: Render ADE metrics in the dashboard
- [x] Update the SDLC dashboard view to render governance, daemon health, and evolution panels.
- [x] Add trend indicators to the headline stat cards with clear visual direction markers.
- [x] Expand view tests to verify the new ADE panels and trend indicators are rendered.

### Group 3: Verify and finalize
- [x] Run formatting for the updated Scala sources.
- [x] Run the focused SDLC dashboard test suites and fix any regressions.
- [x] Review the implementation against the Scala 3 + ZIO guidance and mark all completed tasks in this plan.
