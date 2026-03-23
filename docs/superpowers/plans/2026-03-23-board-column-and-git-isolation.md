# Board Column Fix and `.board/` Git Isolation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `completeSuccess` to move cards to Review (not Done) and add an `approveIssue` merge flow; then isolate `.board/` as a standalone git repo so board commits never pollute workspace `git status`.

**Architecture:** Bug 1 adds a human-review gate: `completeSuccess` stops merging and only moves the card to Review; a new `approveIssue` method (orchestrator + HTTP route + UI button) handles the merge and moves the card to Done. Bug 2 makes `BoardRepositoryFS` run all git operations inside `.board/` (its own repo), with the workspace `.gitignore` updated to exclude it.

**Tech Stack:** Scala 3.5.2, ZIO 2.x, ZIO Test, ZIO-HTTP, ScalaTags, `zio-process` (for `runGit`), `java.nio.file` (atomic `.gitignore` writes).

---

> **Note — independent subsystems:** Bug 1 (BoardOrchestrator / BoardController / BoardView) and Bug 2 (BoardRepositoryFS) touch non-overlapping files. Either task group can be executed first or in a separate session without blocking the other.

---

## File Map

| File | Action |
|---|---|
| `src/main/scala/board/control/BoardOrchestrator.scala` | Add `approveIssue` to trait + companion; rewrite `completeSuccess` |
| `src/main/scala/board/boundary/BoardController.scala` | Add `POST .../approve` route |
| `src/main/scala/shared/web/BoardView.scala` | Add Approve button to card and detail page |
| `src/main/scala/board/control/BoardRepositoryFS.scala` | `initBoard` restructure; `initBoardGit`; `updateGitignore`; update all git call sites; update `rollbackBoardChanges` |
| `src/test/scala/board/control/BoardOrchestratorSpec.scala` | Update existing `completeIssue success` test; add `approveIssue` test |
| `src/test/scala/board/control/BoardRepositoryFSSpec.scala` | Update all 4 existing tests; add 2 new tests |

---

## Bug 1 — Human Review Workflow

---

### Task 1: Update `completeIssue success` test to expect Review

**Files:**
- Modify: `src/test/scala/board/control/BoardOrchestratorSpec.scala`

The existing test asserts `Done`, merge, cleanup, cleared branchName, and set `completedAt`. All of that moves to the new `approveIssue`. This task re-targets the test at the stripped-down `completeSuccess` that only moves to Review.

- [ ] **Step 1: Open the test file and locate the test at line 268**

```scala
test("completeIssue success merges branch, moves issue to done, and triggers cleanup") {
```

- [ ] **Step 2: Replace the test block with the updated version**

> **Note — spec deviation:** The spec says "All other assertions unchanged" for this test, but that instruction is wrong. The old `completeSuccess` merged, cleaned up, cleared `branchName`, and set `completedAt`. None of that happens anymore. The assertions below correctly reflect the new `completeSuccess` behaviour. The spec's rename suggestion (`"completeIssue success merges branch, moves issue to review..."`) is also misleading since `completeSuccess` no longer merges — `approveIssue` (Task 4) handles that. The test name below is accurate.

Replace the entire test from `test("completeIssue success ...") {` through its closing `},` with:

```scala
test("completeIssue success moves issue to review, preserving branch") {
  val inProgress = issue("task-2", BoardColumn.InProgress, branchName = Some("agent/agent-default-task-2"))

  val run = WorkspaceRun(
    id = "run-2",
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = "task-2",
    agentName = "agent-default",
    prompt = "Body",
    conversationId = "1",
    worktreePath = s"$workspacePath/.worktree/run-2",
    branchName = "agent/agent-default-task-2",
    status = RunStatus.Completed,
    attachedUsers = Set.empty,
    controllerUserId = None,
    createdAt = Instant.parse("2026-03-20T10:00:00Z"),
    updatedAt = Instant.parse("2026-03-20T10:10:00Z"),
  )

  for
    (orchestrator, boardRef, _, cleanupRef, mergesRef) <-
      makeOrchestrator(List(inProgress), runsByIssueRefSeed = Map("task-2" -> List(run)))
    _                                                  <- orchestrator.completeIssue(
                                                            workspacePath,
                                                            BoardIssueId("task-2"),
                                                            success = true,
                                                            details = "linked PR #22",
                                                          )
    state                                              <- boardRef.get
    reviewIssue                                         = state(BoardIssueId("task-2"))
    cleanup                                            <- cleanupRef.get
    merges                                             <- mergesRef.get
  yield assertTrue(
    reviewIssue.column == BoardColumn.Review,
    reviewIssue.frontmatter.completedAt.isEmpty,
    reviewIssue.frontmatter.branchName.contains("agent/agent-default-task-2"),
    reviewIssue.frontmatter.proofOfWork.contains("linked PR #22"),
    cleanup.isEmpty,
    merges.isEmpty,
  )
},
```

- [ ] **Step 3: Run only this test to confirm it fails (expected — implementation not changed yet)**

```
sbt "testOnly board.control.BoardOrchestratorSpec"
```

Expected: FAIL on `reviewIssue.column == BoardColumn.Review` (actual: `Done`)

---

### Task 2: Rewrite `completeSuccess` in `BoardOrchestrator`

**Files:**
- Modify: `src/main/scala/board/control/BoardOrchestrator.scala`

- [ ] **Step 1: Replace `completeSuccess` (lines 238–267)**

> **Note — spec snippet fix:** The spec's code block for `completeSuccess` writes `_.copy(proofOfWork = appendDetail(fm.proofOfWork, details))` where `fm` is unbound. The correct form uses a named lambda parameter: `fm => fm.copy(...)`. Use the snippet below, not the spec's snippet.

```scala
private def completeSuccess(workspacePath: String, issueId: BoardIssueId, details: String): IO[BoardError, Unit] =
  for
    _ <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Review)
    _ <- boardRepository.updateIssue(
           workspacePath,
           issueId,
           fm =>
             fm.copy(
               transientState = TransientState.None,
               failureReason  = None,
               proofOfWork    = appendDetail(fm.proofOfWork, details),
             ),
         )
  yield ()
```

Removed from `completeSuccess`: `ensureMainBranch`, `readIssue`, `mergeNoFastForward`, `completedAt`, `branchName = None`, `cleanupLatestRun`.

- [ ] **Step 2: Run the test to confirm it now passes**

```
sbt "testOnly board.control.BoardOrchestratorSpec"
```

Expected: PASS (all 3 tests green)

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/board/control/BoardOrchestrator.scala \
        src/test/scala/board/control/BoardOrchestratorSpec.scala
git commit -m "fix(board): completeSuccess moves to Review instead of Done"
```

---

### Task 3: Write failing test for `approveIssue`

**Files:**
- Modify: `src/test/scala/board/control/BoardOrchestratorSpec.scala`

- [ ] **Step 1: Add new test inside `suite("BoardOrchestratorSpec")(...)` after the failure test**

```scala
test("approveIssue merges branch, moves issue to done, and triggers cleanup") {
  val reviewIssue = issue("task-4", BoardColumn.Review, branchName = Some("agent/agent-default-task-4"))

  val run = WorkspaceRun(
    id = "run-4",
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = "task-4",
    agentName = "agent-default",
    prompt = "Body for task-4",
    conversationId = "1",
    worktreePath = s"$workspacePath/.worktree/run-4",
    branchName = "agent/agent-default-task-4",
    status = RunStatus.Completed,
    attachedUsers = Set.empty,
    controllerUserId = None,
    createdAt = Instant.parse("2026-03-20T10:00:00Z"),
    updatedAt = Instant.parse("2026-03-20T10:10:00Z"),
  )

  for
    (orchestrator, boardRef, _, cleanupRef, mergesRef) <-
      makeOrchestrator(List(reviewIssue), runsByIssueRefSeed = Map("task-4" -> List(run)))
    _                                                  <- orchestrator.approveIssue(workspacePath, BoardIssueId("task-4"))
    state                                              <- boardRef.get
    doneIssue                                           = state(BoardIssueId("task-4"))
    cleanup                                            <- cleanupRef.get
    merges                                             <- mergesRef.get
  yield assertTrue(
    doneIssue.column == BoardColumn.Done,
    doneIssue.frontmatter.completedAt.nonEmpty,
    doneIssue.frontmatter.branchName.isEmpty,
    cleanup == List("run-4"),
    merges.exists { case (_, branch, _) => branch == "agent/agent-default-task-4" },
  )
},
```

- [ ] **Step 2: Run to confirm compilation failure (method does not exist yet)**

```
sbt "testOnly board.control.BoardOrchestratorSpec"
```

Expected: compile error — `value approveIssue is not a member of BoardOrchestratorLive`

---

### Task 4: Implement `approveIssue` in `BoardOrchestrator`

**Files:**
- Modify: `src/main/scala/board/control/BoardOrchestrator.scala`

- [ ] **Step 1: Add `approveIssue` to the `BoardOrchestrator` trait (after `completeIssue`)**

```scala
def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]
```

- [ ] **Step 2: Add `approveIssue` accessor to the `BoardOrchestrator` companion object (after the `markIssueStarted` accessor)**

```scala
def approveIssue(
  workspacePath: String,
  issueId: BoardIssueId,
): ZIO[BoardOrchestrator, BoardError, Unit] =
  ZIO.serviceWithZIO[BoardOrchestrator](_.approveIssue(workspacePath, issueId))
```

- [ ] **Step 3: Implement `approveIssue` in `BoardOrchestratorLive` (add after `completeIssue` override)**

```scala
override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
  for
    _      <- ensureMainBranch(workspacePath)
    issue  <- boardRepository.readIssue(workspacePath, issueId)
    _      <- ZIO
                .fail(BoardError.ParseError(s"Issue '${issueId.value}' is not in Review"))
                .unless(issue.column == BoardColumn.Review)
    branch <- ZIO
                .fromOption(issue.frontmatter.branchName.map(_.trim).filter(_.nonEmpty))
                .orElseFail(BoardError.ParseError(s"Issue '${issueId.value}' has no branchName"))
    now    <- Clock.instant
    _      <- boardRepository.updateIssue(workspacePath, issueId, _.copy(transientState = TransientState.Merging(now)))
    _      <- (for
                 _    <- gitService
                           .mergeNoFastForward(
                             workspacePath,
                             branch,
                             s"[board] Merge issue ${issueId.value}: ${issue.frontmatter.title}",
                           )
                           .mapError(mapGitError("git merge --no-ff"))
                 now2 <- Clock.instant
                 _    <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Done)
                 _    <- boardRepository.updateIssue(
                           workspacePath,
                           issueId,
                           _.copy(
                             transientState = TransientState.None,
                             completedAt    = Some(now2),
                             branchName     = None,
                           ),
                         )
                 _    <- cleanupLatestRun(issueId)
               yield ())
               .catchAll { err =>
                 boardRepository
                   .updateIssue(
                     workspacePath,
                     issueId,
                     _.copy(
                       transientState = TransientState.None,
                       failureReason  = Some(renderBoardError(err)),
                     ),
                   )
                   .ignore *> ZIO.fail(err)
               }
  yield ()
```

- [ ] **Step 4: Run all orchestrator tests**

```
sbt "testOnly board.control.BoardOrchestratorSpec"
```

Expected: PASS (4 tests green)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/board/control/BoardOrchestrator.scala \
        src/test/scala/board/control/BoardOrchestratorSpec.scala
git commit -m "feat(board): add approveIssue — merge branch and move card to Done"
```

---

### Task 5: Add `POST .../approve` route to `BoardController`

**Files:**
- Modify: `src/main/scala/board/boundary/BoardController.scala`

No unit test for the HTTP layer — the route is thin delegation to `boardOrchestrator.approveIssue` (already tested). Verify by reading the route addition carefully.

- [ ] **Step 1: Add the approve route inside `Routes(...)` in `BoardControllerLive.routes`, after the `dispatch` route**

```scala
Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "approve" -> handler {
  (workspaceId: String, issueId: String, req: Request) =>
    approveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
},
```

- [ ] **Step 2: Add the `approveIssue` private handler method to `BoardControllerLive`, after the `dispatch` private method**

```scala
private def approveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
  for
    workspace <- resolveWorkspace(workspaceId)
    issueId   <- readBoardIssueId(issueIdRaw)
    _         <- boardOrchestrator.approveIssue(workspace.localPath, issueId)
    response  <-
      if isHtmx(req) then renderBoardFragment(workspaceId)
      else ZIO.succeed(Response.redirect(URL.decode(s"/board/$workspaceId").toOption.getOrElse(URL.root)))
  yield response
```

`Response.redirect` takes a `URL`, not a `String` — use `URL.decode(...).toOption.getOrElse(URL.root)` (same pattern as `ChatController.redirect`). The non-HTMX path redirects to the board page, which handles the detail-page Approve button (a plain form POST) cleanly.

- [ ] **Step 3: Compile to confirm no errors**

```
sbt compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/board/boundary/BoardController.scala
git commit -m "feat(board): add POST .../approve route"
```

---

### Task 6: Add Approve button to `BoardView`

**Files:**
- Modify: `src/main/scala/shared/web/BoardView.scala`

The button appears only on Review cards that have a non-empty `branchName`. It posts to the approve endpoint and, being an HTMX form, refreshes the board columns fragment.

- [ ] **Step 1: Update `issueCard` to append the Approve button for Review issues with a branch**

> **Note — HTMX target:** The spec says `hx-target="#board-columns"` but that ID does not exist in the DOM. The correct target is `#fs-board-root` (the `div` in `page` that wraps the columns fragment, confirmed at `BoardView.scala` line 58). It uses `hx-swap="innerHTML"`, which is consistent with the existing board auto-refresh setup.

In `issueCard`, locate the existing `div(cls := "mt-2 flex items-center justify-end gap-2")(...)` block (the one containing the Delete button). Replace it with:

```scala
div(cls := "mt-2 flex items-center justify-end gap-2")(
  if column == BoardColumn.Review && issue.frontmatter.branchName.exists(_.nonEmpty) then
    form(
      method          := "post",
      action          := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
      attr("hx-post") := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
      attr("hx-target") := "#fs-board-root",
      attr("hx-swap")   := "innerHTML",
    )(
      button(
        `type` := "submit",
        cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-2 py-1 text-[10px] font-semibold text-emerald-100 hover:bg-emerald-500/30",
      )("Approve")
    )
  else (),
  button(
    `type`                    := "button",
    cls                       := "rounded border border-white/20 px-2 py-1 text-[10px] text-slate-200 hover:bg-white/10",
    attr("data-board-delete") := issue.frontmatter.id.value,
  )("Delete"),
),
```

- [ ] **Step 2: Update `detailPage` to show the Approve button for Review issues**

The detail page does not contain the `#fs-board-root` div, so the button is a plain form POST (no HTMX). The controller returns `Response.redirect(s"/board/$workspaceId")` for non-HTMX requests (Task 5 Step 2), so the user lands back on the board after approving.

In `detailPage`, locate the `div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(` block that renders the badges. Add the Approve button below the badges block, before the markdown block:

```scala
if issue.column == BoardColumn.Review && issue.frontmatter.branchName.exists(_.nonEmpty) then
  div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
    form(
      method := "post",
      action := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
    )(
      button(
        `type` := "submit",
        cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-100 hover:bg-emerald-500/30",
      )("Approve & Merge")
    )
  )
else (),
```

- [ ] **Step 3: Compile**

```
sbt compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/shared/web/BoardView.scala
git commit -m "feat(board): add Approve button on Review cards"
```

---

## Bug 2 — `.board/` as Standalone Git Repository

---

### Task 7: Update `BoardRepositoryFSSpec` test 1 to expect board-local git

**Files:**
- Modify: `src/test/scala/board/control/BoardRepositoryFSSpec.scala`

- [ ] **Step 1: Replace the `"initBoard creates .board structure and initial commit"` test**

```scala
test("initBoard creates .board structure and initial commit") {
  ZIO.scoped {
    for
      repoPath  <- initRepo
      repo      <- repository
      _         <- repo.initBoard(repoPath.toString)
      boardDir   = repoPath.resolve(".board")
      checks    <- ZIO.attempt(
                     (
                       JFiles.exists(boardDir.resolve("BOARD.md")),
                       JFiles.isDirectory(boardDir.resolve("backlog")),
                       JFiles.isDirectory(boardDir.resolve("todo")),
                       JFiles.isDirectory(boardDir.resolve("in-progress")),
                       JFiles.isDirectory(boardDir.resolve("review")),
                       JFiles.isDirectory(boardDir.resolve("done")),
                       JFiles.isDirectory(boardDir.resolve("archive")),
                     )
                   )
      boardLog  <- runCmd(repoPath.resolve(".board"), "git", "log", "--oneline", "-n", "1")
      gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
    yield assertTrue(
      checks._1,
      boardLog.contains("[board] Init: board structure"),
      checks._2,
      checks._3,
      checks._4,
      checks._5,
      checks._6,
      checks._7,
      gitignore.linesIterator.exists(_.trim == "/.board/"),
    )
  }
},
```

- [ ] **Step 2: Run test 1 only to confirm it fails**

```
sbt "testOnly board.control.BoardRepositoryFSSpec"
```

Expected: FAIL — `runCmd` targets `.board` which has no git repo yet; or the board log assertion fails because commits live in workspace git

---

### Task 8: Restructure `initBoard` — add `initBoardGit` and `updateGitignore`

**Files:**
- Modify: `src/main/scala/board/control/BoardRepositoryFS.scala`

- [ ] **Step 1: Update the `initBoard` override**

Replace the existing `initBoard` method with:

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
    updateGitignore(workspacePath)
  }
```

- [ ] **Step 2: Update `rollbackBoardChanges`**

Replace existing `rollbackBoardChanges`:

```scala
private def rollbackBoardChanges(boardPath: String): IO[BoardError, Unit] =
  runGit(boardPath, "checkout", "--", ".").unit
```

The argument is now `boardPath` (the `.board/` directory). `"."` resets everything inside it. The method signature parameter name changes from `workspacePath` to `boardPath` — this is a local rename only; all callers pass their own `boardPath` variable via `withMutationRollback`.

- [ ] **Step 3: Add `initBoardGit` private method (add after `createBoardFilesystem`)**

```scala
private def initBoardGit(boardPath: String, boardGitDir: Path): IO[BoardError, Unit] =
  for
    gitDirExists <- pathExists(boardGitDir)
    _            <- ZIO.unless(gitDirExists)(runGit(boardPath, "init").unit)
    hasCommits   <- gitService
                      .log(boardPath, 1)
                      .map(_.nonEmpty)
                      .catchAll(_ => ZIO.succeed(false))
    _            <- ZIO.unless(hasCommits)(
                      stageAndCommit(boardPath, List("."), "[board] Init: board structure")
                    )
  yield ()
```

Key detail: `pathExists(boardGitDir)` is a filesystem check via `JFiles.exists`. This prevents calling `gitService.log` (which runs `git rev-parse`) before `.board/.git/` exists — without the guard, git would walk upward and find the workspace repo instead.

- [ ] **Step 4: Add `updateGitignore` private method (add after `initBoardGit`)**

```scala
private def updateGitignore(workspacePath: String): IO[BoardError, Unit] =
  val gitignorePath = Paths.get(workspacePath).resolve(".gitignore")
  ZIO
    .attemptBlocking {
      val existing = if JFiles.exists(gitignorePath) then JFiles.readString(gitignorePath) else ""
      if !existing.linesIterator.exists(_.trim == "/.board/") then
        val sep        = if existing.nonEmpty && !existing.endsWith("\n") then "\n" else ""
        val newContent = s"$existing${sep}/.board/\n"
        val tmp        = JFiles.createTempFile(Paths.get(workspacePath), ".gitignore-", null)
        JFiles.writeString(tmp, newContent)
        JFiles.move(tmp, gitignorePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        ()
    }
    .mapError(err => BoardError.WriteError(gitignorePath.toString, err.getMessage))
```

`updateGitignore` is called outside `withMutationRollback` — a board git rollback must not revert the `.gitignore` update.

- [ ] **Step 5: Run test 1 to confirm it passes**

```
sbt "testOnly board.control.BoardRepositoryFSSpec -- -t \"initBoard creates\""
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/board/control/BoardRepositoryFS.scala \
        src/test/scala/board/control/BoardRepositoryFSSpec.scala
git commit -m "feat(board): initBoard creates standalone .board git repo and updates .gitignore"
```

---

### Task 9: Update test 2 (full CRUD) and fix all non-init git call sites

**Files:**
- Modify: `src/test/scala/board/control/BoardRepositoryFSSpec.scala`
- Modify: `src/main/scala/board/control/BoardRepositoryFS.scala`

- [ ] **Step 1: Update test 2 — change `runCmd` target from workspace to board git**

In test `"full CRUD operations persist and produce expected commit messages"`, change:

```scala
// before
logs <- runCmd(repoPath, "git", "log", "--pretty=%s", "-n", "5")

// after
logs <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "5")
```

- [ ] **Step 2: Run tests to confirm test 2 now fails (implementation not yet updated)**

```
sbt "testOnly board.control.BoardRepositoryFSSpec"
```

Expected: test 1 PASS, test 2 FAIL (commit messages in wrong git log)

- [ ] **Step 3: Update `createIssue` to use `boardPath` and `boardRoot`**

In `createIssue`, after obtaining `workspace` from `ensureWorkspaceDirectory`, derive:

```scala
val boardPath = workspacePath + "/" + boardRootFolder
val boardRoot = workspace.resolve(boardRootFolder)
```

Change `withMutationRollback(workspacePath)` to `withMutationRollback(boardPath)`.

Change the `stageAndCommit` call:
```scala
// before
_  <- stageAndCommit(
        workspacePath = workspacePath,
        addPaths = List(relativize(workspace, issueDir)),
        commitMessage = s"[board] Create: ${issue.frontmatter.title}",
      )

// after
_  <- stageAndCommit(
        workspacePath = boardPath,
        addPaths = List(relativize(boardRoot, issueDir)),
        commitMessage = s"[board] Create: ${issue.frontmatter.title}",
      )
```

- [ ] **Step 4: Update `moveIssue` to use `boardPath` and `boardRoot`**

In `moveIssue`, derive `boardPath` and `boardRoot` after `ensureWorkspaceDirectory`. Change `withMutationRollback` call to use `boardPath`. Update the two git calls:

```scala
// before
_ <- gitService.mv(workspacePath, relativize(workspace, fromPath), relativize(workspace, toPath))
               .mapError(mapGitError("git mv"))
_ <- gitService.commit(workspacePath, s"[board] Move: ...")
               .mapError(mapGitError("git commit"))

// after
_ <- gitService.mv(boardPath, relativize(boardRoot, fromPath), relativize(boardRoot, toPath))
               .mapError(mapGitError("git mv"))
_ <- gitService.commit(boardPath, s"[board] Move: ...")
               .mapError(mapGitError("git commit"))
```

- [ ] **Step 5: Update `updateIssue` to use `boardPath` and `boardRoot`**

In `updateIssue`, derive `boardPath` and `boardRoot`. Change `withMutationRollback` to use `boardPath`. Update `stageAndCommit`:

```scala
// before
_ <- stageAndCommit(
       workspacePath = workspacePath,
       addPaths = List(relativize(workspace, issueFile)),
       commitMessage = s"[board] Update: ${issueId.value}",
     )

// after
_ <- stageAndCommit(
       workspacePath = boardPath,
       addPaths = List(relativize(boardRoot, issueFile)),
       commitMessage = s"[board] Update: ${issueId.value}",
     )
```

- [ ] **Step 6: Update `deleteIssue` to use `boardPath` and `boardRoot`**

In `deleteIssue`, derive `boardPath` and `boardRoot`. Change `withMutationRollback` to use `boardPath`. Update the two git calls:

```scala
// before
_ <- gitService.rm(workspacePath, relativize(workspace, location.issueDirectory), recursive = true)
               .mapError(mapGitError("git rm"))
_ <- gitService.commit(workspacePath, s"[board] Delete: ${issueId.value}")
               .mapError(mapGitError("git commit"))

// after
_ <- gitService.rm(boardPath, relativize(boardRoot, location.issueDirectory), recursive = true)
               .mapError(mapGitError("git rm"))
_ <- gitService.commit(boardPath, s"[board] Delete: ${issueId.value}")
               .mapError(mapGitError("git commit"))
```

- [ ] **Step 7: Update `reconcileDuplicateIssuePlacements` to use `boardPath` and `boardRoot`**

`reconcileDuplicateIssuePlacements` signature is `(workspacePath: String, workspace: Path)`. Derive at the top of the method:

```scala
val boardPath = workspacePath + "/" + boardRootFolder
val boardRoot = workspace.resolve(boardRootFolder)
```

Update the two git calls:

```scala
// before
_ <- ZIO.foreachDiscard(toRemove) { dir =>
       gitService.rm(workspacePath, relativize(workspace, dir), recursive = true)
                 .mapError(mapGitError("git rm"))
     }
_ <- gitService.commit(workspacePath, s"[board] Repair: deduplicate issue placements (${toRemove.size})")
               .mapError(mapGitError("git commit"))
               .when(toRemove.nonEmpty)

// after
_ <- ZIO.foreachDiscard(toRemove) { dir =>
       gitService.rm(boardPath, relativize(boardRoot, dir), recursive = true)
                 .mapError(mapGitError("git rm"))
     }
_ <- gitService.commit(boardPath, s"[board] Repair: deduplicate issue placements (${toRemove.size})")
               .mapError(mapGitError("git commit"))
               .when(toRemove.nonEmpty)    // ← preserve the .when guard
```

- [ ] **Step 8: Run tests 1 and 2**

```
sbt "testOnly board.control.BoardRepositoryFSSpec"
```

Expected: tests 1 and 2 PASS; tests 3 and 4 may still fail (not yet updated)

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/board/control/BoardRepositoryFS.scala \
        src/test/scala/board/control/BoardRepositoryFSSpec.scala
git commit -m "fix(board): all git operations now target .board standalone repo"
```

---

### Task 10: Update tests 3, 4 and add 2 new tests

**Files:**
- Modify: `src/test/scala/board/control/BoardRepositoryFSSpec.scala`

- [ ] **Step 1: Update test 3 — `"concurrent createIssue operations are serialized by workspace lock"`**

Change:
```scala
// before
logCount <- runCmd(repoPath, "git", "log", "--pretty=%s")
              .map(_.linesIterator.count(_.contains("[board] Create:")))

// after
logCount <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s")
              .map(_.linesIterator.count(_.contains("[board] Create:")))
```

- [ ] **Step 2: Update test 4 — `"readBoard repairs duplicate issue placement"`**

The test currently stages the simulated duplicate via workspace git. After the change `.board/` is gitignored in the workspace, so `git add .board/...` on the workspace repo stages nothing and the commit fails. Switch to board git:

```scala
// before
_ <- runCmd(repoPath, "git", "add", ".board/review/dup-issue")
_ <- runCmd(repoPath, "git", "commit", "-m", "simulate duplicate placement from merge")

// after
_ <- runCmd(repoPath.resolve(".board"), "git", "add", "review/dup-issue")
_ <- runCmd(repoPath.resolve(".board"), "git", "commit", "-m", "simulate duplicate placement from merge")
```

Also change the log assertion:
```scala
// before
logs <- runCmd(repoPath, "git", "log", "--pretty=%s", "-n", "1")

// after
logs <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "1")
```

- [ ] **Step 3: Add new test — idempotent `initBoard`**

Add after the existing 4 tests:

```scala
test("initBoard is idempotent — second call does not create extra commits") {
  ZIO.scoped {
    for
      repoPath  <- initRepo
      repo      <- repository
      _         <- repo.initBoard(repoPath.toString)
      _         <- repo.initBoard(repoPath.toString)
      logCount  <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s")
                     .map(_.linesIterator.count(_.contains("[board] Init:")))
      gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
    yield assertTrue(
      logCount == 1,
      gitignore.linesIterator.count(_.trim == "/.board/") == 1,
    )
  }
},
```

- [ ] **Step 4: Add new test — `.gitignore` deduplication**

```scala
test("updateGitignore does not add duplicate /.board/ entry when already present") {
  ZIO.scoped {
    for
      repoPath  <- initRepo
      repo      <- repository
      _         <- ZIO.attemptBlocking(
                     JFiles.writeString(repoPath.resolve(".gitignore"), "/.board/\n")
                   )
      _         <- repo.initBoard(repoPath.toString)
      gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
    yield assertTrue(
      gitignore.linesIterator.count(_.trim == "/.board/") == 1,
    )
  }
},
```

- [ ] **Step 5: Run all `BoardRepositoryFSSpec` tests**

```
sbt "testOnly board.control.BoardRepositoryFSSpec"
```

Expected: all 6 tests PASS

- [ ] **Step 6: Run the full test suite**

```
sbt test
```

Expected: all tests PASS (no regressions)

- [ ] **Step 7: Commit**

```bash
git add src/test/scala/board/control/BoardRepositoryFSSpec.scala
git commit -m "test(board): update all FS spec tests for standalone .board git repo"
```

---

## Done

All tasks complete. The board workflow now routes through `Review` before `Done`, with a human-controlled `approveIssue` merge step. `.board/` is a standalone git repo gitignored from the workspace, so `git status` in the workspace is clean while agents work.
