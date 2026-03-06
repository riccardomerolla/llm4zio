# Proof-of-Work Board & Auto-Dispatch Design

**Date**: 2026-03-05
**Inspired by**: [OpenAI Symphony](https://github.com/openai/symphony) — board-driven agent supervision with proof-of-work ergonomics

## Problem

The gateway has issues, task runs, workflows, and parallel sessions — but these concepts feel disconnected in the UI. When an agent works on an issue, there's no unified view of *what it did* and *whether the work is good*. Engineers must navigate between multiple views to piece together agent output.

Symphony solves this by making the board the control plane: agents provide proof of work directly on issues, and engineers review evidence — not agent behavior.

## Approach

**Read-side projection** (`IssueWorkReport`) that aggregates data from multiple event streams into a single view model per issue. Write-side models stay clean; the projection evolves independently.

## Two Milestones

### Milestone 1: "Proof-of-Work Board"

Unify UX so issue board cards show rich agent work evidence.

### Milestone 2: "Auto-Dispatch & External Sync"

Board-driven autonomous agent dispatch + external tracker integration.

---

## Data Model

### IssueWorkReport (read-side projection)

```
IssueWorkReport
  issueId: IssueId
  walkthrough: Option[String]          // agent-generated change summary
  agentSummary: Option[String]         // what the agent did, key decisions
  diffStats: Option[DiffStats]         // files changed, +/- lines
  prLink: Option[String]              // PR URL
  prStatus: Option[PrStatus]          // Open, Merged, Closed, Draft
  ciStatus: Option[CiStatus]          // Pending, Running, Passed, Failed
  tokenUsage: Option[TokenUsage]      // input, output, total tokens
  runtimeSeconds: Option[Long]        // wall-clock agent runtime
  reports: List[TaskReport]           // from TaskRun events
  artifacts: List[TaskArtifact]       // from TaskRun events
  lastUpdated: Instant
```

Folds events from: `IssueEvent`, `TaskRunEvent`, `ParallelSessionEvent`.

### New TaskRunEvent variants

- `WalkthroughGenerated(runId, summary)`
- `PrLinked(runId, prUrl, prStatus)`
- `CiStatusUpdated(runId, ciStatus)`
- `TokenUsageRecorded(runId, inputTokens, outputTokens, runtimeSeconds)`

### Milestone 2 additions

**AgentIssue new fields**:
- `externalRef: Option[String]` — e.g., `"LINEAR:ABC-123"` or `"GH:owner/repo#42"`
- `externalUrl: Option[String]`

**New IssueEvent variants**:
- `ExternalRefLinked(issueId, externalRef, externalUrl)`
- `ExternalRefSynced(issueId, updatedFields)`

**TrackerConfig entity**:
- `kind: TrackerKind` (Linear, GitHubIssues, Jira)
- `endpoint, apiKey, projectSlug`
- `activeStates, terminalStates`
- `pollingIntervalMs`
- `syncDirection: SyncDirection` (Inbound, Outbound, Bidirectional)

---

## UI/UX

### Issue Card — Proof of Work Panel

```
+-------------------------------------+
| High  | ABC-123: Fix auth bug        |
| Agent: claude-coder | In Progress    |
+-------------------------------------+
| v Proof of Work                      |
|                                      |
| Walkthrough                          |
|   "Refactored auth middleware to     |
|    validate JWT expiry before..."    |
|                                      |
| Agent Summary                        |
|   3 turns - 2 file edits - 1 test   |
|                                      |
| Diff: 4 files - +87 / -23           |
| PR #42 (Open) - CI Passed           |
| 45s - 12.4k tokens                  |
|                                      |
| > Reports (2) - Artifacts (1)       |
+-------------------------------------+
```

- Collapsed by default on board, expanded on issue detail
- Signals appear progressively via HTMX poll / WebSocket push
- Empty signals hidden
- Walkthrough and Agent Summary are most prominent

### Board-level additions

- Stats bar: `Running: 3 | Completed: 12 | Tokens today: 245k`
- Filter: "Has proof of work" for review-ready issues

### Milestone 2 UI

- Auto-dispatch toggle per board/workflow
- External ref badge on cards
- Sync status indicator
- Tracker Integrations settings section under `/config`

---

## Architecture

### Milestone 1 Components

1. **IssueWorkReportProjection** — ZIO service, subscribes to ActivityHub, maintains `Ref[Map[IssueId, IssueWorkReport]]`. Rebuilds from historical events on startup.

2. **ProofOfWorkView** — Scalatags component for the collapsible panel.

3. **Agent runner enrichment** — Emit new TaskRunEvents after agent work (PR link, diff stats, tokens, walkthrough).

Data flow:
```
Agent completes work
  -> TaskRunEvent(PrLinked, CiStatusUpdated, TokenUsageRecorded, WalkthroughGenerated)
  -> ActivityHub broadcasts
  -> IssueWorkReportProjection updates in-memory state
  -> Board HTMX poll fetches updated card fragment
  -> ProofOfWorkView renders enriched card
```

### Milestone 2 Components

4. **AutoDispatcher** — Polling ZIO service. Queries "Todo" issues, matches agent capabilities, emits Assigned events, triggers workspace runs. Concurrency limits + exponential backoff.

5. **TrackerSyncService** — Polls external tracker, creates/updates internal issues with externalRef. Optional outbound sync.

6. **TrackerConfig management** — CRUD under ConfigRepository, exposed via ConfigController.

Concurrency model:
- Max concurrent agents (global)
- Per-state concurrency slots
- Exponential backoff retries (capped)

---

## Issue Breakdown

### Milestone 1: Proof-of-Work Board (8 issues)

1. Add new TaskRunEvent variants (WalkthroughGenerated, PrLinked, CiStatusUpdated, TokenUsageRecorded)
2. Create IssueWorkReport projection model and service
3. Wire projection to ActivityHub event streams
4. Rebuild projection on startup from historical events
5. Emit proof-of-work events from agent runner
6. Build ProofOfWorkView Scalatags component
7. Enrich issue board cards with proof-of-work panel
8. Add board-level stats bar and "has proof" filter

### Milestone 2: Auto-Dispatch & External Sync (6 issues)

9. Add external ref fields to AgentIssue model
10. Create TrackerConfig entity and CRUD
11. Implement TrackerSyncService for inbound sync
12. Implement AutoDispatcher with concurrency limits
13. Add auto-dispatch toggle and sync UI
14. Outbound sync support for bidirectional tracking
