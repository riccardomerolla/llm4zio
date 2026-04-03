# Plan: Implement GitHub Issue #688 IssueTimelineService

## Task Group 1: Planning
- [x] Task 1.1: Replace the previous issue plan with an issue-specific implementation plan for #688

## Task Group 2: Timeline aggregation service
- [x] Task 2.1: Create `board/control/IssueTimelineService.scala` with the service trait, layer, and live implementation
- [x] Task 2.2: Map issue events, workspace runs, decisions, and chat messages into chronological `TimelineEntry` values
- [x] Task 2.3: Add focused tests covering aggregation and ordering behavior

## Task Group 3: Review and validation
- [x] Task 3.1: Review the completed service task group for API alignment and unnecessary scope
- [x] Task 3.2: Run formatting, compile, and tests

## Task Group 4: Finalize
- [x] Task 4.1: Mark all completed tasks in this plan
- [x] Task 4.2: Commit the implementation
