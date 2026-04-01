# Plan: Migrate all board operations from workspace.localPath to ProjectStorageService

## Problem
Board issues are created in `{workspace.localPath}/.board/` instead of the project storage directory `~/.llm4zio-gateway/projects/{projectId}/.board/`. Three files still use the legacy `workspace.localPath` for board operations.

## Task Group 1: Fix IssueRepositoryBoard
- [ ] Task 1.1: Add `ProjectStorageService` dependency and `resolveBoardPath` helper
- [ ] Task 1.2: Replace all `workspace.localPath` usages with `resolveBoardPath`

## Task Group 2: Fix WorkspaceAnalysisSchedulerLive
- [ ] Task 2.1: Add `ProjectStorageService` dependency and replace `workspace.localPath`

## Task Group 3: Fix PlannerAgentServiceLive
- [ ] Task 3.1: Add `ProjectStorageService` dependency and fix `resolveWorkspacePath`

## Task Group 4: Tests & Validation
- [ ] Task 4.1: Update all affected tests
- [ ] Task 4.2: Compile and run full test suite
- [ ] Task 4.3: Code review for remaining legacy usages
- [x] Delete any now-unused helper logic that existed only for the board proof filter.
- [x] Run focused board controller and view verification.
