## Second Modernization Iteration

### Group 1: Legacy and dead-code removal
- [x] Replace the previous plan with this narrower implementation plan.
- [x] Remove dead legacy client assets that are no longer referenced by any rendered page or test.
- [x] Run focused checks for the legacy removal surface.
- [x] Perform a review pass for Group 1, apply any remarks, then commit the group.

### Group 2: Effect-oriented cleanup
- [x] Replace remaining request parsing built around `Try(...)` in activity endpoints with explicit helper functions.
- [x] Refactor config-controller initialization to use explicit startup error modeling instead of inner `orDie` calls.
- [x] Collapse repeated application startup defect conversion into a single explicit helper at the wiring boundary.
- [x] Run focused tests for the touched runtime and controller surfaces.
- [x] Perform a review pass for Group 2, apply any remarks, then commit the group.

### Group 3: Gateway MCP helper extraction
- [x] Extract the pure JSON parsing, enum parsing, rendering, and markdown summarization helpers from `GatewayMcpTools.scala` into an internal support module.
- [x] Keep the public `GatewayMcpTools` construction and tool list unchanged while switching call sites to the support module.
- [x] Run focused tests for the MCP tool surface.
- [x] Perform a review pass for Group 3, apply any remarks, then commit the group.

### Group 4: Final verification
- [x] Run `sbt --client scalafmtAll`.
- [x] Run targeted specs for the touched areas.
- [x] Run `sbt --client compile`.
- [x] Run `sbt --client test`.
- [x] Perform a final review pass and apply any last remarks.
- [x] Commit the final verification updates if needed.
