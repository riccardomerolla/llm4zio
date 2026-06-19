# In-process auto-resume + idempotent createPr — design

**Date:** 2026-06-19
**Status:** Approved (brainstorming → spec)
**Sub-project:** C of 4. Depends on A (Flow Trace recorder + flaky-stream retry split, merged v3.7.0).

---

## Problem

The original failure — a flow dying on an intermittent Gemini "Invalid stream:
empty response" — is now mostly self-healing: sub-project A split a flaky-stream
retry class with 6 in-run retries (`LLM4ZIO_FLAKY_RETRIES`), each spawning a fresh
`gemini` process. But two residual gaps remain against the "never manually relaunch"
goal:

1. **No outer safety net.** If a flake exhausts all 6 in-run retries in a single
   run (or a transient error slips past stream-level retry), the whole flow dies and
   the user must manually relaunch the script — even though relaunching almost always
   works (PlanStore resumes, a fresh process succeeds).
2. **`gh.createPr` is not idempotent.** It has no "already exists" handling
   (unlike `GitTool.checkoutOrCreate` / `commitAll`). A flow that fails *after*
   creating the PR but before finishing will, on any re-run, call `gh pr create`
   again and error with "a pull request already exists" — so a full pipeline is not
   safely re-runnable past PR creation.

## Goal

Add **in-process auto-resume**: on a transient/flaky failure that survives in-run
retry, automatically re-enter the whole flow body (re-reading the resumable plan,
skipping completed tasks) — exactly what a manual relaunch does, without the human.
And make **`gh.createPr` find-or-create**, closing the one step that breaks full
re-runs, so auto-resume is safe end-to-end.

## Non-goals

- Changing `implementTaskLoop` / `PlanStore` — the task-level idempotency backbone
  already works (completed tasks are skipped on re-entry).
- Persisting the PR URL in the plan — querying `gh` for the existing PR is enough.
- Auto-resuming non-transient failures (config/parse/compile/abort) — those must
  fail fast, never loop.
- Deterministic replay (sub-project D).

---

## Background: the precedent

`withUsageLimitRetry(policy, maxReentries = 3)(flow)` already wraps the entire flow
body and, on a `FlowError.Llm(_, Some(UsageLimitError))`, sleeps and **re-enters the
same flow** — relying on PlanStore + `implementTaskLoop` to skip completed tasks.
Auto-resume is the same shape, triggered by transient/flaky errors instead of usage
limits, and layered outside it so the two compose.

`implementTaskLoop` persists the plan (`PlanStore.save`) after each completed task
and skips `task.completed` tasks, so re-entering the body re-runs only unfinished
work. The surrounding stages are already re-run-safe: `GitTool.checkoutOrCreate`
(`CreateBranch.AlreadyExists`), `commitAll` (`Commit.NothingToCommit`), and an
idempotent `push`. The sole exception is `gh.createPr`.

---

## Architecture & components

| Unit | Module | One job |
|---|---|---|
| `AutoResume.shouldResume(e: FlowError): Boolean` | `llm4zio.flow` | Classify resumable failures: true only for `FlowError.Llm(_, Some(cause))` where `TransientRetry.isTransient(cause)` or `TransientRetry.isFlakyStream(cause)`. |
| `AutoResume.withAutoResume[R, A](maxReentries: Int)(flow)(using FlowEvents)` | `llm4zio.flow` | The re-entry loop (mirrors `withUsageLimitRetry`): on a resumable failure with budget remaining, publish an `Info`, brief backoff, re-run `flow`; otherwise fail through. |
| `GhTool.createPr` (modify) + `GhTool.prViewArgs` | `llm4zio.flow` | Find-or-create: query the current branch's PR via `gh pr view --json …`; if one exists, return it (and publish/log a "reusing existing PR" notice); else create as today. Return type stays `PullRequest`. |
| `AutoResumeEnv` | `llm4zio.runner` | Parse `LLM4ZIO_AUTO_RESUME` → re-entry budget; unset/blank/invalid → default **2**; `0` disables. |
| Wiring (modify) | `llm4zio.runner` (`Llm4zio.run`) | Wrap `withAutoResume(budget)(withUsageLimitRetry(policy)(body…))`. |

### `withAutoResume` (the loop)

```scala
def withAutoResume[R, A](
  maxReentries: Int,
)(
  flow: ZIO[R, FlowError, A]
)(using events: FlowEvents
): ZIO[R, FlowError, A] =
  def loop(attempt: Int): ZIO[R, FlowError, A] =
    flow.catchSome {
      case e if AutoResume.shouldResume(e) && attempt < maxReentries =>
        events.publish(FlowEvent.Info(
          s"↻ auto-resume after transient failure — re-entering from plan, attempt ${attempt + 1}/$maxReentries: ${e.message}"
        )) *> ZIO.sleep(AutoResume.backoff) *> loop(attempt + 1)
    }
  loop(0)
```

- `backoff` is a short fixed delay (e.g. `2.seconds`) — re-reading the plan is cheap;
  the pause just avoids a tight loop and lets transient infra settle.
- `maxReentries = 0` makes `loop(0)` run `flow` once with no `catchSome` re-entry
  (the guard `attempt < 0` is false) — i.e. disabled. Verify this in a test.

### Layering in `Llm4zio.run`

Today: `withUsageLimitRetry(policy)(body(ctx).mapError {…})`. New:

```scala
withAutoResume(autoResumeBudget)(
  withUsageLimitRetry(policy)(
    body(ctx).mapError { case fe: FlowError => fe; case other => FlowError.Llm(other.toString) }
  )
)
```

A usage-limit error is caught by the inner `withUsageLimitRetry`; a transient/flaky
exhaustion bubbles past it and is caught by the outer `withAutoResume`. Both re-enter
the same body. The `.onExit` reporting and `.ensuring(tracker.summary…)` stay
outermost, unchanged.

### Idempotent `createPr` (find-or-create)

`gh pr view` with no positional argument operates on the **current branch's** PR: it
prints the PR JSON if one exists and exits non-zero ("no pull requests found for
branch …") otherwise. So:

```
createPr(title, body, base, draft):
  run `gh pr view --json url` (Proc.run, inspect exit)
    → success + parseable URL  ⇒ log "reusing existing PR", return that PullRequest
    → otherwise                ⇒ `gh pr create …` exactly as today
```

`prViewArgs` is a pure arg-builder (`List("pr", "view", "--json", "url")`), unit-tested
like the existing `prCreateArgs`. The live `gh` orchestration is not unit-tested (gh
needs network/auth — consistent with the repo's no-network test rule).

---

## Trace integration (A)

Auto-resume re-entries and the "reusing existing PR" notice are `FlowEvent.Info`
events, already captured by the flight recorder via the hub subscriber. No extra
trace wiring; each re-entry is visible in `.llm4zio/trace-<runId>.jsonl`.

---

## Error handling

`withAutoResume` only adds recovery: a non-resumable error or an exhausted budget
falls straight through via `catchSome` (exactly like `withUsageLimitRetry`), so the
existing ✖ banner / exit path is unchanged. `shouldResume` returns false for
`Persistence`, `PlanParse`, `Aborted`, `Process`, and non-transient `Llm` — these
fail fast and never loop. `createPr`'s find path uses `Proc.run` (inspect exit)
rather than `runOrFail`, so a missing PR is a normal branch, not a failure.

---

## Testing (TDD)

1. `AutoResume.shouldResume` matrix: `FlowError.Llm` with a transient cause → true;
   with a flaky-stream cause → true; with a non-transient cause → false;
   `FlowError.Process`/`Persistence`/`PlanParse`/`Aborted` → false.
2. `withAutoResume` re-enters: a `Ref`-counted flow that fails K times with a
   transient `FlowError.Llm` then succeeds — succeeds when K ≤ budget, fails when
   K > budget; a `FlowError.Process` is **not** retried (fails immediately, count
   == 1); `maxReentries = 0` runs the flow exactly once.
3. `GhTool.prViewArgs` builds `["pr", "view", "--json", "url"]`; the existing
   `PullRequest.fromUrl` parsing covers the find path's URL extraction.
4. `AutoResumeEnv.parse`: unset/blank/invalid → 2; `0` → 0; `5` → 5.

---

## Component isolation check

- `AutoResume` — *what:* classify + re-enter; *depends on:* `FlowError`,
  `TransientRetry` predicates, `FlowEvents`.
- `GhTool.createPr` change — *what:* find-or-create; *depends on:* `Proc`, the
  existing arg-builders/parsers.
- `AutoResumeEnv` — *what:* env parse; *depends on:* nothing.
- Wiring — *what:* compose the two retry wrappers; *depends on:* all the above.
