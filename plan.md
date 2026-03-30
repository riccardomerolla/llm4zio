## Workspace Default Branch

### Group 1: Domain and persistence
- [x] Add a workspace default branch field to the read model with a `"main"` fallback for existing event streams.
- [x] Introduce a dedicated workspace event for default-branch changes so persisted event schemas remain backward compatible.
- [x] Update repository and model tests to cover the new default-branch behaviour.

### Group 2: Workspace create/edit flow
- [x] Extend workspace create and update request parsing to accept a default branch, normalizing empty input to `"main"`.
- [x] Persist default-branch changes during workspace add/edit operations.
- [x] Update the workspace UI to expose the default branch on create and edit forms and show it in workspace details.
- [x] Add controller and view coverage for the new form field.

### Group 3: Board orchestration
- [x] Replace the hard-coded `ensureMainBranch` check with validation against the workspace-configured default branch.
- [x] Add board orchestrator coverage for non-`main` default branches and mismatch failures.
- [x] Run formatting, targeted tests, and review the completed changes before finishing.
