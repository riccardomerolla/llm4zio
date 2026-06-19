# In-Process Auto-Resume + Idempotent createPr Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically re-enter the whole flow body on a transient/flaky failure that survived in-run retry (mimicking a manual relaunch via PlanStore), and make `gh.createPr` find-or-create so a full pipeline is safely re-runnable.

**Architecture:** `withAutoResume` is a near-clone of the proven `withUsageLimitRetry` loop — catch a resumable `FlowError` on the whole body, brief backoff, re-enter — layered *outside* usage-limit retry. `gh.createPr` first checks the current branch's PR (`gh pr view`) and returns it if present. On by default with a small budget via `LLM4ZIO_AUTO_RESUME`.

**Tech Stack:** Scala 3, ZIO 2.1.25, zio-process, ZIO Test. sbt 2.x.

## Global Constraints

- **Auto-resume triggers ONLY on transient/flaky LLM failures** — `FlowError.Llm(_, Some(cause))` where `TransientRetry.isTransient(cause)` or `TransientRetry.isFlakyStream(cause)`. Never `Process`, `Persistence`, `PlanParse`, `Aborted`, or non-transient `Llm` (those fail fast).
- **Re-entry leans on existing idempotency** — `implementTaskLoop` skips completed tasks; git stages are already re-run-safe. Do NOT change `implementTaskLoop`/`PlanStore`.
- **`withAutoResume` only adds recovery** — `catchSome`, like `withUsageLimitRetry`; a non-resumable error or exhausted budget falls straight through. No new error channel.
- **`createPr` stays returning `PullRequest`** (transparent find-or-create) so example scripts are unchanged.
- **On by default**, budget default **2**, `LLM4ZIO_AUTO_RESUME=0` disables.
- **Scala 3 + ZIO 2.1.25.** `-Werror` / `-Wunused:all` — unused/duplicate imports fatal. No `var`.
- **NB (CLAUDE.md):** a wildcard `import zio.*` brings `zio.Task`, shadowing `flow.Task` in *type* position. `UsageLimitRetry.scala`/`AutoResume.scala` use `import zio.*` and name no `Task` type — match the existing per-file import style; don't introduce `zio.*` into a file that references `flow.Task`.
- **Live `gh`/network is NOT unit-tested** (gh needs auth/network; repo rule is no-network tests). Test pure arg-builders + decision logic only.
- **Build:** `sbt llm4zioFlow/test`, `sbt llm4zioRunner/test`, `sbt 'llm4zioFlow/testOnly llm4zio.flow.FooSpec'`. sbt 2 `test` is incremental — `testFull` forces all. `sbt fmt` before committing; `sbt check` is the lint gate (scalafmt + scalafix import order; it **writes** fixes, so re-stage after running it).

---

## File Structure

**Create:**
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/AutoResume.scala` — `shouldResume` + `withAutoResume` (Task 1).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/AutoResumeSpec.scala` (Task 1).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/AutoResumeEnv.scala` (Task 3).
- `modules/llm4zio-runner/src/test/scala/llm4zio/runner/AutoResumeEnvSpec.scala` (Task 3).

**Modify:**
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/GhTool.scala` — `createPr` find-or-create + `prViewArgs` (Task 2).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/GhToolSpec.scala` — `prViewArgs` case (Task 2; if the spec file has a different name, add to the existing GhTool test file).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala` — wrap `withAutoResume` (Task 4).

---

### Task 1: `AutoResume` — classify + re-enter

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/AutoResume.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/AutoResumeSpec.scala`

**Interfaces:**
- Consumes: `FlowError`, `FlowEvent`/`FlowEvents`, `TransientRetry.isTransient`/`isFlakyStream`, `llm4zio.core.LlmError`.
- Produces:
  - `AutoResume.shouldResume(e: FlowError): Boolean`
  - `AutoResume.withAutoResume[R, A](maxReentries: Int, backoff: Duration = 2.seconds)(flow: ZIO[R, FlowError, A])(using events: FlowEvents): ZIO[R, FlowError, A]`

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.LlmError

object AutoResumeSpec extends ZIOSpecDefault:

  private val transientLlm = FlowError.Llm("boom", Some(LlmError.ProviderError("connection reset", None)))
  private val flakyLlm     = FlowError.Llm("boom", Some(LlmError.ProviderError("Invalid stream: empty response", None)))
  private val fatalLlm     = FlowError.Llm("nope", Some(LlmError.InvalidRequestError("bad prompt")))

  // A flow that fails `failTimes` times with `err`, then succeeds; counts attempts.
  private def counting(ref: Ref[Int], err: FlowError, failTimes: Int): IO[FlowError, String] =
    ref.updateAndGet(_ + 1).flatMap(n => if n <= failTimes then ZIO.fail(err) else ZIO.succeed("ok"))

  def spec = suite("AutoResume")(
    test("shouldResume: transient/flaky Llm yes; fatal Llm and other FlowErrors no") {
      assertTrue(
        AutoResume.shouldResume(transientLlm),
        AutoResume.shouldResume(flakyLlm),
        !AutoResume.shouldResume(fatalLlm),
        !AutoResume.shouldResume(FlowError.Process("git", "x")),
        !AutoResume.shouldResume(FlowError.Persistence("io")),
        !AutoResume.shouldResume(FlowError.PlanParse("bad")),
        !AutoResume.shouldResume(FlowError.Aborted("stop")),
      )
    },
    test("re-enters a transient failure within budget, then succeeds") {
      given FlowEvents = FlowEvents.noop
      for
        ref <- Ref.make(0)
        out <- AutoResume.withAutoResume(2, backoff = Duration.Zero)(counting(ref, transientLlm, failTimes = 2))
        n   <- ref.get
      yield assertTrue(out == "ok", n == 3) // 2 failures + 1 success
    },
    test("fails once the budget is exhausted") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <- AutoResume.withAutoResume(2, backoff = Duration.Zero)(counting(ref, transientLlm, failTimes = 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 3) // initial + 2 re-entries
    },
    test("does not re-enter a non-resumable error") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <- AutoResume.withAutoResume(3, backoff = Duration.Zero)(counting(ref, FlowError.Process("git", "x"), 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 1) // failed immediately, no re-entry
    },
    test("maxReentries = 0 runs the flow exactly once") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <- AutoResume.withAutoResume(0, backoff = Duration.Zero)(counting(ref, transientLlm, 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 1)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.AutoResumeSpec'`
Expected: FAIL — `AutoResume` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import zio.*

import llm4zio.core.LlmError

/** Flow-level backstop for flaky/transient provider failures that survive in-run retry: re-enter the whole flow body
  * (re-reading the resumable plan, skipping completed tasks via `implementTaskLoop`) — exactly what a manual relaunch
  * does. Sibling of [[withUsageLimitRetry]], which handles usage caps; layered outside it so the two compose.
  */
object AutoResume:
  /** Default pause between re-entries. Re-reading the plan is cheap; this just avoids a tight loop. */
  val defaultBackoff: Duration = 2.seconds

  /** Resumable = a transient or flaky-stream LLM failure that survived in-run retry. Everything else (process, parse,
    * persistence, abort, non-transient LLM) fails fast.
    */
  def shouldResume(e: FlowError): Boolean = e match
    case FlowError.Llm(_, Some(cause)) => TransientRetry.isTransient(cause) || TransientRetry.isFlakyStream(cause)
    case _                             => false

  def withAutoResume[R, A](
    maxReentries: Int,
    backoff: Duration = defaultBackoff,
  )(
    flow: ZIO[R, FlowError, A]
  )(using events: FlowEvents
  ): ZIO[R, FlowError, A] =
    def loop(attempt: Int): ZIO[R, FlowError, A] =
      flow.catchSome {
        case e if shouldResume(e) && attempt < maxReentries =>
          events.publish(
            FlowEvent.Info(
              s"↻ auto-resume after transient failure — re-entering from plan, attempt ${attempt + 1}/$maxReentries: ${e.message}"
            )
          ) *> ZIO.sleep(backoff) *> loop(attempt + 1)
      }
    loop(0)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.AutoResumeSpec'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/AutoResume.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/AutoResumeSpec.scala
git commit -m "feat(flow): AutoResume — re-enter flow body on transient/flaky failure"
```

---

### Task 2: `GhTool.createPr` find-or-create

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/GhTool.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/GhToolSpec.scala` (use whatever the existing GhTool test file is named — search for `prCreateArgs` to find it)

**Interfaces:**
- Consumes: `Proc.run`/`Proc.runOrFail`, `PullRequest.fromUrl` (existing).
- Produces:
  - `GhTool.prViewArgs: List[String]` — pure arg-builder for `gh pr view` (current branch).
  - `GhTool#createPr(...)` — unchanged signature, now find-or-create.

Current `createPr` (GhTool.scala:32–42) calls `Proc.runOrFail("gh", prCreateArgs(...), workDir)` and parses the URL. `Proc.run("gh", args, workDir)` returns a result with `.ok`, `.stdout`, `.stderr`, `.exitCode` (see `prChecks` at line 69 and `GitTool.exec`). `Proc.runOrFail` fails on non-zero exit.

- [ ] **Step 1: Write the failing test (add to the GhTool spec)**

```scala
    ,
    test("prViewArgs queries the current branch's PR url") {
      assertTrue(GhTool.prViewArgs == List("pr", "view", "--json", "url", "--jq", ".url"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.GhToolSpec'` (adjust the spec name if different)
Expected: FAIL — `prViewArgs` not found.

- [ ] **Step 3: Write minimal implementation (edit `GhTool.scala`)**

Add the pure arg-builder to `object GhTool` (next to `prCreateArgs`, ~line 89):

```scala
  /** `gh` argv to read the current branch's PR URL (empty output / non-zero exit when there is none). Pure. */
  val prViewArgs: List[String] = List("pr", "view", "--json", "url", "--jq", ".url")
```

Replace the `createPr` method (lines 31–42) with a find-or-create version:

```scala
  /** Open a pull request, or return the existing open PR for the current branch if one is already there. Find-or-create
    * makes the step idempotent, so a re-run (e.g. auto-resume) past PR creation does not fail with "a pull request
    * already exists".
    */
  def createPr(
    title: String,
    body: String,
    base: Option[String] = None,
    draft: Boolean = false,
  ): IO[FlowError, PullRequest] =
    findOpenPr.flatMap {
      case Some(existing) =>
        ZIO.logInfo(s"reusing existing PR ${existing.shortRef}").as(existing)
      case None           =>
        Proc.runOrFail("gh", GhTool.prCreateArgs(title, body, base, draft), workDir).flatMap { out =>
          ZIO
            .fromOption(out.linesIterator.flatMap(PullRequest.fromUrl).nextOption())
            .orElseFail(FlowError.Process("gh pr create", s"could not parse a PR URL from: $out"))
        }
    }

  /** The open PR for the current branch, if any (`gh pr view` exits non-zero when there is none). */
  private def findOpenPr: IO[FlowError, Option[PullRequest]] =
    Proc.run("gh", GhTool.prViewArgs, workDir).map { r =>
      if r.ok then r.stdout.linesIterator.flatMap(PullRequest.fromUrl).nextOption() else None
    }
```

(If `Proc.run`'s result field is named differently than `.ok`/`.stdout`, match the names used by `GitTool.exec`/`prChecks` in this codebase — check before writing.)

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.GhToolSpec'`
Expected: PASS (existing GhTool cases + the new `prViewArgs` one).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/GhTool.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/GhToolSpec.scala
git commit -m "feat(flow): gh.createPr is find-or-create (idempotent for re-runs)"
```

---

### Task 3: `AutoResumeEnv`

**Files:**
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/AutoResumeEnv.scala`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/AutoResumeEnvSpec.scala`

**Interfaces:**
- Produces: `AutoResumeEnv.default: Int` (2); `AutoResumeEnv.parse(value: Option[String]): Int`.

Mirror `FlakyRetryEnv`/`RetryEnv`/`TraceKeepEnv` in the same package — read one first.

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.runner

import zio.test.*

object AutoResumeEnvSpec extends ZIOSpecDefault:
  def spec = suite("AutoResumeEnv")(
    test("unset / blank / invalid / negative → default (2)") {
      assertTrue(
        AutoResumeEnv.parse(None) == 2,
        AutoResumeEnv.parse(Some("  ")) == 2,
        AutoResumeEnv.parse(Some("nope")) == 2,
        AutoResumeEnv.parse(Some("-1")) == 2,
      )
    },
    test("a valid non-negative int is used (0 disables)") {
      assertTrue(AutoResumeEnv.parse(Some("0")) == 0, AutoResumeEnv.parse(Some("5")) == 5)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.AutoResumeEnvSpec'`
Expected: FAIL — `AutoResumeEnv` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.runner

/** Parse `LLM4ZIO_AUTO_RESUME` into the in-process auto-resume budget for [[llm4zio.flow.AutoResume.withAutoResume]]:
  * how many times the whole flow body is re-entered after a transient/flaky failure that survived in-run retry (each
  * re-entry resumes from the persisted plan). Unset/blank/invalid → [[default]] (2); `0` → disabled; `<n>` (n ≥ 0).
  */
object AutoResumeEnv:
  val default: Int = 2

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.AutoResumeEnvSpec'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/AutoResumeEnv.scala \
        modules/llm4zio-runner/src/test/scala/llm4zio/runner/AutoResumeEnvSpec.scala
git commit -m "feat(runner): AutoResumeEnv — LLM4ZIO_AUTO_RESUME budget (default 2)"
```

---

### Task 4: Wire `withAutoResume` into `Llm4zio.run`

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`

**Interfaces:**
- Consumes: `AutoResume.withAutoResume` (Task 1), `AutoResumeEnv.parse` (Task 3).

This task is verified by the runner module compiling + existing tests passing (consistent with the A/B wiring tasks; behavior is covered by Tasks 1–3).

- [ ] **Step 1: Parse the budget**

In `Llm4zio.run`, next to the other env parses (`retries`/`flakyRetries`/`traceKeep`/`level`):

```scala
                       autoResume   = AutoResumeEnv.parse(sys.env.get("LLM4ZIO_AUTO_RESUME"))
```

- [ ] **Step 2: Wrap `withAutoResume` outside `withUsageLimitRetry`**

The current block is:

```scala
                         withUsageLimitRetry(policy)(
                           body(ctx).mapError {
                             case fe: FlowError => fe
                             case other         => FlowError.Llm(other.toString)
                           }
                         ).unit
```

Change it to:

```scala
                         withAutoResume(autoResume)(
                           withUsageLimitRetry(policy)(
                             body(ctx).mapError {
                               case fe: FlowError => fe
                               case other         => FlowError.Llm(other.toString)
                             }
                           )
                         ).unit
```

Leave the `.onExit { … }` and `.ensuring(tracker.summary …)` chained after `.unit` exactly as they are (reporting stays outermost). `given FlowEvents = hub` is already in scope above this block, so both wrappers see the event sink. `withAutoResume` resolves via the existing `import llm4zio.flow.*`.

- [ ] **Step 3: Compile + run the suites**

```bash
sbt llm4zioFlow/test
sbt llm4zioRunner/test
```
Expected: both compile and pass (no regressions; the body is now wrapped by two composing retry layers).

- [ ] **Step 4: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala
git commit -m "feat(runner): wire withAutoResume (LLM4ZIO_AUTO_RESUME) around the flow body"
```

---

### Task 5: Full-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite, all modules**

Run: `sbt "; llm4zioCore/testFull; llm4zioFlow/testFull; llm4zioRunner/testFull"`
Expected: all PASS, 0 failures.

- [ ] **Step 2: Format + lint gate**

Run: `sbt check`
Expected: clean. NB `check` *applies* scalafix fixes (writes files); if it changes anything, run `sbt fmt`, re-stage, and commit the fixup before re-running `sbt check`.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "chore: scalafmt + verification fixups for auto-resume" || echo "nothing to commit"
```

---

## Self-Review notes

- **Spec coverage:** `shouldResume` + `withAutoResume` (Task 1), find-or-create `createPr` + `prViewArgs` (Task 2), `AutoResumeEnv` (Task 3), wiring outside usage-limit retry (Task 4), full verify (Task 5). `implementTaskLoop`/PlanStore untouched (no task — they already work). Trace integration is automatic (Info events via the hub — no task).
- **Type consistency:** `AutoResume.shouldResume(FlowError): Boolean`, `withAutoResume(maxReentries: Int, backoff: Duration)(flow)(using FlowEvents)`, `GhTool.prViewArgs: List[String]`, `createPr` returns `PullRequest`, `AutoResumeEnv.parse(Option[String]): Int` — consistent across tasks.
- **Deviation from spec:** `withAutoResume` takes a `backoff` parameter (default `2.seconds`) rather than a bare `AutoResume.backoff` constant, so tests can pass `Duration.Zero` and not stall on `ZIO.sleep` under the test clock. Behavior in production is identical (default backoff).
- **No placeholders:** every step shows full code; commands have expected output.
