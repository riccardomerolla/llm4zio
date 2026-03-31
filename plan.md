## Legacy Removal, ZIO Cleanup, and File Splitting

### Group 1: Legacy and compatibility purge
- [x] Replace the previous plan contents with this implementation plan.
- [x] Audit the legacy and compatibility row/model surfaces under `config/entity`, `conversation/entity`, `issues/entity`, `taskrun/entity`, `shared/store`, and `db`.
- [x] Remove dead legacy files and compatibility exports that are no longer required by runtime code.
- [ ] Migrate remaining internal call sites and tests to canonical types where compatibility aliases are only retained for historical reasons.
- [ ] Remove internal legacy issue-status aliases where current code can use canonical statuses directly, while keeping boundary parsing for persisted or inbound values that still need translation.
- [x] Run focused tests for the touched legacy and compatibility surfaces.
- [ ] Perform a review pass for Group 1, apply any remarks, then commit the group.

### Group 2: Effect-oriented cleanup
- [x] Refactor broad `catchAll(_ => ...)`, `orDie`, and ad hoc `RuntimeException(e.toString)` mappings in workspace, issues, conversation, and config runtime code into explicit conversions.
- [x] Replace parsing and fallback helpers built around `Try(...)` and silent swallowing with explicit helper functions and deliberate defaults.
- [ ] Refactor unsafe runtime initialization, especially the Docker availability cache, into effect-safe construction while preserving behavior.
- [x] Extract small pure helpers for normalization, parsing, and event construction before touching larger control flow.
- [x] Run focused tests for effect and error-handling behavior.
- [ ] Perform a review pass for Group 2, apply any remarks, then commit the group.

### Group 3: Oversized Scala file decomposition
- [x] Split `IssueController.scala` into internal modules for board loading and filtering, templates and pipelines, imports, status transitions, workspace run triggering, and form or parsing helpers.
- [ ] Split `WorkspaceRunService.scala` into setup and preflight, prompt and context building, execution lifecycle, and cleanup or audit helpers.
- [ ] Split `PlannerAgentService.scala` into preview and state persistence, LLM execution and parsing, and plan or specification creation helpers.
- [ ] Split `ChatController.scala` into conversation startup and streaming, planner mode helpers, workspace context resolution, and form or session parsing helpers.
- [x] Keep the top-level traits and live wiring stable while moving implementation detail into smaller internal modules.
- [x] Run focused tests for the decomposed Scala surfaces.
- [ ] Perform a review pass for Group 3, apply any remarks, then commit the group.

### Group 4: Oversized view and client decomposition
- [x] Split `IssuesView.scala` into board or list rendering, detail rendering, form rendering, filter or toolbar rendering, and markdown rendering helpers.
- [ ] Review `GatewayMcpTools.scala` and extract tool groups only if the split can stay behavior-neutral in this pass.
- [x] Split `issues-board.js` and `ab-issues-board.js` into coordinator, drag and drop, data synchronization, and render state helpers.
- [ ] Leave `board-fs.js`, `ab-board-layout.js`, and `ab-board-column.js` unchanged unless the larger board split exposes obvious duplication worth extracting.
- [x] Run focused tests for the decomposed view and client surfaces.
- [ ] Perform a review pass for Group 4, apply any remarks, then commit the group.

### Group 5: Final verification
- [x] Run `sbt --client scalafmtAll`.
- [x] Run targeted specs for all touched areas.
- [x] Run `sbt --client compile`.
- [x] Run `sbt --client test`.
- [ ] Perform a final review pass and apply any last remarks.
- [ ] Commit the final verification updates if needed.
