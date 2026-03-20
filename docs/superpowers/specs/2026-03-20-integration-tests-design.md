# Integration Tests Design — Gateway Workspace

**Date:** 2026-03-20
**Status:** Approved

---

## Context

One integration test already exists: `WorkspaceGoldenPathIntegrationSpec` covers the happy-path end-to-end flow (init → plan issues via mocked LLM → dispatch with dependency resolution → complete → merge). All file-system and git operations run against a real temporary repository; AI providers, workspace persistence, and the run service are stubbed.

This document specifies **five new integration tests** grouped into three files to complement that coverage.

---

## Approach

**Three files, grouped by theme:**

| File | Tests | Theme |
|---|---|---|
| `BoardOrchestratorFailureIntegrationSpec` | 2 | Orchestration failure modes |
| `DispatchConcurrencyIntegrationSpec` | 2 | Dispatch-level concurrency and pool behaviour |
| `AnalysisPipelineIntegrationSpec` | 1 | Analysis pipeline from runner to persistence |

Each file is self-contained. Shared setup helpers live in a new `IntegrationFixtures` object in the same package.

---

## Shared Fixtures — `IntegrationFixtures`

A plain `object` (no trait, no inheritance) in `src/it/scala/integration/`.

| Helper | Description |
|---|---|
| `initGitRepo` | `ZIO[Scope, Throwable, Path]` — acquireRelease: creates temp dir, `git init --initial-branch=main`, sets user config, writes `HelloWorld.scala`, initial commit. Deletes on scope exit. |
| `boardRepoFor(path, git, parser)` | Builds `BoardRepositoryFS` with a fresh lock `Ref`. |
| `stubLlm(responses)` | `Ref`-backed queue of JSON strings; `executeStructured` pops and decodes them. Same pattern as golden path. |
| `minimalWorkspace(id, path)` | Constructs a `Workspace` value with `enabled=true`, `RunMode.Host`, `defaultAgent = Some("codex")`. |
| `stubWorkspaceRepo(ws)` | `StubWorkspaceRepository`: returns the single workspace; all run lookups return `Nil`. |
| `stubActivityHub` | `NoOpActivityHub`: `publish` discards events; `subscribe` returns a one-slot queue. |

---

## File 1 — `BoardOrchestratorFailureIntegrationSpec`

Real services: `GitServiceLive`, `BoardRepositoryFS`, `IssueMarkdownParserLive`, `BoardDependencyResolverLive`.
Mocked: `WorkspaceRepository` (stub), `ActivityHub` (no-op).
`BoardOrchestratorLive` is instantiated directly — no background listener forked.

### Test 1 — Run failure compensation and retry

**Goal:** Verify that when `WorkspaceRunService.assign()` fails, the orchestrator compensates the issue back to Todo with a failure reason, and that a subsequent dispatch with a succeeding run service completes the issue successfully.

**Stub:** `StubFailingRunService` — `assign()` always returns `WorkspaceError.WorktreeError("agent failed to start")`.

**Flow:**
1. Create one issue, move to Todo.
2. `dispatchCycle` with `StubFailingRunService`.
3. Assert:
   - `dispatch.skippedIssueIds.size == 1`
   - `dispatch.dispatchedIssueIds.isEmpty`
   - Issue is in Todo (moved back by `dispatchCompensation`)
   - `issue.frontmatter.failureReason.isDefined`
4. Swap in a `StubSucceedingRunService` (creates a real feature branch).
5. `dispatchCycle` again → issue dispatched.
6. `completeIssue(success = true, ...)`.
7. Assert: issue is in Done with `completedAt` set.

---

### Test 2 — Merge conflict recovery

**Goal:** Verify that when `git merge --no-ff` produces a conflict, the error surfaces as `BoardError.GitOperationFailed`, the issue is not moved to Done, and calling `completeIssue(success=false)` recovers the issue to Backlog with `TransientState.Rework`.

**Stub:** `StubConflictingRunService` — `assign()` creates a feature branch that modifies `HelloWorld.scala` (e.g. appends `// feature line`), then checks out main. After `assign()` returns, the test makes a conflicting commit directly on main that modifies the same lines.

**Flow:**
1. Create one issue, move to Todo.
2. `dispatchCycle` → issue dispatched, `branchName` set on issue frontmatter.
3. Test makes a conflicting commit on main modifying the same file.
4. Call `completeIssue(success = true, "done")` — expect `BoardError.GitOperationFailed`.
5. Abort partial merge: `git merge --abort` via `gitRun`.
6. Assert: issue is still in InProgress, not in Done.
7. Call `completeIssue(success = false, "merge conflict - needs rework")`.
8. Assert:
   - Issue is in Backlog.
   - `transientState == TransientState.Rework(...)`.
   - `failureReason.isDefined`.
   - `completedAt.isEmpty`.

---

## File 2 — `DispatchConcurrencyIntegrationSpec`

Real services: `GitServiceLive`, `BoardRepositoryFS`, `IssueMarkdownParserLive`, `BoardDependencyResolverLive`.
Mocked: `WorkspaceRepository` (stub), `ActivityHub` (no-op).
`BoardOrchestratorLive` instantiated directly.

**Shared stub — `PooledStubRunService`:**
Constructor takes `repoPath: Path` and `sem: Semaphore`.
- `assign()`: calls `sem.tryAcquire` — if `false`, returns `WorkspaceError.WorktreeError("agent pool exhausted")`; if `true`, creates a real feature branch and returns a `WorkspaceRun`.
- `cleanupAfterSuccessfulMerge()`: calls `sem.release`.

This enforces observable pool capacity without any real agent execution.

---

### Test 1 — Pool exhaustion: skip-and-retry across sequential cycles

**Goal:** Verify that with pool capacity 1 and three ready issues, the orchestrator dispatches one issue per cycle, skipping the others while the pool is full, and correctly dispatches all three across sequential cycles.

**Setup:** Issues A, B, C (no dependencies), all in Todo. `Semaphore.make(1)`.

**Flow:**
1. **Cycle 1:** A dispatched (permit acquired), B and C skipped. Assert `dispatched=[A]`, `skipped=[B,C]`.
2. **Cycle 2** (pool still full — A not yet completed): A is InProgress (not in `readyToDispatch`), B and C skipped. Assert `dispatched=[]`, `skipped=[B,C]`.
3. Complete A → `cleanupAfterSuccessfulMerge` releases semaphore → A moves to Done.
4. **Cycle 3:** B dispatched, C skipped. Assert `dispatched=[B]`.
5. Complete B → semaphore released.
6. **Cycle 4:** C dispatched. Assert `dispatched=[C]`.
7. Complete C.
8. Final assert: A, B, C all in Done; git log has three `[board] Merge issue` entries; Todo and InProgress columns empty.

---

### Test 2 — Concurrent dispatch cycles do not double-dispatch the same issue

**Goal:** Verify that two `dispatchCycle` calls fired in parallel do not dispatch the same issue twice. The `BoardRepositoryFS` workspace lock serialises `moveIssue` calls, so the second concurrent attempt to move an already-InProgress issue fails with `ConcurrencyConflict` and is absorbed into `skippedIssueIds`.

**Setup:** Issues X and Y (no dependencies), both in Todo. `Semaphore.make(2)` (enough capacity for both). `assign()` includes a `ZIO.sleep(20.millis)` to maximise the concurrency window before the branch is created.

**Flow:**
1. Fire two `dispatchCycle` calls in parallel via `ZIO.zipPar` → `(result1, result2)`.
2. Collect `allDispatched = result1.dispatchedIssueIds ++ result2.dispatchedIssueIds`.
3. Assert:
   - `allDispatched.distinct.size == 2` — each issue dispatched exactly once.
   - No issue ID appears in both `result1.dispatchedIssueIds` and `result2.dispatchedIssueIds`.
   - Board has exactly 2 InProgress issues, 0 in Todo.

---

## File 3 — `AnalysisPipelineIntegrationSpec`

Real services: `GitServiceLive` (via `gitRunner`), `FileService.live`.
`AnalysisAgentRunnerLive` instantiated directly with constructor overrides.

**Constructor wiring:**

| Parameter | Value |
|---|---|
| `workspaceRepository` | `StubWorkspaceRepository` (one enabled workspace) |
| `agentRepository` | `StubAgentRepository` — `list()` returns `Nil`, `findByName` returns `None` → built-in agent fallback |
| `analysisRepository` | `StubAnalysisRepository` — in-memory `Ref[List[AnalysisEvent]]` |
| `taskRepository` | `StubTaskRepository` — `getSetting` returns `None` (no overrides) |
| `fileService` | `FileService.live` |
| `llmPromptExecutor` | `Some((_, agent, _) => ZIO.succeed(s"# Code Review\n\nMocked analysis by ${agent.name}."))` |
| `promptLoader` | `None` |
| `gitRunner` | Default (`CliAgentRunner.runProcess`) — runs real git in the temp repo |

---

### Test 1 — Code review: file written, committed, doc persisted

**Goal:** Verify the full analysis pipeline from `runCodeReview` through file I/O, git commit, and event persistence — with the LLM execution stubbed at the executor level.

**Flow:**
1. `runner.runCodeReview(workspaceId)`.
2. Built-in analysis agent selected; stub executor returns mocked markdown.
3. `writeAnalysisFile` writes `.llm4zio/analysis/code-review.md`.
4. `commitAnalysisFile` runs real git add + commit.
5. `persistAnalysisDoc` appends `AnalysisEvent` to `StubAnalysisRepository`.

**Assert:**
- `.llm4zio/analysis/code-review.md` exists on disk.
- File content contains `"# Code Review"`.
- `StubAnalysisRepository.events` has at least one event appended.
- Returned `AnalysisDoc.workspaceId == workspaceId`.
- Returned `AnalysisDoc.analysisType == AnalysisType.CodeReview`.
- Git log contains a commit referencing the analysis file.

---

## Stub Inventory

| Stub | Used in |
|---|---|
| `StubWorkspaceRepository` | All three specs (via `IntegrationFixtures`) |
| `NoOpActivityHub` | Failure + Concurrency specs |
| `StubFailingRunService` | Failure spec test 1 |
| `StubSucceedingRunService` | Failure spec test 1 (reuse golden-path pattern) |
| `StubConflictingRunService` | Failure spec test 2 |
| `PooledStubRunService(repoPath, sem)` | Concurrency spec tests 1 and 2 |
| `StubAgentRepository` | Analysis spec |
| `StubAnalysisRepository` | Analysis spec |
| `StubTaskRepository` | Analysis spec |

---

## Where Tests Live

```
src/it/scala/integration/
  IntegrationFixtures.scala                     ← shared helpers
  WorkspaceGoldenPathIntegrationSpec.scala      ← existing
  BoardOrchestratorFailureIntegrationSpec.scala ← new
  DispatchConcurrencyIntegrationSpec.scala      ← new
  AnalysisPipelineIntegrationSpec.scala         ← new
```

Run all integration tests with: `sbt it:test`
Run a single spec with: `sbt "It/testOnly integration.<SpecName>"`

---

## Constraints

- No real AI provider calls — all LLM execution is stubbed at the executor or `LlmService` level.
- All file and git operations target a real temporary directory that is deleted on test scope exit.
- Tests are fully re-runnable and leave no side effects.
- No shared mutable state between tests — each test creates its own temp repo, `Ref`s, and semaphores.
