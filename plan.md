## Fourth Modernization Iteration

### Group 1: Internal legacy compatibility cleanup
- [x] Replace scattered issue-state compatibility sets with shared canonical helpers so legacy state aliases stay centralized instead of leaking through controllers.
- [x] Remove dead issue controller compatibility helpers that are no longer referenced.
- [x] Run focused issue and workspace controller specs for the compatibility cleanup.
- [x] Perform a review pass for Group 1, apply any remarks, then commit the group.

### Group 2: Effect-oriented runtime cleanup
- [x] Replace the broad issue lookup fallback in `WorkspaceRunService` with explicit not-found handling and typed persistence mapping.
- [x] Replace the broad channel-close fallback in `ChatController` with explicit channel error handling.
- [x] Run focused specs for the touched workspace and chat runtime surfaces.
- [x] Perform a review pass for Group 2, apply any remarks, then commit the group.

### Group 3: Large-file decomposition
- [ ] Extract chat session and session-id helper logic from `ChatController.scala` into an internal support module without changing routes or service wiring.
- [ ] Update the controller to delegate to the extracted support module and keep behavior unchanged.
- [ ] Run focused specs for the chat controller surface after the split.
- [ ] Perform a review pass for Group 3, apply any remarks, then commit the group.

### Group 4: Final verification
- [ ] Run `sbt --client scalafmtAll`.
- [ ] Run targeted specs for the touched areas.
- [ ] Run `sbt --client compile`.
- [ ] Run `sbt --client test`.
- [ ] Perform a final review pass and apply any last remarks.
- [ ] Commit the final verification updates if needed.
