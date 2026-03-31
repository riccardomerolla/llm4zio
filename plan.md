## Third Modernization Iteration

### Group 1: Legacy and canonical status cleanup
- [x] Replace the previous plan with this iteration plan.
- [x] Remove the remaining board-status parsing path that still routes through legacy `IssueStateTag` aliases inside `IssueController`.
- [x] Move issue state-to-view mapping helpers out of `IssueController.scala` into support code so the controller uses a single canonical conversion path.
- [x] Run focused tests for the issue controller and board rendering surfaces.
- [x] Perform a review pass for Group 1, apply any remarks, then commit the group.

### Group 2: Effect-oriented runtime cleanup
- [x] Refactor `McpController` request-body handling to avoid `orDie` and use explicit request decoding failures.
- [x] Refactor `McpService` startup so MCP tool registration failure is modeled explicitly before the app boundary converts it to a defect.
- [x] Replace broad websocket fallback handling with explicit strict-or-default helpers.
- [x] Collapse the repeated `WorkspaceRunService` persistence wrapper tail onto the shared workspace error helpers.
- [x] Run focused tests for the touched runtime surfaces.
- [x] Perform a review pass for Group 2, apply any remarks, then commit the group.

### Group 3: Large-file decomposition
- [x] Keep `IssueController.scala` slimmer by moving canonical state-to-view and status parsing support into `IssueControllerSupport.scala`.
- [x] Extract the workspace run git and lifecycle helper block into an internal support module without changing service entrypoints.
- [x] Run focused tests for the decomposed controller and workspace surfaces.
- [x] Perform a review pass for Group 3, apply any remarks, then commit the group.

### Group 4: Final verification
- [ ] Run `sbt --client scalafmtAll`.
- [ ] Run targeted specs for the touched areas.
- [ ] Run `sbt --client compile`.
- [ ] Run `sbt --client test`.
- [ ] Perform a final review pass and apply any last remarks.
- [ ] Commit the final verification updates if needed.
