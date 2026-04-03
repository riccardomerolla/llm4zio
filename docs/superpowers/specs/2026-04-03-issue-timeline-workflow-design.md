# Issue Timeline & Workflow Redesign

**Date:** 2026-04-03
**Status:** Approved

---

## Problem

The current issue approval workflow requires 6+ clicks across 4 different views:

1. Open issue detail view from board
2. Navigate to Decision Inbox, find related decision
3. Approve the decision
4. Navigate back to the chat view
5. Open Git Changes sidebar, click Apply to repo
6. Navigate back to the board, click Approve on the card

Additionally, the rework flow is broken: "Request rework" dumps the card to Backlog with the assigned agent wiped, no chat continuation, and the human's rework comment lost. The user must manually drag the card back to Todo for a fresh agent start with no memory of previous work.

The root cause is fragmentation — decision, chat, git changes, and issue status live in separate views with no unified interaction point.

## Solution

Replace the issue detail page with a **GitHub PR-style scrollable timeline** that becomes the single hub for an issue. All actions (review, approve, rework, merge) happen inline. The board card gets a **one-click Approve & Merge** shortcut. Rework becomes an **automated chat continuation**.

---

## Design

### 1. Issue Timeline View

**Route:** `GET /board/{workspaceId}/issues/{issueId}` (replaces current detail page)

**Layout:** Single scrollable page with a fixed header and a chronological timeline below.

**Header (sticky):**

- Issue title, ID, priority badge, tags
- Current status badge (Todo / In Progress / Review / Done)
- Branch name (if exists) with copy button
- Action bar: Approve & Merge button (only in Review state), Request Rework button (only in Review state)

**Timeline body:** Vertical timeline with a left gutter line. Each entry is a card with an icon, timestamp, and content. Entries are built by aggregating data from multiple event sources into a single chronological list.

**Data sources:**

- `IssueRepository` — issue events (Created, MovedToTodo, Assigned, MovedToInProgress, MovedToReview, Approved, MovedToRework, MovedToMerging, MarkedDone)
- `WorkspaceRepository` — runs filtered by `issueRef` (run started, run completed, branch/worktree info)
- `ChatRepository` — conversation messages loaded per run via `conversationId` (agent chat, collapsed by default)
- `DecisionInbox` — decision events filtered by issue (decision raised, decision resolved)
- Git diff stats fetched per run (lazy-loaded when user expands)

**Timeline entry types:**

| Entry | Icon | Content |
|-------|------|---------|
| Issue created | clipboard | Title, description, priority |
| Moved to Todo | arrow | "Moved to Todo" + dependency info |
| Agent assigned | robot | Agent name, dispatch details |
| Run started | play | Branch name, link to worktree |
| Chat messages | chat | Collapsible conversation (first/last message shown, expand all on click) |
| Run completed | check | Summary, duration |
| Git changes | files | Diff stats (files changed, +/−), expandable file list with inline diffs |
| Decision raised | eye | "Agent requests human review" |
| Review action | approve/rework | Approve or Rework with comment |
| Rework cycle | refresh | Rework comment, then new run entries repeat chronologically |
| Merged | merge | "Branch merged into main" |
| Done | flag | Final status, completion time |

**Review action form:** Rendered inline at the bottom of the timeline when the issue is in Review state. Textarea for reviewer notes, Approve & Merge button, Request Rework button. No need to visit the Decision Inbox separately.

### 2. One-Click Approve & Merge (quickApprove)

A single `IssueApprovalService.quickApprove()` method that performs the entire approval in one atomic operation:

```
quickApprove(workspacePath, issueId, reviewerNotes) →
  1. Validate issue is in Review column
  2. Validate governance allows Review → Done
  3. Find the pending decision for this issue → resolve as Approved
  4. Find the latest run for this issue
  5. Apply run worktree changes to workspace repo (reuse applyRunBranchToRepo logic)
  6. Set transient state to Merging
  7. Git merge --no-ff the run branch into main
  8. Move card to Done
  9. Clean up run/worktree
```

**Error handling:** If any step fails (merge conflict, missing branch, etc.), the transient state is reset, a failure reason is set on the issue, and the error surfaces to the UI. The timeline shows the failure as an entry.

**Called from three places:**

1. **Board card "Approve & Merge" button** — HTMX POST, returns updated board fragment. No navigation. Uses `ab-confirm-modal` for confirmation.
2. **Issue timeline Approve button** — HTMX POST, returns updated timeline. Stays on the page.
3. **Decision Inbox Approve** — existing flow, now calls `quickApprove()` internally instead of just appending events.

### 3. Automated Rework Flow

When a decision is resolved as `ReworkRequested`, the system automatically handles the full rework cycle:

```
reworkIssue(issueId, reworkComment, actor) →
  1. Resolve the decision as ReworkRequested
  2. Append IssueEvent.MovedToRework with the rework comment
  3. Move the board card to Todo (not Backlog) — keeping assigned agent
  4. Find the latest completed run for this issue
  5. Call WorkspaceRunService.continueRun() with:
     - parentRunId = the completed run's ID
     - prompt = the rework comment
     - same worktree/branch (agent continues from where it left off)
  6. The new run is created in Pending state
  7. On next dispatchCycle(), the orchestrator picks up the Todo card
     and starts the pending run (agent resumes with rework context)
```

**Chat continuation:** `continueRun()` creates a new conversation linked via `parentRunId`. The new conversation gets the rework comment as the initial prompt. The agent sees the previous chat history via the run chain. The previous branch/worktree is reused so the agent picks up where it left off.

**Timeline rendering:** Rework appears as a continuation — the rework comment shows as a "Human requested rework" entry, followed by the new run's chat and git changes. Multiple rework cycles stack chronologically, creating a clear revision history.

### 4. Board Card UX Changes

**Review column cards:**

- Branch name shown on the card
- "Approve & Merge" button calls `IssueApprovalService.quickApprove()` via HTMX POST with `ab-confirm-modal` confirmation. On success, board refreshes and card appears in Done. On failure, error toast appears.
- "Review" link opens the issue timeline page
- Cards that have been through rework show a "Rework #N" badge

**Other column cards:**

- In Progress cards show "Running" indicator when a run is active
- Todo cards with pending rework run show "Rework pending" badge

**Board-level changes:**

- `/board/{ws}/issues/{id}` serves the timeline view (replaces old detail page)
- Toast notifications after quickApprove succeeds from the board
- No Merging column needed — the Merging state is transient (seconds). Card goes directly from Review to Done from the user's perspective.

### 5. Implementation Architecture

**New files:**

| File | Layer | Purpose |
|------|-------|---------|
| `board/control/IssueApprovalService.scala` | Control | `quickApprove()` and `reworkIssue()` — orchestrates across BoardOrchestrator, DecisionInbox, WorkspaceRunService |
| `board/control/IssueTimelineService.scala` | Control | Aggregates events from multiple sources into `List[TimelineEntry]` |
| `board/entity/TimelineEntry.scala` | Entity | ADT for timeline entry types |
| `shared/web/IssueTimelineView.scala` | Boundary (view) | Scalatags SSR rendering of the timeline page |

**Modified files:**

| File | Change |
|------|--------|
| `board/boundary/BoardController.scala` | New route handler for timeline page; quickApprove endpoint; rework endpoint |
| `board/control/BoardOrchestrator.scala` | `dispatchCycle()` handles cards with existing pending runs (rework continuation) |
| `decision/boundary/DecisionsController.scala` | Resolve from Decision Inbox calls `quickApprove()`/`reworkIssue()` via `IssueApprovalService` |
| `shared/web/BoardView.scala` | Card links point to timeline; Approve button calls quickApprove; rework badges; remove old detail page |
| `workspace/control/WorkspaceRunService.scala` | `continueRun()` adjustment to reuse existing worktree/branch |
| `app/` DI wiring | Wire `IssueApprovalService` and `IssueTimelineService` into dependency graph |

**Dependency graph:**

```
IssueApprovalService
  ├── BoardOrchestrator (card moves, git merge)
  ├── DecisionInbox (resolve decisions)
  ├── WorkspaceRunService (apply changes, continue runs)
  └── WorkspaceRepository (find runs by issue)

IssueTimelineService
  ├── IssueRepository (issue events)
  ├── WorkspaceRepository (runs by issue)
  ├── DecisionInbox (decisions by issue)
  └── ChatRepository (conversations by run)
```

**Avoiding circular dependencies:** `IssueApprovalService` calls into existing services but none call back. For `DecisionInbox.resolve()` triggering rework automation: the controller layer handles the dispatch — `DecisionInbox.resolve()` returns the resolution result, and the controller invokes `IssueApprovalService.reworkIssue()` based on the result. This keeps `DecisionInbox` unaware of `IssueApprovalService`.
