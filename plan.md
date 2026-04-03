# Plan: Implement GitHub Issue #690 Rework Flow

## Task Group 1: Planning
- [x] Task 1.1: Replace the previous plan with an issue-specific implementation plan for #690

## Task Group 2: Issue approval service rework flow
- [x] Task 2.1: Extend `IssueApprovalService` with `reworkIssue`
- [x] Task 2.2: Wire the service to resolve the review decision, move the board card back to Todo, and continue the latest eligible run
- [x] Task 2.3: Keep the implementation scoped to the automated rework path only

## Task Group 3: Test coverage and review
- [x] Task 3.1: Extend `IssueApprovalServiceSpec` with rework-path coverage
- [x] Task 3.2: Review the implementation and assertions for unnecessary scope and weak behavior checks

## Task Group 4: Validation and handoff
- [x] Task 4.1: Run formatting, targeted tests, compile, and the full test suite
- [x] Task 4.2: Mark the plan complete and leave changes uncommitted for user-managed commit flow
