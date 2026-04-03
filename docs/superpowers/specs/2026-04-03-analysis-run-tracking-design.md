# Analysis Run Tracking Design

## Problem

When the `WorkspaceAnalysisScheduler` runs analysis jobs (CodeReview, Architecture, Security), it does not create `WorkspaceRun` records. This causes two gaps:

1. **No Active Runs visibility** — the Command Center's Active Runs widget queries `WorkspaceRun` records with `Pending`/`Running` status, so analysis runs are invisible.
2. **Empty issue timeline** — `IssueTimelineService.buildTimeline()` finds runs via `workspaceRepository.listRunsByIssueRef(issueId)`, but no `WorkspaceRun` exists with the board issue's ID as `issueRef`, so the analysis review issue shows "No timeline activity yet."

## Solution

Create `WorkspaceRun` records for each analysis job directly in the scheduler. Each analysis type (CodeReview, Architecture, Security) appears as a separate run with its own status lifecycle. Both Active Runs and Timeline work automatically with zero changes to those systems.

## Design

### Data Flow

Current:
```
runJob → updateStatus(Running) → publishActivity → runAnalysis → ensureHumanReviewIssue → updateStatus(Completed) → publishActivity
```

New:
```
runJob → ensureHumanReviewIssue (returns BoardIssueId) → createWorkspaceRun(issueRef=boardIssueId) → updateStatus(Running) → updateRunStatus(Running) → publishActivity → runAnalysis → updateRunStatus(Completed) → updateStatus(Completed) → publishActivity
```

Key change: `ensureHumanReviewIssueWithAnalysis` moves to the top of `runJob()` so the board issue ID is available before creating the run. The method's idempotency (skips if issue exists) ensures the first analysis type creates the issue and subsequent ones reuse it.

### Return Type Change

The `ensureHumanReviewIssue` chain currently returns `Unit`. All four private methods in the chain change to return `BoardIssueId`:

- `ensureHumanReviewIssueWithAnalysis` — returns `BoardIssueId` from `runtimeState.modifyZIO`
- `upsertHumanReviewIssue` — passes through
- `ensureWorkspaceBoardReviewIssue` — returns found or created ID
- `createWorkspaceBoardReviewIssue` — returns the generated ID

### WorkspaceRun Field Mapping

Each analysis job creates a `WorkspaceRunEvent.Assigned` with:

| Field | Value |
|---|---|
| `runId` | Fresh UUID |
| `workspaceId` | From the job |
| `parentRunId` | `None` |
| `issueRef` | `boardIssueId.value` (String) from `ensureHumanReviewIssue` |
| `agentName` | `"analysis-code-review"`, `"analysis-architecture"`, or `"analysis-security"` |
| `prompt` | `"Code review analysis for workspace <id>"` (or Architecture/Security) |
| `conversationId` | `""` — analysis runs have no chat conversations |
| `worktreePath` | Workspace `localPath` — no separate worktree |
| `branchName` | `""` — analysis doesn't create branches |

### Status Transitions

Via `WorkspaceRunEvent.StatusChanged`:

1. Created as `Pending` (implicit from `Assigned` event)
2. Updated to `Running(Autonomous)` when analysis execution starts
3. Updated to `Completed` on success, `Failed` on error

### Helper

New private method `analysisAgentName(AnalysisType): String`:
- `CodeReview` → `"analysis-code-review"`
- `Architecture` → `"analysis-architecture"`
- `Security` → `"analysis-security"`

## Files Changed

Only `src/main/scala/analysis/control/WorkspaceAnalysisScheduler.scala`:

1. Add imports for `WorkspaceRunEvent`, `RunStatus`, `RunSessionMode`
2. `runJob()` — reorder to get issueRef first, create run, update run status at each stage
3. `ensureHumanReviewIssueWithAnalysis()` — return `BoardIssueId`
4. `upsertHumanReviewIssue()` — return `BoardIssueId`
5. `ensureWorkspaceBoardReviewIssue()` — return found or created `BoardIssueId`
6. `createWorkspaceBoardReviewIssue()` — return generated `BoardIssueId`
7. Add `analysisAgentName` helper

No changes to `IssueTimelineService`, `CommandCenterView`, `WorkspaceRun` entity, or `WorkspaceRepository`.

## Testing

Add tests to `WorkspaceAnalysisSchedulerSpec` verifying:
- Analysis jobs create `WorkspaceRun` records
- Run `issueRef` matches the board issue ID
- Run `agentName` follows the `"analysis-<type>"` convention
- Run status transitions through `Pending → Running → Completed`
- Failed analysis sets run status to `Failed`
