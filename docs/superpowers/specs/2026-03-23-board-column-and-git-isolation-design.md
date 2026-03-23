# Board Column Fix and `.board/` Git Isolation

**Date:** 2026-03-23
**Status:** Approved

---

## Problem

1. **Wrong destination column**: `BoardOrchestrator.completeSuccess` moves the issue to `BoardColumn.Done`. Correct destination is `BoardColumn.Review`.
2. **Apply blocked by board changes**: `.board/` lives inside the workspace git repo. Board commits pollute workspace `git status`, blocking "Apply to repo".

---

## Design

### Bug 1 — Human Review workflow

The current `completeSuccess` merges the branch immediately and moves the card straight to `Done`, bypassing any human oversight. The correct workflow is:

1. Run completes → card moves to **Review** (no merge yet, branch kept alive)
2. Human reviews the changes via the Git Changes panel
3. Human clicks **"Approve"** → system merges the branch, then moves card to **Done**

**Workflow after fix:**

| Event | From | To | Merge happens? |
|---|---|---|---|
| Run assigned | Todo | In Progress | No |
| Run completes | In Progress | Review | **No** — branch preserved |
| Human clicks Approve | Review | Done | **Yes** — merge + cleanup |

#### `completeSuccess` — revised

Remove the merge, the cleanup, and the field-clearing that depend on the merge. Only move the card to Review and record proof-of-work:

```scala
private def completeSuccess(workspacePath: String, issueId: BoardIssueId, details: String): IO[BoardError, Unit] =
  for
    now <- Clock.instant
    _   <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Review)
    _   <- boardRepository.updateIssue(
             workspacePath,
             issueId,
             _.copy(
               transientState = TransientState.None,
               failureReason  = None,
               proofOfWork    = appendDetail(fm.proofOfWork, details),
             ),
           )
  yield ()
```

Fields that are **not** changed in `completeSuccess`:
- `branchName` — preserved; the Approve action needs it to merge
- `completedAt` — stays `None`; set to `Some(now)` only when Done
- No `mergeNoFastForward`, no `cleanupLatestRun`
- `ensureMainBranch` call removed from `completeSuccess` (no git operation here)

#### New `approveIssue` — `BoardOrchestrator` trait + live

Add to `BoardOrchestrator` trait:

```scala
def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]
```

Add companion object accessor and `BoardOrchestratorLive` implementation:

```scala
override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
  for
    _      <- ensureMainBranch(workspacePath)
    issue  <- boardRepository.readIssue(workspacePath, issueId)
    _      <- ZIO.fail(BoardError.ParseError(s"Issue ${issueId.value} is not in Review"))
                .unless(issue.column == BoardColumn.Review)
    branch <- ZIO
                .fromOption(issue.frontmatter.branchName.map(_.trim).filter(_.nonEmpty))
                .orElseFail(BoardError.ParseError(s"Issue '${issueId.value}' has no branchName"))
    now    <- Clock.instant
    _      <- boardRepository.updateIssue(workspacePath, issueId, _.copy(transientState = TransientState.Merging(now)))
    _      <- gitService
                .mergeNoFastForward(
                  workspacePath,
                  branch,
                  s"[board] Merge issue ${issueId.value}: ${issue.frontmatter.title}",
                )
                .mapError(mapGitError("git merge --no-ff"))
    now2   <- Clock.instant
    _      <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Done)
    _      <- boardRepository.updateIssue(
                workspacePath,
                issueId,
                _.copy(
                  transientState = TransientState.None,
                  completedAt    = Some(now2),
                  branchName     = None,
                ),
              )
    _      <- cleanupLatestRun(issueId)
  yield ()
```

On merge failure: `mapError` converts the `GitError` to `BoardError.GitOperationFailed` and the caller handles it. The card stays in Review with `transientState = TransientState.Merging` visible to the user, signalling that the merge was attempted but failed. A compensating `updateIssue` that resets `transientState = TransientState.None` and sets a `failureReason` should be added via `.catchAll` wrapping the merge + post-merge steps.

#### New endpoint — `BoardController`

Add route:

```scala
Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "approve" -> handler {
  (workspaceId: String, issueId: String, req: Request) =>
    approveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
}
```

Private handler:

```scala
private def approveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
  for
    workspace <- resolveWorkspace(workspaceId)
    issueId   <- readBoardIssueId(issueIdRaw)
    _         <- boardOrchestrator.approveIssue(workspace.localPath, issueId)
    response  <- if isHtmx(req) then renderBoardFragment(workspaceId) else ZIO.succeed(Response(status = Status.NoContent))
  yield response
```

#### New "Approve" button — `BoardView`

On card detail page (`BoardView.detailPage`) and on the card in the board fragment (`BoardView.columnsFragment`), when `issue.column == BoardColumn.Review` and `issue.frontmatter.branchName.nonEmpty`:

```html
<form method="POST" action="/board/{workspaceId}/issues/{issueId}/approve"
      hx-post="/board/{workspaceId}/issues/{issueId}/approve"
      hx-target="#board-columns" hx-swap="outerHTML">
  <button type="submit" class="...">Approve</button>
</form>
```

The button is absent on all other columns and when `branchName` is empty.

---

### Bug 2 — `.board/` as standalone git repository

`.board/` becomes a self-contained git repository. The workspace `.gitignore` gets `/.board/` so workspace `git status` never shows board state. The Apply guard is unblocked automatically.

`BoardRepositoryFS` already has all the plumbing needed. The changes are surgical: introduce `boardPath` per method, update every git call site to use it, update the rollback mechanism, and initialise the board git repo in `initBoard`.

#### Shared derivations (per method that does git operations)

```scala
val boardPath   = workspacePath + "/" + boardRootFolder      // e.g. "/workspace/.board"
val boardRoot   = workspace.resolve(boardRootFolder)         // java.nio.file.Path for relativization
val boardGitDir = Paths.get(boardPath).resolve(".git")       // filesystem check, no git traversal
```

`boardRoot` is used to relativize paths: `relativize(boardRoot, someIssuePath)` produces `"todo/ISSUE-1"` instead of `".board/todo/ISSUE-1"`.

#### Complete list of `workspacePath` → `boardPath` changes

**`withMutationRollback` and `rollbackBoardChanges`:**

All five methods that call `withMutationRollback` (`initBoard`, `createIssue`, `moveIssue`, `updateIssue`, `deleteIssue`) currently pass `workspacePath`. All must be updated to pass `boardPath`.

```scala
// before (all five callers)
withMutationRollback(workspacePath) { ... }

// after (all five callers)
withMutationRollback(boardPath) { ... }
```

`rollbackBoardChanges` updated:
```scala
// before
private def rollbackBoardChanges(workspacePath: String): IO[BoardError, Unit] =
  runGit(workspacePath, "checkout", "--", boardRootFolder).unit
  // ↑ "boardRootFolder" (.board) as path arg was relative to workspace

// after
private def rollbackBoardChanges(boardPath: String): IO[BoardError, Unit] =
  runGit(boardPath, "checkout", "--", ".").unit
  // ↑ boardPath IS .board/, so "." resets everything inside it
```

**Direct `GitService` / `stageAndCommit` call sites (exhaustive):**

| Method | Current call | Updated call |
|---|---|---|
| `initBoard` | `stageAndCommit(workspacePath, List(boardRootFolder), ...)` | `stageAndCommit(boardPath, List("."), ...)` (see `initBoardGit`) |
| `createIssue` | `stageAndCommit(workspacePath, List(relativize(workspace, issueDir)), ...)` | `stageAndCommit(boardPath, List(relativize(boardRoot, issueDir)), ...)` |
| `moveIssue` | `gitService.mv(workspacePath, relativize(workspace, from), relativize(workspace, to))` | `gitService.mv(boardPath, relativize(boardRoot, from), relativize(boardRoot, to))` |
| `moveIssue` | `gitService.commit(workspacePath, ...)` | `gitService.commit(boardPath, ...)` |
| `updateIssue` | `stageAndCommit(workspacePath, List(relativize(workspace, issueFile)), ...)` | `stageAndCommit(boardPath, List(relativize(boardRoot, issueFile)), ...)` |
| `deleteIssue` | `gitService.rm(workspacePath, relativize(workspace, dir), ...)` | `gitService.rm(boardPath, relativize(boardRoot, dir), ...)` |
| `deleteIssue` | `gitService.commit(workspacePath, ...)` | `gitService.commit(boardPath, ...)` |
| `reconcileDuplicateIssuePlacements` | `gitService.rm(workspacePath, relativize(workspace, dir), ...)` | `gitService.rm(boardPath, relativize(boardRoot, dir), ...)` |
| `reconcileDuplicateIssuePlacements` | `gitService.commit(workspacePath, ...).when(toRemove.nonEmpty)` | `gitService.commit(boardPath, ...).when(toRemove.nonEmpty)` — preserve `.when` guard |

#### `initBoard` — detailed restructure

```scala
override def initBoard(workspacePath: String): IO[BoardError, Unit] =
  withWorkspaceLock(workspacePath) {
    val boardPath   = workspacePath + "/" + boardRootFolder
    val boardGitDir = Paths.get(boardPath).resolve(".git")
    withMutationRollback(boardPath) {
      for
        workspace <- ensureWorkspaceDirectory(workspacePath)
        boardRoot  = workspace.resolve(boardRootFolder)
        exists    <- pathExists(boardRoot)
        _         <- createBoardFilesystem(boardRoot, createOverview = !exists)
        _         <- initBoardGit(boardPath, boardGitDir)
      yield ()
    } *>
    updateGitignore(workspacePath)   // Phase 2: always runs, outside rollback wrapper
  }
```

**`initBoardGit(boardPath, boardGitDir)` — new private method:**

```scala
private def initBoardGit(boardPath: String, boardGitDir: Path): IO[BoardError, Unit] =
  for
    gitDirExists <- pathExists(boardGitDir)   // filesystem check — no git upward traversal
    _            <- ZIO.unless(gitDirExists)(runGit(boardPath, "init").unit)
    // .board/.git/ now exists; ensureRepo inside GitService correctly targets board repo
    hasCommits   <- gitService.log(boardPath, 1)
                      .map(_.nonEmpty)
                      .catchAll(_ => ZIO.succeed(false))
    _            <- ZIO.unless(hasCommits)(
                      stageAndCommit(boardPath, List("."), "[board] Init: board structure")
                    )
  yield ()
```

The filesystem `pathExists(boardGitDir)` check is critical: it prevents calling `gitService.log` (which calls `ensureRepo` → `git rev-parse --is-inside-work-tree`) before `.board/.git/` exists. Without this check, git would walk upward and find the workspace repo instead.

Migration cases handled:

| State | `gitDirExists` | `hasCommits` | Action |
|---|---|---|---|
| Fresh | false | — | `git init` + `stageAndCommit` |
| Legacy (`.board/` exists, no `.git/`) | false | — | `git init` + `stageAndCommit` |
| Corrupt (`.git/` exists, no commits) | true | false | `stageAndCommit` only |
| Fully initialised | true | true | no-op |

**Rollback behaviour in partial-init cases:**

- If `git init` itself fails (before `.board/.git/` exists): rollback fires, `git checkout -- .` has no git repo to target, fails silently (`.ignore`). The filesystem changes (`BOARD.md`, column dirs) are left on disk. This is acceptable — on the next `initBoard` call, `gitDirExists=false` → `git init` is retried, and `createBoardFilesystem` re-runs safely (idempotent). No extra handling is needed in the implementation.
- If `git init` succeeds but `stageAndCommit` fails: rollback fires, `git checkout -- .` on a headless repo (no commits) fails silently. `.board/.git/` remains with no commits. On the next `initBoard` call: `gitDirExists=true`, `hasCommits=false` → `stageAndCommit` retried. `git init` is not re-run.

**`updateGitignore(workspacePath)` — new private method:**

Reads `workspacePath/.gitignore` (empty string if file absent). If `/.board/` is not already a line, appends it. Writes atomically: compose full content, write to a temp file in the same directory, then `Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)`. On failure → `BoardError.WriteError`. Idempotent if `/.board/` already present.

This runs outside `withMutationRollback` — a board git rollback must not reverse the `.gitignore` update.

---

## Error Handling

| Source | Error |
|---|---|
| `git init` / direct git ops | `BoardError.GitOperationFailed` via `runGit` |
| `stageAndCommit` inner calls | Propagated as `BoardError.GitOperationFailed` |
| `updateGitignore` | `BoardError.WriteError` |
| Rollback failures | Silently ignored by existing `.ignore` (line 208 of `BoardRepositoryFS`) |

---

## Migration

| Workspace state | Outcome |
|---|---|
| `.board/` never committed in workspace git | Workspace `git status` clean immediately after `.gitignore` update |
| `.board/` previously committed in workspace git | `.gitignore` update stops tracking *new* untracked files; existing tracked files remain until `git rm -r --cached .board/` is run manually. Out of scope. |

---

## Testing

### `BoardOrchestratorSpec`

- **Line 266**: rename test to `"completeIssue success merges branch, moves issue to review, and triggers cleanup"`.
- **Line 296**: rename `val doneIssue` to `val reviewIssue` (and all references in the test).
- **Line 300**: change assertion `doneIssue.column == BoardColumn.Done` to `reviewIssue.column == BoardColumn.Review`.
- All other assertions unchanged.

### `BoardRepositoryFSSpec`

All four existing tests assert git log and/or run git commands against `repoPath` (the workspace git repo). After the change, board commits live in `.board/` — the workspace git log will be empty for board operations. All four tests must be updated:

**Test 1 — `"initBoard creates .board structure and initial commit"`:**
- Change `runCmd(repoPath, "git", "log", "--oneline", "-n", "1")` to `runCmd(repoPath.resolve(".board"), "git", "log", "--oneline", "-n", "1")`.
- Add assertion: `repoPath.resolve(".gitignore")` contents contain `/.board/`.
- Filesystem assertions for directory structure remain unchanged.

**Test 2 — `"full CRUD operations persist and produce expected commit messages"`:**
- Change `runCmd(repoPath, "git", "log", "--pretty=%s", "-n", "5")` to `runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "5")`.
- All commit message assertions remain unchanged.

**Test 3 — `"concurrent createIssue operations are serialized by workspace lock"`:**
- Change `runCmd(repoPath, "git", "log", "--pretty=%s")` to `runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s")`.

**Test 4 — `"readBoard repairs duplicate issue placement across columns and commits repair"`:**
- `initRepo` still runs `git init` on `repoPath` — this is correct, workspace git must still exist for `git status` assertions elsewhere. After `initBoard`, `.board/` is its own git repo and is gitignored in the workspace.
- The test currently stages and commits the simulated duplicate via workspace git (`git add .board/...`; `git commit`). After the change, `.board/` is gitignored in the workspace, so `git add .board/...` on the workspace repo will produce no staged files and the commit will fail. The simulation must target the board git repo instead:
  ```scala
  // before (workspace git)
  _        <- runCmd(repoPath, "git", "add", ".board/review/dup-issue")
  _        <- runCmd(repoPath, "git", "commit", "-m", "simulate duplicate placement from merge")

  // after (board git)
  _        <- runCmd(repoPath.resolve(".board"), "git", "add", "review/dup-issue")
  _        <- runCmd(repoPath.resolve(".board"), "git", "commit", "-m", "simulate duplicate placement from merge")
  ```
- Change `runCmd(repoPath, "git", "log", "--pretty=%s", "-n", "1")` to `runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "1")`.

**New tests to add (to the same spec object):**

- **Idempotent `initBoard`**: call `initBoard` twice; second call succeeds; board git commit count unchanged; `/.board/` appears exactly once in `.gitignore`.
- **`.gitignore` deduplication**: write `/.board/` to `.gitignore` before calling `initBoard`; after `initBoard`, `/.board/` still appears exactly once.

---

## Files to Change

| File | Change |
|---|---|
| `src/main/scala/board/control/BoardOrchestrator.scala` | Line 253: `Done` → `Review` |
| `src/main/scala/board/control/BoardRepositoryFS.scala` | `initBoard`: restructure + `initBoardGit` helper + `updateGitignore` outside rollback; `withMutationRollback`/`rollbackBoardChanges`: accept `boardPath`, change git arg to `.`; all call sites per exhaustive table above |
| `src/test/scala/board/control/BoardOrchestratorSpec.scala` | Line 266 rename; line 296 `doneIssue` → `reviewIssue`; line 300 `Done` → `Review` |
| `src/test/scala/board/control/BoardRepositoryFSSpec.scala` | Update all 4 existing tests; add 2 new tests |
