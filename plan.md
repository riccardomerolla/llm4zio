## Issue 635 Plan

### Group 1: Standardize ADE controller construction
- [x] Inspect the existing ADE controllers and identify the exact dependency set each one needs for a `trait/live` ZLayer shape.
- [x] Convert `ProjectsController`, `SpecificationsController`, and `PlansController` from static `routes(...)` helpers to `trait` controllers with `routes` methods and `live` layers.
- [x] Convert `DecisionsController`, `CheckpointsController`, `KnowledgeController`, and `WorkspacesController` to the same `trait/live` pattern without changing route behavior.
- [x] Review the controller refactors for consistent accessor naming, dependency capture, and error-handling parity before wiring changes.

### Group 2: Simplify WebServer wiring and align tests
- [x] Update `WebServer` to depend on controller services instead of manually threading repositories and services into ADE controller route builders.
- [x] Update controller-focused specs to instantiate the new controller services cleanly and keep their scenario coverage unchanged.
- [x] Review the server/controller wiring for any remaining direct `routes(deps...)` usage or inconsistent controller patterns.

### Group 3: Verify and finalize
- [x] Run `sbt --client scalafmtAll`.
- [x] Run `sbt --client compile` and `sbt --client Test/compile`, fixing any regressions from the controller refactor.
- [x] Run `sbt --client test`.
- [x] Mark the completed tasks in this plan.
