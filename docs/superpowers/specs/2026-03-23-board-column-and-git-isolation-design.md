# Board Column Fix and `.board/` Git Isolation

**Date:** 2026-03-23
**Status:** Approved

---

## Problem

Two related issues in the board-run workflow:

1. **Wrong destination column**: When a run completes successfully, `BoardOrchestrator.completeSuccess` moves the issue to `BoardColumn.Done`. The correct destination is `BoardColumn.Review` — a human must verify the output before the issue is closed.

2. **Apply blocked by board changes**: The `.board/` directory lives inside the workspace git repository. Board operations (`moveIssue`, `createIssue`) either commit to the workspace git log or leave files untracked. Both cases pollute the workspace `git status` and block the "Apply to repo" action with "workspace has uncommitted changes".

---

## Design

### Bug 1 — Column fix

Single-line change in `BoardOrchestrator.completeSuccess`:

```scala
// before
boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Done)

// after
boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Review)
```

**Workflow after fix:**

| Event | From | To |
|---|---|---|
| Run assigned | Todo | In Progress |
| Run completes successfully | In Progress | Review |
| Human approves | Review | Done |

---

### Bug 2 — `.board/` as standalone git repository

`.board/` becomes a self-contained git repository, completely invisible to the workspace git. The workspace `.gitignore` gets a `/.board/` entry so `git status` never sees board state. The "Apply to repo" uncommitted-changes guard is unblocked for free — no changes to the guard logic are needed.

`BoardRepositoryFS` already routes all git calls through `GitService` with a configurable `repoPath`; the only structural change is passing `boardPath` (`workspacePath + "/.board"`) instead of `workspacePath`.

#### Changes

**`BoardRepositoryFS.initBoard(workspacePath)`**

After creating the `.board/` directory structure:

1. Run `git init` inside `workspacePath/.board` (the board git root).
2. Make an initial commit: `init: board repository`.
3. Append `/.board/` to `workspacePath/.gitignore`, creating the file if absent and skipping if the entry already exists.
4. If `.board/.git` already exists (existing workspace), skip `git init` but still ensure `/.board/` is in `.gitignore`.

**`BoardRepositoryFS` — all git call sites**

Change `repoPath` from `workspacePath` to `boardPath = workspacePath + "/.board"`. File paths passed to `git mv`, `git add`, `git rm` become relative to the board root:

```scala
// before
gitService.mv(workspacePath, ".board/todo/ISSUE-1.md", ".board/in-progress/ISSUE-1.md")
gitService.commit(workspacePath, "move: ISSUE-1 to in-progress")

// after
val boardPath = workspacePath + "/.board"
gitService.mv(boardPath, "todo/ISSUE-1.md", "in-progress/ISSUE-1.md")
gitService.commit(boardPath, "move: ISSUE-1 to in-progress")
```

No changes to the `GitService` interface — `repoPath` already accepts any path.

**`BoardRepositoryFS.createIssue`**

Currently writes files without a git commit, leaving untracked files. Add after the file write:

```scala
gitService.add(boardPath, List(".")) *>
  gitService.commit(boardPath, s"create: ${issue.id}")
```

This ensures every board file is always committed in the board repo and no untracked files accumulate.

---

## Error Handling

- `git init` failure in `initBoard` is surfaced as `BoardError` — same pattern as existing git errors in the repository. No retry; if it fails, workspace setup fails visibly.
- Appending to `.gitignore` is a best-effort file write; failure is surfaced as `BoardError`.

---

## Migration

Workspaces where `.board/` is already tracked in workspace git:

- `initBoard` detects an existing `.board/.git` and skips `git init`, but still ensures `/.board/` is in `.gitignore`.
- Once `.gitignore` is updated, workspace `git status` will no longer show `.board/` contents as untracked.
- If `.board/` files were previously committed to workspace git, they remain in workspace history (no automated removal). Users can run `git rm -r --cached .board/` manually to untrack them; this is out of scope.

---

## Testing

### `BoardOrchestratorSpec`

- Assert `completeSuccess` invokes `moveIssue` with `BoardColumn.Review` (not `Done`).

### `BoardRepositoryFSSpec` (integration, temp directory)

After `initBoard`:
- `.board/.git` exists.
- Workspace `.gitignore` contains `/.board/`.
- Board repo has exactly one commit (`init: board repository`).

After `createIssue`:
- Board repo has a second commit.
- `git status` inside `.board/` shows no untracked files.
- Workspace `git status` shows no changes.

After `moveIssue`:
- Issue file is at the new column path.
- Board repo has a new commit.
- Workspace `git status` shows no changes.

---

## Files to Change

| File | Change |
|---|---|
| `src/main/scala/board/control/BoardOrchestrator.scala` | `Done` → `Review` in `completeSuccess` |
| `src/main/scala/board/control/BoardRepositoryFS.scala` | `initBoard`: git init + gitignore; all git calls: `workspacePath` → `boardPath`; `createIssue`: add commit |
| `src/test/scala/board/control/BoardOrchestratorSpec.scala` | Assert `Review` column in `completeSuccess` test |
| `src/test/scala/board/control/BoardRepositoryFSSpec.scala` | New integration tests (temp dir) |
