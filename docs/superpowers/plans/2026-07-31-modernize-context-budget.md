# Bounded Context for the Modernization Flows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every LLM call in the modernization pipeline carries a bounded, predictable amount of context, so no phase dies on Gemini's 1M input-token limit; oversized inputs shrink and complete with the truncation recorded rather than failing.

**Architecture:** A new `Context` budget primitive in `llm4zio-flow` (cap + shrink ladder + fiber-local truncation recording) becomes the backstop. The real fix is decomposition: per-program judging in Implement and Review, per-program triage in Verify, path-scoped diffs per reviewer lens, a fresh `Chat` per task, and a deterministically pre-resolved include closure for the Extract analyst. A guard in `TransientRetry` stops a deterministic 400 being retried three times.

**Tech Stack:** Scala 3.8.3, ZIO 2.x, zio-json, zio-test, sbt 2.x.

**Spec:** [`docs/superpowers/specs/2026-07-31-modernize-context-budget-design.md`](../specs/2026-07-31-modernize-context-budget-design.md)

## Global Constraints

- **ZIO-native.** No `Future`. Blocking work goes in `ZIO.attemptBlocking`. Subprocesses go through `flow.Proc` (zio-process), never raw `ProcessBuilder`.
- **Typed errors.** Core uses `LlmError`; flow uses `FlowError` (`Persistence`, `PlanParse`, `Aborted`, `Process`, `Llm`). No `Throwable` in signatures.
- **No `var`.** Use `Ref` / `FiberRef` / `Queue` / `Hub`.
- **`-Werror` / `-Wunused:all`** — an unused import fails the build. NB: a wildcard `import zio.*` brings `zio.Task`, which shadows `flow.Task` in *type* position. In any file naming `flow.Task`, import `zio.ZIO` and specific names instead.
- **Dependency direction:** `modernize → runner → flow → core`. Never the reverse. `Context` lives in `flow` and therefore **cannot** use `modernize.Env`.
- **Formatting:** run `sbt fmt` before every commit; `sbt check` verifies.
- **Testing:** `sbt test` is incremental in sbt 2. Use `sbt testFull` (what CI runs) before declaring a task done. Integration tests live in `src/it/scala` and use a temp repo + local bare remote (no network).
- **Budgets are in characters, not tokens.** Default 400_000 (~115k tokens at ~3.5 chars/token for code).
- **Target version:** v4.3.0.

---

### Task 1: `Context.cap` and the budget knob

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `Context.Capped(text: String, originalChars: Int, truncated: Boolean)`, `Context.cap(text: String, limit: Int): Capped`, `Context.budget: Int`.

**Why `budget` reads system properties too:** `modernize.Env` resolves `sys.env` then `sys.props.get("llm4zio.<NAME>")`, which is how `modernize.conf` works. `Context` lives in `flow` and cannot depend on `modernize`, so it replicates that two-step lookup itself. Miss this and `LLM4ZIO_CONTEXT_BUDGET` set in `modernize.conf` is silently ignored.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala`:

```scala
package llm4zio.flow

import zio.test.*

object ContextSpec extends ZIOSpecDefault:

  def spec = suite("Context")(
    test("cap returns text at or under the limit untouched") {
      val short = "abcdef"
      val out   = Context.cap(short, 10)
      assertTrue(
        out.text == short,
        out.originalChars == 6,
        !out.truncated,
      )
    },
    test("cap keeps head and tail with an elision marker, never exceeding the limit") {
      val text = ("h" * 100) + ("t" * 100)
      val out  = Context.cap(text, 40)
      assertTrue(
        out.truncated,
        out.originalChars == 200,
        out.text.startsWith("h"),
        out.text.endsWith("t"),
        out.text.contains("[truncated]"),
        // The marker counts against the limit: room = 40 - 19 = 21, head = 21*3/4 = 15, tail = 6.
        out.text.length == 40,
        out.text.takeWhile(_ == 'h').length == 15,
        out.text.reverse.takeWhile(_ == 't').length == 6,
      )
    },
    test("cap handles a limit smaller than the marker without crashing") {
      val out = Context.cap("x" * 50, 4)
      assertTrue(out.truncated, out.text.nonEmpty)
    },
    test("budget falls back to the 400k default") {
      // No env var set in the test JVM, and no llm4zio.* system property.
      assertTrue(Context.budget == 400_000)
    },
  )
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: FAIL to compile — `Not found: Context`.

- [ ] **Step 3: Write the minimal implementation**

Create `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala`:

```scala
package llm4zio.flow

/** Context budgeting for LLM prompts: bound what a call ships, and make every truncation visible.
  *
  * Budgets are in CHARACTERS, not tokens — deterministic, no tokenizer dependency, and what the flows already used.
  * Rule of thumb ~3.5 chars/token for code, so the 400k default is ~115k tokens: conservative against every provider.
  */
object Context:

  private val Marker = "\n\n… [truncated] …\n\n"

  /** The result of [[cap]]: the (possibly shortened) text plus what it cost. */
  final case class Capped(text: String, originalChars: Int, truncated: Boolean)

  /** Bound `text` to `limit` characters — the result is NEVER longer than `limit`, marker included. Keeps the head
    * (3/4 of the remaining room) and the tail (1/4) so both the entry points and the trailing rules survive; the
    * middle is where boilerplate lives. Text at or under the limit is returned untouched.
    *
    * NB the marker counts against `limit`. `ExtractFlow.capText`, the prior art this generalises, let the marker sit
    * on top — a ~19-char overshoot. That was an accident, not a design choice, and callers here reason about fitting
    * under a hard provider ceiling, so a method called `cap` must actually cap.
    */
  def cap(text: String, limit: Int): Capped =
    if text.length <= limit then Capped(text, text.length, truncated = false)
    else if limit <= Marker.length then Capped(text.take(math.max(limit, 1)), text.length, truncated = true)
    else
      val room = limit - Marker.length
      val head = room * 3 / 4
      val tail = room - head
      Capped(s"${text.take(head)}$Marker${text.takeRight(tail)}", text.length, truncated = true)

  /** The default character budget: `LLM4ZIO_CONTEXT_BUDGET`, else the deprecated `LLM4ZIO_JUDGE_SOURCES_LIMIT`, else
    * 400_000. Both are read from the environment first and then from `llm4zio.<NAME>` system properties, mirroring
    * `modernize.Env` so a `modernize.conf` setting still reaches the flow layer (which cannot depend on modernize).
    */
  def budget: Int =
    def lookup(name: String): Option[String] = sys.env.get(name).orElse(sys.props.get(s"llm4zio.$name"))
    lookup("LLM4ZIO_CONTEXT_BUDGET")
      .orElse(lookup("LLM4ZIO_JUDGE_SOURCES_LIMIT"))
      .flatMap(_.trim.toIntOption)
      .filter(_ > 0)
      .getOrElse(400_000)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala
git commit -m "feat(flow): Context.cap and the LLM4ZIO_CONTEXT_BUDGET knob"
```

---

### Task 2: Stop retrying deterministic 400s

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala` (the `isTransient` object method, ~line 112-141)
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `TransientRetry.isContextOverflow(e: LlmError): Boolean`. `TransientRetry.isTransient` returns `false` for deterministic 4xx.

**Background:** `isTransient` matches the bare substring `"api error"` to catch Gemini's `[API Error: An unknown error occurred.]` glitch. But Gemini wraps *every* error that way, including a 400 `INVALID_ARGUMENT` for exceeding the token limit — which is deterministic and can never succeed on retry. Today that burns 3 retries with exponential backoff and mislabels the failure "transient".

- [ ] **Step 1: Write the failing test**

Append to the existing `spec` suite in `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala`:

```scala
    test("a 400 INVALID_ARGUMENT token-count error is not transient and is a context overflow") {
      val msg = """Gemini CLI returned an error: [API Error: [{
                  |  "error": {
                  |    "code": 400,
                  |    "message": "The input token count exceeds the maximum number of tokens allowed 1048576.",
                  |    "status": "INVALID_ARGUMENT"
                  |  }
                  |}]]""".stripMargin
      val err = LlmError.ProviderError(msg, None)
      assertTrue(
        !TransientRetry.isTransient(err),
        TransientRetry.isContextOverflow(err),
      )
    },
    test("genuine transients are still transient and are not context overflows") {
      val unknown   = LlmError.ProviderError("[API Error: An unknown error occurred.]", None)
      val unavail   = LlmError.ProviderError("503 service unavailable", None)
      val reset     = LlmError.ProviderError("connection reset by peer", None)
      assertTrue(
        TransientRetry.isTransient(unknown),
        TransientRetry.isTransient(unavail),
        TransientRetry.isTransient(reset),
        !TransientRetry.isContextOverflow(unknown),
        !TransientRetry.isContextOverflow(unavail),
      )
    },
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TransientRetrySpec'`
Expected: FAIL to compile — `value isContextOverflow is not a member`.

- [ ] **Step 3: Write the implementation**

In `TransientRetry.scala`, add `isContextOverflow` and a `isDeterministic4xx` guard. Place both immediately above the existing `isTransient`:

```scala
  /** Deterministic client errors that a retry can never fix. Gemini wraps EVERY error in `[API Error: {...}]`,
    * including 400s, so the `"api error"` substring in [[isTransient]] would otherwise swallow them.
    */
  private def isDeterministic4xx(message: String): Boolean =
    val m = message.toLowerCase
    List(
      "invalid_argument",
      "\"code\": 400",
      "\"code\":400",
      "code=400",
      "exceeds the maximum number of tokens",
    ).exists(m.contains)

  /** The prompt was larger than the model's input window. Deterministic: the same prompt always fails, so it is NOT
    * transient — it routes to [[Context.withShrink]], which retries at a smaller budget.
    */
  def isContextOverflow(e: LlmError): Boolean = e match
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List(
        "exceeds the maximum number of tokens",
        "input token count exceeds",
        "context length exceeded",
        "maximum context length",
        "prompt is too long",
        "request too large",
      ).exists(m.contains)
    case _                                  => false
```

Then guard `isTransient`'s `ProviderError` branch. Change:

```scala
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List(
```

to:

```scala
    case LlmError.ProviderError(message, _) if !isDeterministic4xx(message) =>
      val m = message.toLowerCase
      List(
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TransientRetrySpec'`
Expected: PASS — all existing tests plus the two new ones.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala
git commit -m "fix(flow): don't retry deterministic 400s; classify context overflow"
```

---

### Task 3: Fiber-local truncation recording and `Context.capped`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala`
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala`

**Interfaces:**
- Consumes: `Context.cap`, `Context.Capped` (Task 1); `FlowEvents`, `FlowEvent.Info`.
- Produces: `Context.Truncation(label: String, originalChars: Int, keptChars: Int)`, `Context.capped(label: String, text: String, limit: Int)(using FlowEvents): UIO[String]`, `Context.truncations: UIO[Chunk[Truncation]]`, `Context.recordTruncation(t: Truncation): UIO[Unit]`.

`capped` and `withShrink` (Task 4) are the **only** writers to the recorder, so no call site can forget to record a truncation.

- [ ] **Step 1: Write the failing test**

Add to `ContextSpec`'s suite:

```scala
    test("capped publishes an event and records the truncation") {
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        out    <- Context.capped("specs", "x" * 1000, 100)
        seen   <- events.recorded
        recs   <- Context.truncations
      yield assertTrue(
        out.length <= 100,
        seen.exists { case FlowEvent.Info(m) => m.contains("specs") && m.contains("1000"); case _ => false },
        recs.size == 1,
        recs.head.label == "specs",
        recs.head.originalChars == 1000,
        recs.head.keptChars <= 100,
      )
    },
    test("capped records nothing when the text fits") {
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        out    <- Context.capped("specs", "small", 100)
        seen   <- events.recorded
        recs   <- Context.truncations
      yield assertTrue(out == "small", seen.isEmpty, recs.isEmpty)
    },
```

Note: each ZIO test runs on its own fiber, so the `FiberRef` starts empty per test — no cross-test bleed.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: FAIL to compile — `value capped is not a member of object Context`.

- [ ] **Step 3: Write the implementation**

Add to `Context.scala`. The imports at the top become:

```scala
package llm4zio.flow

import zio.{ Chunk, FiberRef, UIO, Unsafe, ZIO }
```

Then append inside `object Context`:

```scala
  /** One recorded truncation: what was shortened, and by how much. */
  final case class Truncation(label: String, originalChars: Int, keptChars: Int):
    def render: String = s"$label: $originalChars → $keptChars chars"

  /** Fiber-local truncation log. Fiber-local (not global) so concurrent flows don't cross-contaminate, and so a
    * phase reads back exactly what its own calls truncated. Written ONLY by [[capped]] and [[withShrink]].
    */
  private val recorded: FiberRef[Chunk[Truncation]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Chunk.empty[Truncation]))

  /** Truncations recorded on this fiber so far. Phases write these into `provenance.json`. */
  def truncations: UIO[Chunk[Truncation]] = recorded.get

  private def record(t: Truncation): UIO[Unit] = recorded.update(_ :+ t)

  /** [[cap]], publishing a [[FlowEvent.Info]] and recording the truncation when one happens. `label` names what was
    * shortened, so the event and the provenance entry are readable ("specs", "branch diff", "judge context").
    */
  def capped(label: String, text: String, limit: Int)(using events: FlowEvents): UIO[String] =
    val out = cap(text, limit)
    if !out.truncated then ZIO.succeed(out.text)
    else
      events.publish(
        FlowEvent.Info(s"⚠ context: $label truncated ${out.originalChars} → ${out.text.length} chars")
      ) *> record(Truncation(label, out.originalChars, out.text.length)).as(out.text)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala
git commit -m "feat(flow): record truncations fiber-locally via Context.capped"
```

---

### Task 4: `Context.withShrink` — the shrink ladder

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala`
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala`

**Interfaces:**
- Consumes: `Context.budget` (Task 1), `Context.record` (Task 3), `TransientRetry.isContextOverflow` (Task 2).
- Produces: `Context.withShrink[A](label: String, start: Int = budget)(f: Int => IO[FlowError, A])(using FlowEvents): IO[FlowError, A]`.

This generalises `ExtractFlow.judgeWithShrink`, which today fires only on empty responses. It must also fire on context overflow — that is the behaviour change that turns the reported crash into a completed call.

- [ ] **Step 1: Write the failing test**

Add to `ContextSpec`'s suite:

```scala
    test("withShrink retries at half budget after a context overflow and records the shrink") {
      val overflow = LlmError.ProviderError(
        """[API Error: {"error":{"code":400,"message":"The input token count exceeds the maximum """ +
          """number of tokens allowed 1048576.","status":"INVALID_ARGUMENT"}}]""",
        None,
      )
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        calls  <- Ref.make(List.empty[Int])
        out    <- Context.withShrink("judge", start = 1000) { cap =>
                    calls.update(_ :+ cap) *>
                      (if cap > 500 then ZIO.fail(FlowError.Llm(overflow.message, Some(overflow)))
                       else ZIO.succeed(s"ok@$cap"))
                  }
        seen   <- calls.get
        recs   <- Context.truncations
      yield assertTrue(
        out == "ok@500",
        seen == List(1000, 500),
        recs.exists(_.label == "judge"),
      )
    },
    test("withShrink retries on an empty response too") {
      val empty = LlmError.ProviderError("Invalid stream: empty response", None)
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        calls  <- Ref.make(0)
        out    <- Context.withShrink("judge", start = 1000) { cap =>
                    calls.updateAndGet(_ + 1).flatMap { n =>
                      if n == 1 then ZIO.fail(FlowError.Llm(empty.message, Some(empty)))
                      else ZIO.succeed(s"ok@$cap")
                    }
                  }
      yield assertTrue(out == "ok@500")
    },
    test("withShrink fails with a budget-naming message once the ladder is exhausted") {
      val overflow = LlmError.ProviderError("input token count exceeds the limit", None)
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        res    <- Context
                    .withShrink("judge", start = 1000)(_ => ZIO.fail(FlowError.Llm(overflow.message, Some(overflow))))
                    .either
      yield assertTrue(
        res.isLeft,
        res.left.exists(_.message.contains("LLM4ZIO_CONTEXT_BUDGET")),
      )
    },
```

The test file needs `import zio.*` and `import llm4zio.core.LlmError` added. Because the file names no `flow.Task`, a wildcard `zio.*` is safe here.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: FAIL to compile — `value withShrink is not a member of object Context`.

- [ ] **Step 3: Write the implementation**

Add to `Context.scala`. Extend the imports to:

```scala
import zio.{ Chunk, FiberRef, IO, UIO, Unsafe, ZIO }
```

Append inside `object Context`:

```scala
  /** True for the two failure classes a smaller prompt can fix: a deterministic context overflow, and the empty
    * response gemini returns when a prompt is too large for it to even start.
    */
  private def shrinkable(e: FlowError): Boolean = e match
    case FlowError.Llm(message, cause) =>
      cause.exists(TransientRetry.isContextOverflow) ||
      message.toLowerCase.contains("empty response") ||
      message.toLowerCase.contains("input token count exceeds") ||
      message.toLowerCase.contains("exceeds the maximum number of tokens")
    case _                             => false

  /** Run `f` at `start` characters; on a shrinkable failure retry at 1/2, then 1/4, then give up. Repeating the same
    * oversized prompt cannot succeed, so shrinking is the only retry that makes sense for this failure class — this is
    * why context overflow is deliberately excluded from [[TransientRetry]]'s budget.
    *
    * Each shrink publishes a [[FlowEvent.Info]] and is recorded like any other truncation.
    */
  def withShrink[A](label: String, start: Int = budget)(
    f: Int => IO[FlowError, A]
  )(using events: FlowEvents
  ): IO[FlowError, A] =
    def attempt(cap: Int, rest: List[Int]): IO[FlowError, A] =
      f(cap).catchSome {
        case e if shrinkable(e) && rest.nonEmpty =>
          events.publish(
            FlowEvent.Info(s"⚠ context: $label did not fit at $cap chars — shrinking to ${rest.head}: ${e.message}")
          ) *> record(Truncation(label, cap, rest.head)) *> attempt(rest.head, rest.tail)
        case e if shrinkable(e)                  =>
          ZIO.fail(FlowError.Llm(
            s"$label exceeded the model's input limit even after shrinking to $cap chars — " +
              s"lower LLM4ZIO_CONTEXT_BUDGET or scope this phase further (cause: ${e.message})",
            None,
          ))
      }
    attempt(start, List(start / 2, start / 4))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextSpec'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Context.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextSpec.scala
git commit -m "feat(flow): Context.withShrink ladder for oversized prompts"
```

---

### Task 5: Path-scoped `GitTool.diffVsBase`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/GitTool.scala` (near the existing `diffVsBase`, line 84)
- Test: `modules/llm4zio-flow/src/it/scala/llm4zio/flow/GitToolSpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `GitTool.diffVsBase(base: String, paths: List[String], threeDot: Boolean = true)(using Caps.GitRead): IO[FlowError, String]`.

Overload, not a replacement — the existing no-paths `diffVsBase` stays and all current callers keep working. An **empty** `paths` list must return the empty string, not the whole diff: `git diff base...HEAD --` with no pathspec means "everything", which would silently defeat every scoping call site downstream.

- [ ] **Step 1: Write the failing test**

Add to the `suite("GitTool")` in `modules/llm4zio-flow/src/it/scala/llm4zio/flow/GitToolSpec.scala`:

```scala
    test("diffVsBase scopes to the given paths, and an empty path list yields nothing") {
      ZIO.scoped {
        for
          dir     <- tempDir
          git     <- newRepo(dir)
          _       <- git.createBranch("feature/scoped")
          _       <- write(dir, "a.txt", "alpha")
          _       <- write(dir, "b.txt", "beta")
          _       <- git.commitAll("add a and b")
          all     <- git.diffVsBase("main")
          onlyA   <- git.diffVsBase("main", List("a.txt"))
          neither <- git.diffVsBase("main", Nil)
        yield assertTrue(
          all.contains("a.txt"),
          all.contains("b.txt"),
          onlyA.contains("a.txt"),
          !onlyA.contains("b.txt"),
          neither.isEmpty,
        )
      }
    },
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/It/testOnly llm4zio.flow.GitToolSpec'`
Expected: FAIL to compile — none of the `diffVsBase` overloads take a `List[String]` second argument.

- [ ] **Step 3: Write the implementation**

In `GitTool.scala`, directly below the existing `diffVsBase`:

```scala
  /** Diff vs `base` restricted to `paths` — the per-program / per-lens scoping primitive. An EMPTY `paths` list
    * returns the empty string rather than the whole diff: bare `git diff <range> --` means "everything", which would
    * silently defeat every caller that scopes by a computed, possibly-empty file set.
    */
  def diffVsBase(base: String, paths: List[String], threeDot: Boolean)(using Caps.GitRead): IO[FlowError, String] =
    if paths.isEmpty then ZIO.succeed("")
    else
      val range = if threeDot then s"$base...HEAD" else s"$base..HEAD"
      read("git diffVsBase (scoped)")(execOrFail(List("diff", range, "--") ++ paths*))

  def diffVsBase(base: String, paths: List[String])(using Caps.GitRead): IO[FlowError, String] =
    diffVsBase(base, paths, threeDot = true)
```

Two explicit overloads rather than a default argument: Scala 3 forbids default arguments on overloaded methods that would make the call ambiguous with the existing `diffVsBase(base, threeDot)`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/It/testOnly llm4zio.flow.GitToolSpec'`
Expected: PASS — all existing GitTool tests plus the new one.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/GitTool.scala modules/llm4zio-flow/src/it/scala/llm4zio/flow/GitToolSpec.scala
git commit -m "feat(flow): path-scoped GitTool.diffVsBase"
```

---

### Task 6: `Pack.programFiles`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Pack.scala` (case class ~line 18-42, `parse` ~line 117-150)
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PackSpec.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `Pack.programFiles: Option[String]` (field) and `Pack.filesFor(program: String): String` (the resolved regex, `<NAME>` substituted).

The default template `.*(?i)<NAME>.*` matches any path containing the program name case-insensitively. A pack whose target code doesn't carry the program name in its path overrides this.

- [ ] **Step 1: Write the failing test**

Add to the suite in `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PackSpec.scala`. The file already
provides `tempDir` (a scoped temp directory) and `write(dir, name, content)` — reuse them; do **not**
add a `parseForTest` hook, since `Pack` has no public `parse`:

```scala
    test("programFiles parses and substitutes <NAME>, with a case-insensitive default") {
      ZIO.scoped {
        for
          dir      <- tempDir
          _        <- write(dir, "with/pack.md", "# Pack: demo\n\nsource: cobol\nprogramFiles: src/main/java/.*<NAME>.*\\.java\n")
          _        <- write(dir, "without/pack.md", "# Pack: demo\n\nsource: cobol\n")
          withField <- Pack.load(dir.resolve("with"))
          noField   <- Pack.load(dir.resolve("without"))
        yield assertTrue(
          withField.filesFor("ACCTXFR") == """src/main/java/.*ACCTXFR.*\.java""",
          noField.filesFor("ACCTXFR") == """.*(?i)\QACCTXFR\E.*""",
          // the default really is case-insensitive
          "src/main/java/Acctxfr.java".matches(noField.filesFor("ACCTXFR")),
        )
      }
    },
```

Note the expected default string: `filesFor` wraps the program name in `Pattern.quote`, which produces
`\QACCTXFR\E`. Assert the quoted form, not the bare name.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.PackSpec'`
Expected: FAIL to compile — `value filesFor is not a member of Pack`.

- [ ] **Step 3: Write the implementation**

In `Pack.scala`, add the field to the case class right after `programs`:

```scala
  // Regex template locating a program's TARGET implementation files (relative paths), `<NAME>` substituted with the
  // program name. The seam that makes per-program judging possible. Defaults to a case-insensitive name match.
  programFiles: Option[String],
```

Add the accessor next to `def prompt`:

```scala
  /** The relative-path regex for `program`'s implementation files: the pack's `programFiles:` template with `<NAME>`
    * substituted, or a case-insensitive "path contains the program name" fallback.
    */
  def filesFor(program: String): String =
    programFiles.fold(s".*(?i)${java.util.regex.Pattern.quote(program)}.*")(_.replace("<NAME>", program))
```

In `parse`, add to the `Pack(...)` construction right after `programs = fields.get("programs"),`:

```scala
                programFiles = fields.get("programFiles"),
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.PackSpec'`
Expected: PASS.

Then run the whole flow suite, because adding a case-class field breaks every positional `Pack(...)` construction in tests:

Run: `sbt llm4zioFlow/testFull`
Expected: PASS. Fix any construction sites the compiler flags.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Pack.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/PackSpec.scala
git commit -m "feat(flow): Pack.programFiles — locate a program's target files"
```

---

### Task 7: `ProgramJudge` — shared per-program judging

**Files:**
- Create: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ProgramJudge.scala`
- Test: `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ProgramJudgeSpec.scala` (first test in this module — the directory does not exist yet; `build.sbt:181` already wires `zioTestDeps` and the ZTestFramework, so no build change is needed)

**Interfaces:**
- Consumes: `Pack.filesFor` (Task 6), `Context.withShrink` / `Context.capped` (Tasks 3-4), `GitTool.diffVsBase(base, paths)` (Task 5), `ReviewCache.cached` / `ReviewCache.fingerprint`, `Judge.of`, `Sample`, `EvalResult`, `Dimension`, `ReviewResult`, `ReviewIssue`, `Severity`.
- Produces:
  - `ProgramJudge.groupFiles(pack: Pack, programs: List[String], changed: List[String]): (Map[String, List[String]], List[String])` — per-program files, plus the unassigned remainder.
  - `ProgramJudge.judgeAll(pack, judge, dims, gateDir, base, programs, specFor, query)(using FlowContext): IO[FlowError, ReviewResult]`

Extracted into its own file because Implement (Task 11) and Review (Task 12) both need it and neither should own it.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ProgramJudgeSpec.scala`:

```scala
package llm4zio.modernize

import zio.test.*

import llm4zio.flow.*

object ProgramJudgeSpec extends ZIOSpecDefault:

  private def packWith(template: Option[String]): Pack =
    Pack(
      name = "test",
      source = "cobol",
      scaffold = None,
      sources = None,
      programs = None,
      programFiles = template,
      specsDir = "docs/specs",
      featuresDir = "features",
      gates = Map.empty,
      replay = None,
      equivalence = ComparisonPolicy.default,
      judgeDimensions = Nil,
      coverage = Nil,
      survey = Nil,
      prompts = Map.empty,
      lenses = Nil,
      lessons = None,
      dir = java.nio.file.Path.of("."),
    )

  def spec = suite("ProgramJudge")(
    test("groupFiles assigns changed files to programs and collects the remainder") {
      val pack    = packWith(None) // default: case-insensitive name match
      val changed = List(
        "src/main/java/Acctxfr.java",
        "src/main/java/AcctxfrService.java",
        "src/main/java/Balinq.java",
        "src/main/java/CommonUtil.java",
        "pom.xml",
      )
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR", "BALINQ"), changed)
      assertTrue(
        byProgram("ACCTXFR").toSet == Set("src/main/java/Acctxfr.java", "src/main/java/AcctxfrService.java"),
        byProgram("BALINQ") == List("src/main/java/Balinq.java"),
        unassigned.toSet == Set("src/main/java/CommonUtil.java", "pom.xml"),
      )
    },
    test("groupFiles honours a pack's programFiles template") {
      val pack    = packWith(Some("""src/main/java/.*<NAME>.*\.java"""))
      val changed = List("src/main/java/ACCTXFR.java", "docs/ACCTXFR.md")
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR"), changed)
      assertTrue(
        byProgram("ACCTXFR") == List("src/main/java/ACCTXFR.java"),
        unassigned == List("docs/ACCTXFR.md"),
      )
    },
    test("groupFiles assigns a file matching two programs to both") {
      val pack    = packWith(None)
      val changed = List("src/main/java/AcctxfrBalinqBridge.java")
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR", "BALINQ"), changed)
      assertTrue(
        byProgram("ACCTXFR") == changed,
        byProgram("BALINQ") == changed,
        unassigned.isEmpty,
      )
    },
  )
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.ProgramJudgeSpec'`
Expected: FAIL to compile — `Not found: ProgramJudge`.

- [ ] **Step 3: Write the implementation**

Create `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ProgramJudge.scala`:

```scala
package llm4zio.modernize

import java.nio.file.Path

import zio.{ IO, ZIO }

import llm4zio.eval.{ Dimension, EvalResult, Evaluator, Sample }
import llm4zio.flow.*

/** Per-program spec-compliance judging, shared by [[ImplementFlow]] and [[ReviewFlow]].
  *
  * A whole-branch judge call carries every spec and every diff hunk in the estate, which is what blows a provider's
  * input window. Judging one program at a time against only that program's slice of the diff keeps each call small,
  * and — wrapped in [[ReviewCache]] — makes the gate resumable: an unchanged program reuses its stored verdict with no
  * LLM call, exactly as `ExtractFlow`'s gate already does.
  */
object ProgramJudge:

  /** Partition `changed` by which program's file regex matches. A file matching several programs is judged with each
    * of them (a shared bridge class is genuinely part of both). The remainder — build files, shared utilities — is
    * returned separately for the unassigned pass.
    */
  def groupFiles(
    pack: Pack,
    programs: List[String],
    changed: List[String],
  ): (Map[String, List[String]], List[String]) =
    val byProgram  = programs.map(p => p -> changed.filter(_.matches(pack.filesFor(p)))).toMap
    val assigned   = byProgram.values.flatten.toSet
    val unassigned = changed.filterNot(assigned.contains)
    (byProgram, unassigned)

  /** Judge every program whose files changed, plus one pass over the unassigned remainder. Each verdict is cached at
    * `gateDir/<NAME>.json`, fingerprinted over the spec, the diff slice, and the rubric it judged — so re-running
    * after a crash re-judges only what changed.
    *
    * `specFor` supplies a program's spec text; the caller owns where specs live (the two flows differ).
    */
  def judgeAll(
    pack: Pack,
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    programs: List[String],
    specFor: String => IO[FlowError, String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    given FlowEvents = ctx.events
    for
      changed              <- git.changedFilesVsBase(base)
      (byProgram, leftover) = groupFiles(pack, programs, changed)
      active                = programs.filter(p => byProgram.getOrElse(p, Nil).nonEmpty)
      perProgram           <- ZIO.foreach(active)(p =>
                                judgeOne(judge, dims, gateDir, base, p, byProgram(p), specFor, query)
                              )
      residual             <- ZIO.when(leftover.nonEmpty)(
                                judgeUnassigned(pack, judge, dims, gateDir, base, leftover, specFor, programs, query)
                              )
    yield Reviewers.merge(perProgram ++ residual.toList)

  private def judgeOne(
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    program: String,
    files: List[String],
    specFor: String => IO[FlowError, String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    given FlowEvents = ctx.events
    for
      spec   <- specFor(program)
      // `files` comes from groupFiles — do NOT re-derive it here; judgeAll already computed the grouping and
      // re-running changedFilesVsBase per program would be N+1 git invocations for the same answer.
      diff   <- git.diffVsBase(base, files)
      rubric  = dims.map(d => s"${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
      result <- ReviewCache.cached(gateDir.resolve(s"$program.json"), ReviewCache.fingerprint(spec, diff, rubric)) {
                  ctx.events.publish(FlowEvent.Info(s"judging $program")) *>
                    Context.withShrink(s"judge[$program]") { cap =>
                      for
                        s <- Context.capped(s"spec[$program]", spec, cap)
                        d <- Context.capped(s"diff[$program]", diff, cap)
                        r <- judge
                               .evaluate(Sample(response = d, context = Some(s), query = Some(query)))
                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                      yield r
                    }.map(issues(_, dims, program))
                }
    yield result

  private def judgeUnassigned(
    pack: Pack,
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    files: List[String],
    specFor: String => IO[FlowError, String],
    programs: List[String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    given FlowEvents = ctx.events
    for
      diff   <- git.diffVsBase(base, files)
      specs  <- ZIO.foreach(programs)(specFor).map(_.mkString("\n\n"))
      rubric  = dims.map(d => s"${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
      result <- ReviewCache.cached(gateDir.resolve("unassigned.json"), ReviewCache.fingerprint(specs, diff, rubric)) {
                  ctx.events.publish(FlowEvent.Info(s"judging ${files.size} unassigned file(s)")) *>
                    Context.withShrink("judge[unassigned]") { cap =>
                      for
                        s <- Context.capped("spec[unassigned]", specs, cap)
                        d <- Context.capped("diff[unassigned]", diff, cap)
                        r <- judge
                               .evaluate(Sample(response = d, context = Some(s), query = Some(query)))
                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                      yield r
                    }.map(issues(_, dims, "unassigned"))
                }
    yield result

  /** Sub-bar dimensions as Critical review issues, titled with the program they belong to — the same shape
    * `ExtractFlow.judgeIssues` produces, so `fixLoop` and `ReviewResult.isClean` work unchanged.
    */
  private def issues(scored: EvalResult, dims: List[Dimension], program: String): ReviewResult =
    val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
    ReviewResult(
      subBar.map(s => ReviewIssue(Severity.Critical, s"judge[$program]: ${s.name} scored ${s.score}", s.reasoning)),
      s"judge:$program",
    )
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.ProgramJudgeSpec'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ProgramJudge.scala modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ProgramJudgeSpec.scala
git commit -m "feat(modernize): ProgramJudge — cached per-program spec-compliance judging"
```

---

### Task 8: Migrate `ExtractFlow` onto `Context`

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ExtractFlow.scala` (delete `JudgeSourcesLimit` ~142, `capText` ~145-149, `judgeWithShrink` ~296-313; update `judgeProgram` ~318 and the Plan stage ~500-510)
- Modify: `examples/modernize-extract.sc` (mirror the same changes; the `.sc` files are the authoring surface and must stay in step)

**Interfaces:**
- Consumes: `Context.withShrink`, `Context.capped` (Tasks 3-4).
- Produces: nothing new. `ExtractFlow.capText`, `ExtractFlow.judgeWithShrink`, and `ExtractFlow.JudgeSourcesLimit` are **removed** — later tasks must not reference them.

- [ ] **Step 1: Delete the local implementations**

In `ExtractFlow.scala` remove `JudgeSourcesLimit`, `capText`, `isEmptyResponse`, and `judgeWithShrink` entirely.

- [ ] **Step 2: Rewrite `judgeProgram`'s evaluation to use the ladder**

Replace the `judgeWithShrink(judge, Sample(...))` call inside `judgeProgram` with:

```scala
                   Context.withShrink(s"judge[$name]") { cap =>
                     for
                       src <- Context.capped(s"source[$name]", source, cap)
                       out <- Context.capped(s"spec[$name]", s"$spec\n\n$feature", cap)
                       r   <- judge
                                .evaluate(Sample(response = out, context = Some(src), query = Some(userPrompt)))
                                .mapError(e => FlowError.Llm(e.message, Some(e)))
                     yield r
                   }.map(judgeIssues(_, pack.judgeDimensions, name))
```

`judgeProgram` already has `ctx: FlowContext` in scope; add `given FlowEvents = ctx.events` at the top of its body if the compiler cannot summon one.

- [ ] **Step 3: Update the Plan stage**

Replace `capText(specText, JudgeSourcesLimit)` with `Context.capped("spec pack", specText, Context.budget)`. Because `capped` is effectful, the `Planner.from` call moves into the for-comprehension:

```scala
                  for
                    specText <- gatherSpecPack(modDir)
                    capped   <- Context.capped("spec pack", specText, Context.budget)
                    plan     <- Planner.from(
                                  reasoning,
                                  capped,
                                  Planner.defaultInstructions + "\n\n" + pack.prompt("plan").getOrElse(""),
                                )
                    _        <- writeFile(modDir.resolve("plan.md"), plan.render)
                  yield ()
```

- [ ] **Step 4: Make `AnalystTurns` env-overridable**

Replace `val AnalystTurns = 48` with:

```scala
  val AnalystTurns: Int = Env.get("LLM4ZIO_ANALYST_TURNS").flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(48)
```

- [ ] **Step 5: Mirror every change into `examples/modernize-extract.sc`**

The `.sc` file carries its own copies of `capText`/`judgeWithShrink`/`JudgeSourcesLimit`. Delete them and apply the same three edits. It uses `sys.env` rather than `Env`, so `AnalystTurns` becomes:

```scala
val AnalystTurns: Int = sys.env.get("LLM4ZIO_ANALYST_TURNS").flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(48)
```

- [ ] **Step 6: Verify the module compiles and the suite passes**

Run: `sbt llm4zioModernize/compile && sbt testFull`
Expected: PASS. `-Wunused:all` will flag any import left behind by the deletions (`llm4zio.core.LlmError` may now be unused) — remove them.

- [ ] **Step 7: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ExtractFlow.scala examples/modernize-extract.sc
git commit -m "refactor(modernize): ExtractFlow uses the shared Context ladder"
```

---

### Task 9: Bound the Extract analyst's reads with a pre-resolved include closure

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ExtractFlow.scala` (`programAsk` ~166-186, `extractPrograms` ~199-222)
- Modify: `examples/modernize-extract.sc`
- Test: `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/IncludeClosureSpec.scala`

**Interfaces:**
- Consumes: `Survey.graph`, `SurveyGraph`, `SurveyEdge`, `Pack.survey`, `Pack.sources`, `Context.cap` (Task 1).
- Produces: `ExtractFlow.closureFor(graph: SurveyGraph, program: String, maxFiles: Int): List[String]`.

**This is the (C) fix, and the one that explains Extract failing despite its cap.** `programAsk` currently says *"Read `$rel` and anything it references (copybooks, includes, called programs)"* with a 48-turn budget — up to 48 rounds of the CLI pulling files into **its own** context, inside one `Chat.ask`. No prompt-side cap can reach that. Naming the exact files up front bounds it.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/IncludeClosureSpec.scala`:

```scala
package llm4zio.modernize

import zio.test.*

import llm4zio.flow.{ SurveyEdge, SurveyGraph, SurveyNode }

object IncludeClosureSpec extends ZIOSpecDefault:

  private def node(path: String) = SurveyNode(path, path.split('/').last.takeWhile(_ != '.'), 10, 1)

  private val graph = SurveyGraph(
    nodes = List(node("cobol/ACCTXFR.cbl"), node("copy/ACCTREC.cpy"), node("copy/COMMON.cpy"), node("cobol/BALINQ.cbl")),
    edges = List(
      SurveyEdge("ACCTXFR", "ACCTREC", "copy"),
      SurveyEdge("ACCTREC", "COMMON", "copy"),   // transitive
      SurveyEdge("BALINQ", "COMMON", "copy"),    // unrelated branch
    ),
  )

  def spec = suite("include closure")(
    test("closureFor walks transitively and excludes unrelated units") {
      val out = ExtractFlow.closureFor(graph, "ACCTXFR", maxFiles = 50)
      assertTrue(
        out == List("copy/ACCTREC.cpy", "copy/COMMON.cpy"),
        !out.contains("cobol/BALINQ.cbl"),
        !out.contains("cobol/ACCTXFR.cbl"), // the program itself is named separately, not in its closure
      )
    },
    test("closureFor terminates on a dependency cycle") {
      val cyclic = graph.copy(edges = graph.edges :+ SurveyEdge("COMMON", "ACCTXFR", "copy"))
      val out    = ExtractFlow.closureFor(cyclic, "ACCTXFR", maxFiles = 50)
      assertTrue(out == List("copy/ACCTREC.cpy", "copy/COMMON.cpy"))
    },
    test("closureFor caps the file count") {
      val out = ExtractFlow.closureFor(graph, "ACCTXFR", maxFiles = 1)
      assertTrue(out == List("copy/ACCTREC.cpy"))
    },
  )
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.IncludeClosureSpec'`
Expected: FAIL to compile — `value closureFor is not a member of object ExtractFlow`.

- [ ] **Step 3: Write `closureFor`**

Add to `ExtractFlow.scala`:

```scala
  /** Max files named in one program's include closure. A program pulling more than this gets a bounded, visible
    * subset rather than an unbounded read.
    */
  val MaxClosureFiles: Int =
    Env.get("LLM4ZIO_MAX_CLOSURE_FILES").flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(40)

  /** The transitive dependency closure of `program` as repo-relative paths, breadth-first, excluding the program
    * itself. Visited-set guarded, so a cyclic COPY graph terminates. Truncated to `maxFiles`.
    */
  def closureFor(graph: SurveyGraph, program: String, maxFiles: Int): List[String] =
    val pathOf = graph.nodes.map(n => n.name -> n.path).toMap
    def walk(frontier: List[String], seen: Set[String], acc: List[String]): List[String] =
      if frontier.isEmpty || acc.size >= maxFiles then acc.take(maxFiles)
      else
        val next = frontier.flatMap(f => graph.edges.filter(_.from == f).map(_.to)).distinct.filterNot(seen)
        walk(next, seen ++ next, acc ++ next.flatMap(pathOf.get))
    walk(List(program), Set(program), Nil)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.IncludeClosureSpec'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Thread the closure into `programAsk` and `extractPrograms`**

Change `programAsk`'s signature to take the closure, and replace the "read anything it references" paragraph:

```scala
  def programAsk(pack: Pack, rel: String, name: String, closure: List[String]): String =
    val reads =
      if closure.isEmpty then s"Read $rel. It has no resolved dependencies."
      else
        s"""Read $rel and EXACTLY these resolved dependencies — do not go looking for others:
           |${closure.map(f => s"- $f").mkString("\n")}""".stripMargin
    s"""Extract the behavioural spec for ONE source unit of this repository: $rel
       |
       |Write exactly these files (create directories as needed):
       |
       |- $ModDir/specs/$name.md — the behavioural spec for $rel.
       |${pack.prompt("spec").getOrElse("")}
       |
       |- $ModDir/features/${name.toLowerCase}.feature — BDD scenarios encoding that spec.
       |${pack.prompt("bdd").getOrElse("")}
       |
       |- $ModDir/traceability/$name.md — EVERY source unit of $rel (each COBOL paragraph, each JCL
       |  step) on its own line, mapped to the spec rules/scenarios that cover it: `<UNIT-NAME> — <refs>`.
       |  Unit names verbatim as they appear in the source.
       |
       |- $ModDir/mapping/$name.md — data & interface mapping for $rel: tables/record layouts → target
       |  entities; files/screens/queues → target service contracts.
       |
       |$reads
       |
       |Spec ONLY $rel and do not modify legacy sources. Write the four files, then stop.""".stripMargin
```

In `extractPrograms`, build the graph **once** before the loop and pass each program's closure in. Add a `graph: SurveyGraph` parameter to `extractPrograms` and at the call site compute:

```scala
        graph <- stage("Graph")(
                   if pack.survey.isEmpty then
                     events
                       .publish(FlowEvent.Info(
                         "pack has no '## Survey:' edge regexes — the analyst gets no resolved closure"
                       ))
                       .as(SurveyGraph(Nil, Nil))
                   else
                     Survey.graph(workDir, pack.sources.getOrElse(""".*"""), pack.coverage, pack.survey)
                 )
```

and inside the loop:

```scala
          _    <- chat.ask(programAsk(pack, rel, name, closureFor(graph, name, MaxClosureFiles)))
                    .unit
                    .catchSome(turnLimitRecovery(spec, rel))
```

A pack with no `survey:` sections degrades to an empty closure and the "no resolved dependencies" wording — the analyst still works, just without the bound. That is the honest fallback, and the Info event says so.

- [ ] **Step 6: Mirror into `examples/modernize-extract.sc`**

Apply the same changes; use `sys.env` in place of `Env`.

- [ ] **Step 7: Verify**

Run: `sbt llm4zioModernize/testFull`
Expected: PASS.

- [ ] **Step 8: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ExtractFlow.scala modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/IncludeClosureSpec.scala examples/modernize-extract.sc
git commit -m "fix(modernize): bound the analyst's reads with a resolved include closure"
```

---

### Task 10: Fresh `Chat` per task in `ImplementFlow`

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala` (line 189 `Chat.start`, 190-207 `implementTaskLoop`, 119/141 `specComplianceLoop`)
- Modify: `examples/modernize-implement.sc`
- Test: `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ChatPerTaskSpec.scala`

**Interfaces:**
- Consumes: `Chat.start`, `Chat.messages`.
- Produces: `specComplianceLoop` takes `system: String` instead of `coderChat: Chat`, and starts its own chat.

**Why this is safe:** the repo is the shared memory. `plan.taskPrompt` carries the task, the system prompt carries the pack brief + lessons + pattern cards, and a CLI coder reads what earlier tasks wrote to disk. Nothing load-bearing lives only in the transcript. Today one `coderChat` serves every task, and `Chat` replays its whole `List[Message]` on every `ask` (there is no backend session token), so task 30's request carries all 29 prior tasks.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ChatPerTaskSpec.scala`. This pins the *property* — a chat used for one task never accumulates another task's turns — against a stub service, without running the whole flow:

```scala
package llm4zio.modernize

import zio.*
import zio.json.JsonCodec
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.flow.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ChatPerTaskSpec extends ZIOSpecDefault:

  /** Records the size of the history each call receives. */
  final class Recording(seen: Ref[List[Int]]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              =
      ZStream.succeed(LlmChunk(delta = "done"))
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      ZStream.unwrap(seen.update(_ :+ messages.size).as(ZStream.succeed(LlmChunk(delta = "done"))))
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec = suite("chat per task")(
    test("a fresh chat per task keeps every request the same size") {
      for
        seen <- Ref.make(List.empty[Int])
        svc   = Recording(seen)
        _    <- ZIO.foreachDiscard(List("task 1", "task 2", "task 3")) { t =>
                  Chat.start(svc, system = Some("sys")).flatMap(_.ask(t))
                }
        out  <- seen.get
      yield assertTrue(out == List(2, 2, 2)) // system + user, every time
    },
    test("one shared chat grows with every task — the behaviour being removed") {
      for
        seen <- Ref.make(List.empty[Int])
        svc   = Recording(seen)
        chat <- Chat.start(svc, system = Some("sys"))
        _    <- ZIO.foreachDiscard(List("task 1", "task 2", "task 3"))(chat.ask)
        out  <- seen.get
      yield assertTrue(out == List(2, 4, 6))
    },
  )
```

- [ ] **Step 2: Run the test to verify it passes as a characterisation**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.ChatPerTaskSpec'`
Expected: PASS. Both tests describe `Chat` as it already behaves — the second documents exactly the growth this task removes from the flow. Nothing to implement in `Chat` itself.

- [ ] **Step 3: Move `Chat.start` inside the task loop**

In `ImplementFlow.scala`, delete `coderChat <- Chat.start(coder, system = Some(system))` at line 189 and open a chat per task instead:

```scala
        _         <- implementTaskLoop(planFile, plan) { task =>
                       val testsTask = plan.tasks.headOption.contains(task)
                       for
                         coderChat <- Chat.start(coder, system = Some(system))
                         _         <- coderChat.ask(plan.taskPrompt(task))
                         _         <- reviewAndFixLoop(
                                        Reviewers.minimal ++ pack.lenses,
                                        reviewSvc,
                                        coderChat,
                                        task.title,
                                        git.diffAll,
                                        lint = Some(if testsTask then buildGate else testGate),
                                        parallelism = 1, // gemini free tier 429s under concurrent reviewers
                                      )
                         _         <- ZIO.when(testsTask) {
                                        testGate.flatMap { r =>
                                          ZIO.when(r.isClean)(
                                            fail("the new acceptance tests pass before any implementation — they encode nothing")
                                          )
                                        }
                                      }
                         _         <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                       yield ()
                     }
```

`reviewAndFixLoop` keeps the task's own chat, so a task's fix rounds still see what that task did. `git.diffAll` stays — it is the working-tree diff, i.e. one task's uncommitted work, and is naturally bounded.

- [ ] **Step 4: Give `specComplianceLoop` its own chat**

Change the signature from `coderChat: Chat` to `system: String`, and start a chat lazily inside the feedback branch:

```scala
                else
                  Chat.start(coder, system = Some(system)).flatMap(_.ask(judgeFeedback(below))) *>
                    verGate.flatMap(r =>
                      ZIO.unless(r.isClean)(fail("verify gate broke while addressing judge feedback")).unit
                    ) *>
                    git.commitAll(s"$epicId: address spec-compliance feedback").unit *>
                    round(n + 1)
```

Update the call site at line 225 to pass `system` instead of `coderChat`.

- [ ] **Step 5: Mirror into `examples/modernize-implement.sc`**

- [ ] **Step 6: Verify**

Run: `sbt llm4zioModernize/testFull && sbt llm4zioFlow/It/testFull`
Expected: PASS. `ModernizationPipelineSpec` exercises the pipeline end to end — if it fails here, a task genuinely depended on cross-task transcript memory and that must be surfaced, not worked around.

- [ ] **Step 7: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/ChatPerTaskSpec.scala examples/modernize-implement.sc
git commit -m "fix(modernize): fresh coder chat per task, so history stops growing"
```

---

### Task 11: Per-program judging plus the estate-wide traceability pass in `ImplementFlow`

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala` (`gatherSpecs` ~92-110, `specComplianceLoop` ~113-146, Judge stage ~224-226)
- Modify: `examples/modernize-implement.sc`

**Interfaces:**
- Consumes: `ProgramJudge.judgeAll` (Task 7), `Context.capped` (Task 3), `VerifyFlow`-style `specPrograms` helper.
- Produces: `ImplementFlow.specPrograms(specsDir: Path): IO[FlowError, List[String]]` (mirrors `VerifyFlow.specPrograms`), `ImplementFlow.traceabilityPass(...)`.

- [ ] **Step 1: Add the program enumerator**

`VerifyFlow` already has exactly this. Copy it into `ImplementFlow` rather than cross-importing between flows:

```scala
  /** The spec'd programs: top-level `<NAME>.md` files under the pack's specs dir, indexes aside. */
  def specPrograms(specsDir: Path): IO[FlowError, List[String]] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(specsDir) then Nil
        else
          val stream = Files.list(specsDir)
          try
            stream
              .iterator()
              .asScala
              .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
              .map(_.getFileName.toString.stripSuffix(".md"))
              .filterNot(Set("traceability", "mapping", "README"))
              .toList
              .sorted
          finally stream.close()
      }
      .mapError(e => FlowError.Persistence(s"failed to list specs under $specsDir", Some(e)))
```

- [ ] **Step 2: Add the estate-wide traceability pass**

This is the cross-program check that per-program judging cannot see. It carries `traceability.md` plus the changed-file **names only** — never their contents — so it stays small regardless of estate size:

```scala
  /** One bounded estate-wide pass: the traceability index plus the changed-file NAMES (never contents). Catches
    * cross-program breakage — a rule moved between programs, a scenario orphaned — that per-program judging misses.
    */
  def traceabilityPass(
    judge: Evaluator[Sample],
    dims: List[Dimension],
    specsDir: Path,
    base: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    given FlowEvents = ctx.events
    for
      trace   <- readFileOr(specsDir.resolve("traceability.md"), "")
      changed <- git.changedFilesVsBase(base)
      names    = changed.mkString("\n")
      result  <- Context.withShrink("judge[traceability]") { cap =>
                   for
                     t <- Context.capped("traceability", trace, cap)
                     r <- judge
                            .evaluate(Sample(
                              response = s"Files changed on this branch:\n$names",
                              context = Some(t),
                              query = Some(userPrompt),
                            ))
                            .mapError(e => FlowError.Llm(e.message, Some(e)))
                   yield r
                 }
    yield
      val subBar = result.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
      ReviewResult(
        subBar.map(s => ReviewIssue(Severity.Critical, s"judge[traceability]: ${s.name} scored ${s.score}", s.reasoning)),
        "judge:traceability",
      )
```

Add a `readFileOr` helper to `ImplementFlow` if it does not already have one — copy the four-line version from `VerifyFlow`.

- [ ] **Step 3: Rewrite `specComplianceLoop` to judge per program**

Replace the body of `round(n)` so it calls `ProgramJudge.judgeAll` plus `traceabilityPass` instead of one whole-branch `judge.evaluate`.

**Keep `gatherSpecs`.** It has two call sites and only one goes away. The first (before `Chat.start`) feeds `cited = Patterns.tagged(specText)` for pattern-card selection and must stay; only the second, immediately before the Judge stage, is deleted. Do not remove the `gatherSpecs` definition itself.

```scala
  def specComplianceLoop(
    system: String,
    judge: Evaluator[Sample],
    verGate: IO[FlowError, ReviewResult],
    pack: Pack,
    epicId: String,
  )(using ctx: FlowContext
  ): IO[FlowError, Unit] =
    given FlowEvents = ctx.events
    val specsDir = workDir.resolve(pack.specsDir)
    val gateDir  = workDir.resolve(ModDir).resolve("gate")
    def round(n: Int): IO[FlowError, Unit] =
      for
        base     <- git.defaultBase
        programs <- specPrograms(specsDir)
        perProg  <- ProgramJudge.judgeAll(
                      pack,
                      judge,
                      complianceDims,
                      gateDir,
                      base,
                      programs,
                      p => readFileOr(specsDir.resolve(s"$p.md"), ""),
                      userPrompt,
                    )
        trace    <- traceabilityPass(judge, complianceDims, specsDir, base)
        merged    = Reviewers.merge(List(perProg, trace))
        _        <- if merged.isClean then
                      ctx.events.publish(FlowEvent.Info("spec-compliance judge: branch cleared the bar"))
                    else if n >= JudgeRounds then
                      fail(
                        s"spec-compliance judge not cleared after $JudgeRounds round(s):\n" +
                          merged.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
                      )
                    else
                      Chat.start(coder, system = Some(system)).flatMap(_.ask(judgeFeedback(merged))) *>
                        verGate.flatMap(r =>
                          ZIO.unless(r.isClean)(fail("verify gate broke while addressing judge feedback")).unit
                        ) *>
                        git.commitAll(s"$epicId: address spec-compliance feedback").unit *>
                        round(n + 1)
      yield ()
    round(1)
```

Change `judgeFeedback` to take a `ReviewResult` rather than `List[DimensionScore]`:

```scala
  def judgeFeedback(findings: ReviewResult): String =
    val lines = findings.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
    s"""The final spec-compliance review scored the branch below the bar. Close these gaps without
       |weakening any test, then stop:
       |$lines""".stripMargin
```

Update the Judge stage call site:

```scala
        _         <- stage("Judge")(
                       specComplianceLoop(system, Judge.of(reasoning, complianceDims), verGate, pack, plan.epicId)
                     )
```

Delete only the second `specText <- gatherSpecs(...)` binding, the one immediately before the Judge stage. The earlier binding and the `gatherSpecs` definition both stay (see the note in Step 3).

- [ ] **Step 4: Mirror into `examples/modernize-implement.sc`**

- [ ] **Step 5: Verify**

Run: `sbt llm4zioModernize/testFull && sbt llm4zioFlow/It/testFull`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala examples/modernize-implement.sc
git commit -m "fix(modernize): per-program spec-compliance judging + traceability pass"
```

---

### Task 12: Scope `ReviewFlow`'s lenses, judge, and distill

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ReviewFlow.scala` (`distillPrompt` ~121-142, Review stage ~166-180, Judge stage ~182-188, Distill stage ~190-198)
- Modify: `examples/modernize-review.sc`

**Interfaces:**
- Consumes: `ProgramJudge.judgeAll` (Task 7), `Context.capped` (Task 3), `GitTool.diffVsBase(base, paths)` (Task 5), `Reviewer.matches`.
- Produces: nothing new.

Today this phase sends the same `specText` + whole `diff` pair **N+2 times**: once per lens, once to the judge, and a third time inside `distillPrompt`. The roster is already filtered by `Reviewer.matches(files)` — the *diff* just is not scoped to match.

- [ ] **Step 1: Scope each lens to its own files**

Replace the Review stage:

```scala
    findings <- stage("Review") {
                  val roster = (Reviewers.all ++ pack.lenses).filter(_.matches(files))
                  ZIO
                    .foreach(roster) { r => // sequential: gemini free tier 429s under concurrent reviewers
                      val scoped = r.files.fold(files)(regex => files.filter(_.matches(regex)))
                      for
                        lensDiff <- git.diffVsBase(base, scoped)
                        result   <- Context.withShrink(s"review[${r.name}]") { cap =>
                                      for
                                        s <- Context.capped(s"specs[${r.name}]", specText, cap)
                                        d <- Context.capped(s"diff[${r.name}]", lensDiff, cap)
                                        o <- r.asService(reviewSvc)
                                               .executeStructured[ReviewResult](
                                                 Reviewers.reviewPrompt(
                                                   s"modernization increment vs committed specs\n\n$s",
                                                   d,
                                                 ),
                                                 Reviewers.schema,
                                               )
                                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                                      yield o
                                    }
                      yield result
                    }
                    .map(Reviewers.merge)
                }
```

An unscoped lens (`files = None`) still sees the whole diff — correct, that is what "no scope" means — but now it is capped.

- [ ] **Step 2: Switch the judge to per-program**

Replace the Judge stage:

```scala
    scored   <- stage("Judge") {
                  for
                    programs <- specPrograms(workDir.resolve(pack.specsDir))
                    result   <- ProgramJudge.judgeAll(
                                  pack,
                                  Judge.of(reasoning, complianceDims),
                                  complianceDims,
                                  workDir.resolve(ModDir).resolve("gate"),
                                  base,
                                  programs,
                                  p => readFileOr(workDir.resolve(pack.specsDir).resolve(s"$p.md"), ""),
                                  userPrompt,
                                )
                  yield result
                }
```

Add the same `specPrograms` and `readFileOr` helpers used in Task 11 (copy them into `ReviewFlow`).

`scored` changes type from `EvalResult` to `ReviewResult`, so `distillPrompt`'s second argument changes with it.

- [ ] **Step 3: Drop the whole-diff re-append from `distillPrompt`**

The findings already quote the relevant code; a third full copy of the diff is near-pure waste:

```scala
  def distillPrompt(packReviewPrompt: String, findings: ReviewResult, scored: ReviewResult): String =
    val findingLines = findings.issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")
    val scoreLines   = scored.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
    s"""$packReviewPrompt
       |
       |Below are the raw reviewer findings and judge results for a modernization increment.
       |Distill them:
       |- "fixes": findings where the implementation VIOLATES the committed specs. Each gets a
       |  short spec document (Markdown: what is wrong, the spec rule it violates, the expected
       |  behaviour) and a plan task (title + description naming the spec rules/scenarios).
       |- "improvements": worthwhile follow-ups that do NOT violate the specs.
       |- "lessons": rules of thumb that would help FUTURE modernizations of this kind — phrased
       |  generally (no file paths from this repo), one sentence each. Only include lessons that
       |  generalize; an empty list is a fine answer.
       |
       |Reviewer findings:
       |$findingLines
       |
       |Judge findings:
       |$scoreLines""".stripMargin
```

Update the Distill stage to drop the `diff` argument and wrap the call in the ladder:

```scala
    outcome  <- stage("Distill") {
                  Context.withShrink("distill") { cap =>
                    Context
                      .capped("distill prompt", distillPrompt(pack.prompt("review").getOrElse(""), findings, scored), cap)
                      .flatMap { prompt =>
                        reasoning
                          .executeStructured[ReviewOutcome](prompt, SchemaDerivation.derive[ReviewOutcome])
                          .mapError(e => FlowError.Llm(e.message, Some(e)))
                      }
                  }
                }
```

- [ ] **Step 4: Fix the final summary line**

`outcome.fixes.size` etc. are unchanged, but the `yield` string references remain valid. Confirm the commit message stage still compiles.

- [ ] **Step 5: Mirror into `examples/modernize-review.sc`**

- [ ] **Step 6: Verify**

Run: `sbt llm4zioModernize/testFull`
Expected: PASS. `-Wunused:all` will flag `EvalResult` / `DimensionScore` imports if they are now unused.

- [ ] **Step 7: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ReviewFlow.scala examples/modernize-review.sc
git commit -m "fix(modernize): scope review lenses to their files; stop resending the diff"
```

---

### Task 13: Per-program triage in `VerifyFlow`

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/VerifyFlow.scala` (`triagePrompt` ~222-246, Triage stage ~330-360)
- Modify: `examples/modernize-verify.sc`
- Test: `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/VerifyTriageSpec.scala`

**Interfaces:**
- Consumes: `Context.withShrink`, `Context.capped`.
- Produces: `VerifyFlow.triagePrompt(pack, program, failing, specText)` — now per-program.

Today `triagePrompt` concatenates `traceability.md` plus **every** program spec into one prompt for the whole estate. Mismatches already carry `v.vector.program`, so grouping is a regrouping, not a redesign.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/VerifyTriageSpec.scala`:

```scala
package llm4zio.modernize

import zio.test.*

import llm4zio.flow.*

object VerifyTriageSpec extends ZIOSpecDefault:

  private def vector(program: String, id: String) =
    EquivVector(
      schema = 1,
      program = program,
      id = id,
      tier = Equiv.Tier.Generated,
      rules = List("R1"),
      inputs = zio.json.ast.Json.Obj(),
      observations = Nil,
    )

  /** `Mismatch.Missing` wraps an `Observation`, not a String. */
  private def missing(kind: String) =
    Equiv.Mismatch.Missing(Equiv.Observation.Record(kind, Map("amount" -> "10.00")))

  def spec = suite("verify triage")(
    test("failing vectors group by program") {
      val failing = List(
        VectorVerdict(vector("ACCTXFR", "a"), List(missing("ledger"))),
        VectorVerdict(vector("ACCTXFR", "b"), List(missing("reject"))),
        VectorVerdict(vector("BALINQ", "c"), List(missing("report"))),
      )
      val grouped = failing.groupBy(_.vector.program)
      assertTrue(
        grouped.keySet == Set("ACCTXFR", "BALINQ"),
        grouped("ACCTXFR").size == 2,
        grouped("BALINQ").size == 1,
      )
    },
    test("triagePrompt for one program names only that program's spec") {
      val prompt = VerifyFlow.triagePrompt(
        pack = TestPacks.minimal,
        program = "ACCTXFR",
        failing = List(VectorVerdict(vector("ACCTXFR", "a"), List(missing("ledger")))),
        specText = "ACCTXFR spec body",
      )
      assertTrue(
        prompt.contains("ACCTXFR"),
        prompt.contains("ACCTXFR spec body"),
        !prompt.contains("BALINQ"),
      )
    },
  )
```

`TestPacks` is a shared helper — two call sites (this spec and `ProgramJudgeSpec`) justify extracting it.
Create `modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/TestPacks.scala` holding the
`packWith` builder from Task 7, plus `val minimal: Pack = packWith(None)`, and change
`ProgramJudgeSpec` to use it rather than its own private copy.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.VerifyTriageSpec'`
Expected: FAIL to compile — `triagePrompt` does not take a `program` parameter.

- [ ] **Step 3: Rewrite `triagePrompt` per program**

```scala
  def triagePrompt(pack: Pack, program: String, failing: List[VectorVerdict], specText: String): String =
    val details = failing
      .map { v =>
        val ms = v.mismatches
          .map {
            case Equiv.Mismatch.FieldDiff(at, field, e, a) => s"  - $at: $field expected $e, actual $a"
            case Equiv.Mismatch.Missing(o)                 => s"  - missing: $o"
            case Equiv.Mismatch.Unexpected(o)              => s"  - unexpected: $o"
          }
          .mkString("\n")
        s"- ${v.vector.id} (rules: ${v.vector.rules.mkString(", ")})\n$ms"
      }
      .mkString("\n")
    s"""${pack.prompt("review").getOrElse("")}
       |
       |The equivalence harness replayed test vectors for the program $program against the
       |implementation and found the mismatches below. For each DISTINCT root cause produce one fix:
       |a short spec document (Markdown: the rule violated, expected vs actual behaviour, the failing
       |vector ids) and a plan task (title + description naming the spec rules). Group mismatches
       |sharing a cause. If a mismatch reveals a wrong or ambiguous SPEC rather than wrong code, say
       |so explicitly in that fix document — spec gaps go back to extraction, not to the coder.
       |
       |Mismatches:
       |$details
       |
       |Spec for $program:
       |$specText""".stripMargin
```

- [ ] **Step 4: Rewrite the Triage stage to fan out per program**

```scala
    _        <- ZIO.when(failing.nonEmpty)(stage("Triage") {
                  val byProgram = failing.groupBy(_.vector.program).toList.sortBy(_._1)
                  for
                    outcomes <- ZIO.foreach(byProgram) { (program, vs) =>
                                  for
                                    spec    <- readFileOr(specsDir.resolve(s"$program.md"), "")
                                    outcome <- Context.withShrink(s"triage[$program]") { cap =>
                                                 Context
                                                   .capped(s"spec[$program]", spec, cap)
                                                   .flatMap { s =>
                                                     reasoning
                                                       .executeStructured[VerifyOutcome](
                                                         triagePrompt(pack, program, vs, s),
                                                         SchemaDerivation.derive[VerifyOutcome],
                                                       )
                                                       .mapError(e => FlowError.Llm(e.message, Some(e)))
                                                   }
                                               }
                                  yield outcome
                                }
                    allFixes  = outcomes.flatMap(_.fixes)
                    _        <- ZIO.foreachDiscard(allFixes) { f =>
                                  writeFile(specsDir.resolve("fixes").resolve(s"fix-${slug(f.title)}.md"),
                                    s"# ${f.title}\n\n${f.spec}\n")
                                }
                    _        <- ZIO.when(allFixes.nonEmpty) {
                                  PlanStore
                                    .load(planFile)
                                    .someOrFail(FlowError.Aborted(s"no plan at $planFile — run modernize-seed.sc first"))
                                    .flatMap { plan =>
                                      val increment = allFixes.map(f => Task(f.taskTitle, f.taskDescription))
                                      PlanStore.save(planFile, plan.copy(tasks = plan.tasks ++ increment)) *>
                                        events.publish(FlowEvent.Info(
                                          s"${increment.size} fix task(s) appended — rerun modernize-implement.sc"
                                        ))
                                    }
                                }
                  yield ()
                })
```

**Shadowing note:** this block names `flow.Task`. `VerifyFlow.scala` already imports `zio.{ IO, ZIO }` (indented inside the object, line 48) rather than `zio.*`, so `flow.Task` is not shadowed. Do not "tidy" that into a wildcard import — it would shadow `flow.Task` with `zio.Task` in type position and break this block.

- [ ] **Step 5: Mirror into `examples/modernize-verify.sc`**

- [ ] **Step 6: Verify**

Run: `sbt 'llm4zioModernize/testOnly llm4zio.modernize.VerifyTriageSpec' && sbt llm4zioModernize/testFull`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/VerifyFlow.scala modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/VerifyTriageSpec.scala modules/llm4zio-modernize/src/test/scala/llm4zio/modernize/TestPacks.scala examples/modernize-verify.sc
git commit -m "fix(modernize): triage equivalence failures per program"
```

---

### Task 14: Cap `SurveyFlow`'s two prompts

**Files:**
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/SurveyFlow.scala` (`refinePrompt` ~140-152, Graph refine stage ~211-230, Triage stage ~235-244)
- Modify: `examples/modernize-survey.sc`

**Interfaces:**
- Consumes: `Context.withShrink`, `Context.capped`.
- Produces: nothing new.

These scale with unit count rather than file contents, so they are the least likely to trip. Cap and record only — chunking stays deferred until the cap is observed to fire, per the spec's non-goals.

- [ ] **Step 1: Wrap the graph-refine call**

```scala
                            r     <- Context.withShrink("survey refine") { cap =>
                                       Context
                                         .capped("graph", refinePrompt(graph), cap)
                                         .flatMap { prompt =>
                                           reasoning
                                             .executeStructured[GraphRefinement](
                                               prompt,
                                               SchemaDerivation.derive[GraphRefinement],
                                             )
                                             .mapError(e => FlowError.Llm(e.message, Some(e)))
                                         }
                                     }
```

- [ ] **Step 2: Wrap the triage call**

```scala
        outcome    <- stage("Triage") {
                        Context.withShrink("survey triage") { cap =>
                          Context
                            .capped("inventory", triagePrompt(refined, Survey.renderInventory(refined)), cap)
                            .flatMap { prompt =>
                              reasoning
                                .executeStructured[SurveyOutcome](prompt, SchemaDerivation.derive[SurveyOutcome])
                                .mapError(e => FlowError.Llm(e.message, Some(e)))
                            }
                        }
                      }
```

- [ ] **Step 3: Mirror into `examples/modernize-survey.sc`**

- [ ] **Step 4: Verify**

Run: `sbt llm4zioModernize/testFull`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/SurveyFlow.scala examples/modernize-survey.sc
git commit -m "fix(modernize): cap SurveyFlow's graph-refine and triage prompts"
```

---

### Task 15: Record truncations in `provenance.json`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Provenance.scala` (case class ~15-27)
- Modify: `modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala`, `ReviewFlow.scala`, `VerifyFlow.scala`
- Modify: `examples/modernize-implement.sc`, `examples/modernize-review.sc`, `examples/modernize-verify.sc`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ProvenanceSpec.scala`

**Interfaces:**
- Consumes: `Context.truncations` (Task 3), `Provenance.extend`.
- Produces: `Provenance.contextTruncations: List[String]`.

This is what makes "truncate, but record it" true rather than aspirational. The field is **defaulted** so manifests written by earlier versions still parse.

- [ ] **Step 1: Write the failing test**

Add to `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ProvenanceSpec.scala`:

```scala
    test("a manifest without contextTruncations still parses, and extend can add them") {
      val legacy =
        """{"schema":1,"pack":"p","llm4zioVersion":"4.2.0","createdAt":"now","approvedBy":null,
          |"seats":{},"specs":{},"gateVerdicts":{},"equivalenceReport":null,"fixSpecs":[]}""".stripMargin
      ZIO.scoped {
        for
          dir <- tempDir
          file = dir.resolve("provenance.json")
          _   <- ZIO.attemptBlocking(java.nio.file.Files.write(file, legacy.getBytes)).orDie
          p   <- Provenance.load(file)
          ext <- Provenance.extend(file)(_.copy(contextTruncations = List("specs: 900 → 400 chars")))
          re  <- Provenance.load(file)
        yield assertTrue(
          p.contextTruncations.isEmpty,
          ext.contextTruncations.size == 1,
          re.contextTruncations == List("specs: 900 → 400 chars"),
        )
      }
    },
```

If `ProvenanceSpec` has no `tempDir` helper, copy the one from `GitToolSpec`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ProvenanceSpec'`
Expected: FAIL to compile — `value contextTruncations is not a member of Provenance`.

- [ ] **Step 3: Add the field**

In `Provenance.scala`, append to the case class (last position, defaulted, so `derives JsonCodec` treats it as optional on read):

```scala
  fixSpecs: List[String],
  // Context truncations recorded while producing this evidence — a verdict rendered on a partial view says so here.
  contextTruncations: List[String] = Nil,
) derives JsonCodec
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ProvenanceSpec'`
Expected: PASS.

- [ ] **Step 5: Write recorded truncations at each phase's Provenance stage**

`VerifyFlow` already has a Provenance stage — extend its `Provenance.extend` call:

```scala
                        Provenance
                          .hashFiles(workDir, List(s"$ModDir/equivalence.md"))
                          .flatMap(h =>
                            Context.truncations.flatMap(ts =>
                              Provenance.extend(manifest)(p =>
                                p.copy(
                                  equivalenceReport = h.values.headOption,
                                  contextTruncations = p.contextTruncations ++ ts.map(_.render).toList,
                                )
                              )
                            )
                          )
                          .unit
```

`ImplementFlow` and `ReviewFlow` have no Provenance stage. Add one to each, immediately before their Commit stage, skipping cleanly when no manifest exists:

```scala
        _         <- stage("Provenance") {
                       val manifest = workDir.resolve(ModDir).resolve("provenance.json")
                       ZIO
                         .attemptBlocking(java.nio.file.Files.exists(manifest))
                         .orDie
                         .flatMap {
                           case false => ZIO.unit // seeded before provenance existed
                           case true  =>
                             Context.truncations.flatMap { ts =>
                               ZIO.when(ts.nonEmpty)(
                                 Provenance
                                   .extend(manifest)(p =>
                                     p.copy(contextTruncations = p.contextTruncations ++ ts.map(_.render).toList)
                                   )
                                   .unit
                               )
                             }
                         }
                     }
```

- [ ] **Step 6: Mirror into the three `.sc` examples**

- [ ] **Step 7: Verify the whole build**

Run: `sbt fmt && sbt check && sbt testFull && sbt llm4zioFlow/It/testFull`
Expected: PASS across every module.

- [ ] **Step 8: Commit**

```bash
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Provenance.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/ProvenanceSpec.scala modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ImplementFlow.scala modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/ReviewFlow.scala modules/llm4zio-modernize/src/main/scala/llm4zio/modernize/VerifyFlow.scala examples/modernize-implement.sc examples/modernize-review.sc examples/modernize-verify.sc
git commit -m "feat(modernize): record context truncations in provenance.json"
```

---

### Task 16: Document the knobs

**Files:**
- Modify: `docs/legacy-modernization.md`
- Modify: `CLAUDE.md` (Packs bullet — mention `programFiles`)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Add a "Context budget" section to `docs/legacy-modernization.md`**

Document, with the exact defaults:

| Variable | Default | Effect |
| -------- | ------- | ------ |
| `LLM4ZIO_CONTEXT_BUDGET` | `400000` | Characters of context any single prompt may carry (~115k tokens). |
| `LLM4ZIO_JUDGE_SOURCES_LIMIT` | — | Deprecated alias for the above; still honoured. |
| `LLM4ZIO_ANALYST_TURNS` | `48` | Per-program turn budget for the Extract analyst. |
| `LLM4ZIO_MAX_CLOSURE_FILES` | `40` | Max dependencies named in one program's include closure. |

State plainly: oversized prompts shrink (full → ½ → ¼) and complete, and every shrink lands in `provenance.json` under `contextTruncations`. A verdict rendered on a truncated view is visible in the evidence chain.

Add a `programFiles:` row to the pack-manifest field table, with the default `.*(?i)<NAME>.*` and one worked example.

- [ ] **Step 2: Update the Packs bullet in `CLAUDE.md`**

Mention that `programFiles:` locates a program's target implementation files and is what makes per-program judging work.

- [ ] **Step 3: Commit**

```bash
git add docs/legacy-modernization.md CLAUDE.md
git commit -m "docs: context budget knobs and Pack.programFiles"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
| ------------ | ---- |
| `Context.cap` / `Capped` / `budget` | 1 |
| `Context.capped` + FiberRef recording | 3 |
| `Context.withShrink` ladder, widened trigger | 4 |
| `LLM4ZIO_JUDGE_SOURCES_LIMIT` deprecated alias | 1 |
| `GitTool.diffVsBase(base, paths)` | 5 |
| `Pack.programFiles` | 6 |
| Implement: fresh chat per task (B) | 10 |
| Implement: per-program judge + `ReviewCache` | 7, 11 |
| Implement: unassigned bucket via `withShrink` | 7 |
| Implement: estate-wide traceability pass | 11 |
| Review: per-lens scoped diffs | 12 |
| Review: shared per-program judge | 12 |
| Review: `distillPrompt` drops the diff | 12 |
| Verify: per-program triage | 13 |
| Survey: cap + record only | 14 |
| Extract: include closure (C) | 9 |
| Extract: closure capped and recorded | 9 |
| Extract: `AnalystTurns` env-overridable | 8 |
| Extract: delete `capText` / `judgeWithShrink` | 8 |
| `TransientRetry` 4xx guard + `isContextOverflow` (D) | 2 |
| `Provenance.contextTruncations` | 15 |
| Testing section (all six bullets) | 1-3, 5-7, 9, 13, 15 |

No gaps.

**Placeholder scan:** No TBD/TODO. Every code step carries real code.

**Verified against the codebase** (three scaffolding errors caught and corrected while writing this plan — each would have cost the implementer a failed compile):
- `Pack` has no public `parse`; `PackSpec` writes a temp `pack.md` and calls `Pack.load`. Task 6's test uses that pattern.
- `Equiv.Mismatch.Missing` wraps an `Equiv.Observation`, not a `String`. Task 13's test uses a real `Observation.Record`.
- `VerifyFlow` imports `zio.{ IO, ZIO }` indented inside the object, not `zio.*` — so `flow.Task` is already unshadowed. Task 13 says so rather than warning speculatively.
- `Reviewers.merge(List[ReviewResult]): ReviewResult` and `ReviewResult(issues, summary)` confirmed; `ProgramJudge.issues` matches `ExtractFlow.judgeIssues`'s existing shape.
- `build.sbt:181` already gives `llm4zioModernize` `zioTestDeps` + the ZTestFramework, so Task 7 needs no build change to add the module's first test.

**Type consistency:**
- `Context.capped(label, text, limit)(using FlowEvents): UIO[String]` — same everywhere.
- `Context.withShrink(label, start)(f: Int => IO[FlowError, A])(using FlowEvents)` — Tasks 4, 7, 8, 9, 11-14 all pass `label` first and take `cap: Int` in the lambda.
- `ProgramJudge.judgeAll(pack, judge, dims, gateDir, base, programs, specFor, query)` — Tasks 11 and 12 call it with that exact argument order.
- `Truncation.render` defined in Task 3, used in Task 15.
- `scored` changes from `EvalResult` to `ReviewResult` in Task 12, and `distillPrompt`'s signature changes with it in the same task.
- `judgeFeedback` changes from `List[DimensionScore]` to `ReviewResult` in Task 11, where its only call site also lives.
- `specPrograms` / `readFileOr` are copied into `ImplementFlow` (Task 11) and `ReviewFlow` (Task 12) rather than cross-imported between flows — deliberate, since flows do not depend on each other.

**Scope check:** One coherent theme — bound every prompt in one pipeline. 16 tasks, each independently testable and committable. Tasks 1-6 are library primitives with no behaviour change; 7-15 apply them; 16 documents. Fine as a single plan.
