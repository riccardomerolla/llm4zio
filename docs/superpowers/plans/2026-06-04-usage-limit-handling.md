# Usage-Limit Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a CLI coding agent hits a hard usage/credit cap, surface it as a typed `UsageLimitError` and (opt-in) sleep until the reset, then continue — so unattended flows survive the cap instead of dying.

**Architecture:** A pure-ADT `UsageLimitError(resetAt, provider, message)` produced by a per-provider `UsageLimits.classify` matcher; a `UsageLimitAware` `LlmService` decorator that waits-in-place on the idempotent IO calls (planning/review/tools); a `withUsageLimitRetry` flow combinator that sleeps-and-re-enters for the streaming coder + interactive `Drive`; gated by an opt-in `UsageLimitPolicy` wired through the runner.

**Tech Stack:** Scala 3.8.3, ZIO 2.1.x (`Clock`/`ZIO.sleep`/`TestClock`), zio-test, zio-json. `sbt` build; `-Werror`/`-Wunused:all`.

**Design refinement vs spec:** the decorator wraps the **IO methods** (`executeStructured`, `executeStructuredWithUsage`, `executeWithTools`) — the idempotent reads — and passes streaming methods through; the **combinator** owns the streaming coder + interactive path. This is the spec's idempotent-vs-non-idempotent division of labor made precise (avoids gnarly stream-retry-with-cap), and is why Task 3 also makes the codex *stream* fail with the typed error so the combinator can see it.

---

### Task 1: `UsageLimitError` ADT case

**Files:**
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/core/Errors.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/core/ErrorsSpec.scala` (exists)

- [ ] **Step 1: Write the failing test** — append inside the `suite("LlmError")(...)` list in `ErrorsSpec.scala` (after the existing cases):

```scala
    ,test("UsageLimitError is a pure ADT carrying resetAt + provider, and renders its message") {
      val at  = java.time.Instant.parse("2026-06-04T14:38:00Z")
      val err = LlmError.UsageLimitError(Some(at), "codex", "You've hit your usage limit")
      assertTrue(
        !err.isInstanceOf[Throwable],
        err.resetAt.contains(at),
        err.provider == "codex",
        err.message == "You've hit your usage limit",
      )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.core.ErrorsSpec'`
Expected: FAIL — `value UsageLimitError is not a member of object LlmError` (compile error).

- [ ] **Step 3: Add the case** — in `Errors.scala`, add after the `ProviderError` line (keep `import zio.*` which already brings `Duration`; add the `Instant` import at top):

```scala
import java.time.Instant
```

```scala
  case class UsageLimitError(resetAt: Option[Instant], provider: String, message: String) extends LlmError
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.core.ErrorsSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/core/Errors.scala modules/llm4zio-core/src/test/scala/llm4zio/core/ErrorsSpec.scala
git commit -m "feat(core): add LlmError.UsageLimitError (resetAt/provider/message)"
```

---

### Task 2: `UsageLimits.classify` matcher

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/providers/UsageLimits.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/providers/UsageLimitsSpec.scala`

- [ ] **Step 1: Write the failing test** — create `UsageLimitsSpec.scala`:

```scala
package llm4zio.providers

import java.time.{ Instant, ZoneId }

import zio.test.*

import llm4zio.core.LlmError

object UsageLimitsSpec extends ZIOSpecDefault:
  // A fixed "now": 2026-06-04 12:27 in UTC, evaluated in UTC so wall-clock parsing is deterministic.
  private val zone = ZoneId.of("UTC")
  private val now  = Instant.parse("2026-06-04T12:27:00Z")

  def spec: Spec[Environment & TestEnvironment, Any] = suite("UsageLimits.classify")(
    test("codex wall-clock 'try again at 2:38 PM' → UsageLimitError with today's resetAt") {
      val text = "You've hit your usage limit. Upgrade to Pro ... try again at 2:38 PM."
      UsageLimits.classify("codex", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), "codex", _)) =>
          assertTrue(at == Instant.parse("2026-06-04T14:38:00Z"))
        case other => assertTrue(false)
    },
    test("codex time already passed today → rolls to tomorrow") {
      val text = "usage limit ... try again at 11:00 AM."
      UsageLimits.classify("codex", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), _, _)) =>
          assertTrue(at == Instant.parse("2026-06-05T11:00:00Z"))
        case _ => assertTrue(false)
    },
    test("gemini short 'reset after 2s' → RateLimitError, not UsageLimitError") {
      val text = "You have exhausted your capacity on this model. Your quota will reset after 2s.."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.RateLimitError(Some(d))) => assertTrue(d == zio.Duration.fromSeconds(2))
        case _                                      => assertTrue(false)
    },
    test("unrecognized text → None") {
      assertTrue(UsageLimits.classify("codex", "some unrelated failure", now, zone).isEmpty)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.UsageLimitsSpec'`
Expected: FAIL — `not found: value UsageLimits`.

- [ ] **Step 3: Implement the matcher** — create `UsageLimits.scala`:

```scala
package llm4zio.providers

import java.time.{ Instant, LocalDate, LocalTime, ZoneId }
import java.time.format.DateTimeFormatter
import java.util.Locale

import zio.Duration

import llm4zio.core.LlmError

/** Pure classifier: maps a provider's raw error text into the right typed [[LlmError]] (or None if unrecognized).
  * `now`/`zone` are parameters (not read from a Clock) so parsing stays pure and unit-testable.
  */
object UsageLimits:

  private val codexAt   = """(?i)try again at\s+(\d{1,2}:\d{2}\s*[AP]M)""".r.unanchored
  private val claudeAt  = """(?i)usage limit.*?(?:resets?|try again) at\s+(\d{1,2}(?::\d{2})?\s*[ap]m)""".r.unanchored
  private val geminiSec = """(?i)reset after\s+(\d+)\s*s""".r.unanchored
  private val usageWord = """(?i)usage limit|exhausted your capacity|quota""".r.unanchored

  def classify(provider: String, text: String, now: Instant, zone: ZoneId): Option[LlmError] =
    provider match
      case "codex"  => wallClock(text, codexAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "claude" => wallClock(text, claudeAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "gemini" =>
        text match
          case geminiSec(secs) => Some(LlmError.RateLimitError(Some(Duration.fromSeconds(secs.toLong))))
          case _               => None
      case _        => None

  /** Parse a 12-hour wall-clock time from `text` and resolve it to the next occurrence at/after `now` in `zone`. */
  private def wallClock(text: String, re: scala.util.matching.Regex, now: Instant, zone: ZoneId): Option[Instant] =
    re.findFirstMatchIn(text).flatMap { m =>
      parseTime(m.group(1)).map { lt =>
        val today    = LocalDate.ofInstant(now, zone)
        val candidate = today.atTime(lt).atZone(zone).toInstant
        if candidate.isAfter(now) then candidate else today.plusDays(1).atTime(lt).atZone(zone).toInstant
      }
    }

  private def parseTime(s: String): Option[LocalTime] =
    val cleaned = s.trim.toUpperCase(Locale.US).replace(" ", "")
    val fmts    = List("h:mma", "ha")
    fmts.iterator.flatMap { p =>
      try Some(LocalTime.parse(cleaned, DateTimeFormatter.ofPattern(p, Locale.US)))
      catch case _: Throwable => None
    }.nextOption()
```

(No `throw` — `try/catch` returning `Option` is allowed; scalafix bans `throw`, not `catch`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.UsageLimitsSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/providers/UsageLimits.scala modules/llm4zio-core/src/test/scala/llm4zio/providers/UsageLimitsSpec.scala
git commit -m "feat(providers): pure UsageLimits.classify matcher (codex/claude/gemini)"
```

---

### Task 3: Wire `classify` into the providers

Detection plugs in where each provider already builds its error. `now`/`zone` come from `Clock.instant` + `ZoneId.systemDefault`. **codex** is the confirmed case and where the user's failure occurred; **gemini** maps short caps to `RateLimitError`; **claude** mirrors codex.

**Files:**
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/providers/CodexConnector.scala` (structured path ~line 92; `completeStream` ~line 53)
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` (~line 587)
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala` (`complete` ~line 42)
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/providers/CodexConnectorSpec.scala` (exists)

- [ ] **Step 1: Write the failing test** — in `CodexConnectorSpec.scala`, add (the `RecordingExec` and `ReplyStub` helpers already exist there):

```scala
    ,test("a codex usage-limit message becomes a typed UsageLimitError") {
      val failLine = """{"type":"turn.failed","error":{"message":"You've hit your usage limit. try again at 2:38 PM."}}"""
      for
        seen <- Ref.make(List.empty[String])
        conn  = CodexConnector.make(CliConnectorConfig(ConnectorId.Codex), new RecordingExec(seen, failLine))
        res  <- conn.executeStructured[ReplyStub]("go", SchemaDerivation.derive[ReplyStub]).either
      yield assertTrue(res.left.toOption.exists(_.isInstanceOf[LlmError.UsageLimitError]))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.CodexConnectorSpec'`
Expected: FAIL — the error is a `ProviderError`, not `UsageLimitError`.

- [ ] **Step 3: Classify in codex structured path** — in `CodexConnector.scala`, replace the `codexError` surfacing block in `executeStructuredWithUsage`:

```scala
              _     <- ZIO.foreachDiscard(reply.metadata.get("codexError")) { msg =>
                         for
                           now <- Clock.instant
                           err  = UsageLimits.classify("codex", msg, now, ZoneId.systemDefault)
                                    .getOrElse(LlmError.ProviderError(s"codex error: $msg", None))
                           _   <- ZIO.fail(err)
                         yield ()
                       }
```

Add `import java.time.ZoneId` at the top of the file.

- [ ] **Step 4: Make `completeStream` fail with the typed error too** — so the coder path (and the combinator) sees it. In `completeStream`, after the `mapConcat`, fail when a `codexError` chunk appears:

```scala
      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val argv = List("codex", "exec", "--json") ++ extraArgs ++ List(prompt)
        executor.runStreaming(argv, cwd, config.envVars)
          .mapConcat(CodexConnector.parseStreamLine)
          .mapZIO { chunk =>
            chunk.metadata.get("codexError") match
              case Some(msg) =>
                Clock.instant.flatMap(now =>
                  ZIO.fail(UsageLimits.classify("codex", msg, now, ZoneId.systemDefault)
                    .getOrElse(LlmError.ProviderError(s"codex error: $msg", None)))
                )
              case None      => ZIO.succeed(chunk)
          }
```

- [ ] **Step 5: Classify in gemini error path** — in `GeminiCliProvider.scala`, replace the `Result(status=error)` failure (~line 587) so a short cap becomes `RateLimitError`:

```scala
                case GeminiCliStreamEvent.Result(status, errorMessage, stats) if status.contains("error") =>
                  ZStream.unwrap(Clock.instant.map { now =>
                    val raw = errorMessage.getOrElse("Gemini CLI returned an error")
                    val err = UsageLimits.classify("gemini", raw, now, ZoneId.systemDefault)
                      .getOrElse(LlmError.ProviderError(s"Gemini CLI returned an error: $raw", None))
                    ZStream.fail(err)
                  })
```

Add `import java.time.ZoneId` to `GeminiCliProvider.scala`.

- [ ] **Step 6: Classify in claude `complete`** — in `ClaudeCliConnector.scala`, replace the non-zero-exit failure (~line 42):

```scala
            else
              Clock.instant.flatMap { now =>
                val raw = result.stdout.mkString("\n")
                ZIO.fail(UsageLimits.classify("claude", raw, now, ZoneId.systemDefault)
                  .getOrElse(LlmError.ProviderError(s"claude exited with code ${result.exitCode}: $raw", None)))
              }
```

Add `import java.time.ZoneId` to `ClaudeCliConnector.scala`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.CodexConnectorSpec' llm4zioCore/compile`
Expected: PASS (CodexConnectorSpec, incl. the new test); core compiles clean (`-Wunused` happy — `ZoneId`/`Clock` used).

- [ ] **Step 8: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/providers/CodexConnector.scala modules/llm4zio-core/src/main/scala/llm4zio/providers/GeminiCliProvider.scala modules/llm4zio-core/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala modules/llm4zio-core/src/test/scala/llm4zio/providers/CodexConnectorSpec.scala
git commit -m "feat(providers): classify usage-limit errors into UsageLimitError/RateLimitError"
```

---

### Task 4: `UsageLimitPolicy`

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitPolicy.scala`
- Test: covered by Task 5 (the decorator spec exercises the policy).

- [ ] **Step 1: Create the policy** (no standalone test — it's a plain config; Task 5 exercises it):

```scala
package llm4zio.flow

import zio.*

/** Opt-in policy for waiting out a provider usage/credit cap.
  *
  * @param enabled      master switch (off ⇒ usage limits fail fast, but typed)
  * @param maxWait      ceiling on total wait before giving up and re-raising the UsageLimitError
  * @param pollInterval probe cadence when the reset time is unknown
  */
final case class UsageLimitPolicy(
  enabled: Boolean = false,
  maxWait: Duration = 4.hours,
  pollInterval: Duration = 2.minutes,
)

object UsageLimitPolicy:
  val off: UsageLimitPolicy     = UsageLimitPolicy(enabled = false)
  val patient: UsageLimitPolicy = UsageLimitPolicy(enabled = true)
```

- [ ] **Step 2: Verify it compiles**

Run: `sbt llm4zioFlow/compile`
Expected: success.

- [ ] **Step 3: Commit**

```bash
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitPolicy.scala
git commit -m "feat(flow): UsageLimitPolicy (off/patient presets)"
```

---

### Task 5: `UsageLimitAware` decorator

Wraps the **IO methods** (`executeStructured`, `executeStructuredWithUsage`, `executeWithTools`); streaming + `isAvailable` pass through.

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitAware.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/UsageLimitAwareSpec.scala`

- [ ] **Step 1: Write the failing test** — create `UsageLimitAwareSpec.scala`:

```scala
package llm4zio.flow

import java.time.Instant

import zio.*
import zio.json.JsonCodec
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object UsageLimitAwareSpec extends ZIOSpecDefault:

  /** A service whose executeStructured fails with the given errors (one per call) then succeeds with "ok". */
  final class ScriptedService(failures: Ref[List[LlmError]]) extends LlmService:
    def executeStream(p: String): Stream[LlmError, LlmChunk]                          = ZStream.empty
    def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = ZStream.empty
    def executeWithTools(p: String, t: List[AnyTool]): IO[LlmError, ToolCallResponse] = ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](p: String, s: JsonSchema): IO[LlmError, A]    =
      failures.modify {
        case head :: tail => (Some(head), tail)
        case Nil          => (None, Nil)
      }.flatMap {
        case Some(err) => ZIO.fail(err)
        case None      => ZIO.fromEither("\"ok\"".fromJson[A]).orDieWith(e => new RuntimeException(e))
      }
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private def usageErr(at: Instant) = LlmError.UsageLimitError(Some(at), "codex", "limit")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("UsageLimitAware")(
    test("waits until resetAt then retries (within cap)") {
      for
        events  <- FlowEvents.collecting
        now     <- Clock.instant
        fails   <- Ref.make(List[LlmError](usageErr(now.plusSeconds(7200)))) // 2h out
        svc      = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.patient)(using events)
        fiber   <- svc.executeStructured[String]("go", Json.Obj()).fork
        _       <- TestClock.adjust(2.hours + 1.minute)
        out     <- fiber.join
        emitted <- events.recorded
      yield assertTrue(out == "ok", emitted.exists { case FlowEvent.Info(m) => m.contains("usage limit"); case _ => false })
    },
    test("gives up (re-raises) when reset is beyond maxWait") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        fails  <- Ref.make(List[LlmError](usageErr(now.plusSeconds(5 * 3600)))) // 5h out, cap 4h
        svc     = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.patient)(using events)
        exit   <- svc.executeStructured[String]("go", Json.Obj()).exit
      yield assertTrue(exit.isFailure)
    },
    test("disabled policy fails fast") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        fails  <- Ref.make(List[LlmError](usageErr(now.plusSeconds(60))))
        svc     = UsageLimitAware(ScriptedService(fails), UsageLimitPolicy.off)(using events)
        exit   <- svc.executeStructured[String]("go", Json.Obj()).exit
      yield assertTrue(exit.isFailure)
    },
  ) @@ TestAspect.withLiveClock.@@(TestAspect.nonFlaky(1))
```

Note: `Json.Obj()` is `zio.json.ast.Json.Obj()` — add `import zio.json.ast.Json`. Remove the `withLiveClock` aspect — TestClock is required for `TestClock.adjust`; the suite uses the default test clock. (Final aspect line: just the suite, no `withLiveClock`.)

- [ ] **Step 2: Fix the test's clock aspect** — replace the trailing `) @@ TestAspect.withLiveClock.@@(TestAspect.nonFlaky(1))` with just `)`. The default ZIO test clock is what makes `TestClock.adjust` drive the sleeps.

- [ ] **Step 3: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.UsageLimitAwareSpec'`
Expected: FAIL — `not found: type UsageLimitAware`.

- [ ] **Step 4: Implement the decorator** — create `UsageLimitAware.scala`:

```scala
package llm4zio.flow

import zio.*
import zio.json.JsonCodec
import zio.stream.Stream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** Decorates an [[LlmService]] so its idempotent IO calls (structured output, tool calling) wait out a provider
  * usage/credit cap instead of failing — when `policy.enabled`. Streaming calls and `isAvailable` pass through
  * unchanged (the streaming coder is handled by [[withUsageLimitRetry]]).
  */
final class UsageLimitAware(underlying: LlmService, policy: UsageLimitPolicy)(using events: FlowEvents)
    extends LlmService:

  private val buffer = 30.seconds

  /** Retry `io` while it fails with a UsageLimitError, sleeping until the reset (or polling), capped at maxWait. */
  private def patient[A](io: IO[LlmError, A]): IO[LlmError, A] =
    def loop(waited: Duration): IO[LlmError, A] =
      io.catchSome {
        case err: LlmError.UsageLimitError if policy.enabled =>
          Clock.instant.flatMap { now =>
            val sleepFor = err.resetAt match
              case Some(at) =>
                val remaining = Duration.fromInterval(now, at)
                (if remaining.isNegative then Duration.Zero else remaining) + buffer
              case None     => policy.pollInterval
            if waited + sleepFor > policy.maxWait then ZIO.fail(err)
            else
              events.publish(FlowEvent.Info(notice(err, sleepFor))) *>
                ZIO.sleep(sleepFor) *>
                loop(waited + sleepFor)
          }
      }
    loop(Duration.Zero)

  private def notice(err: LlmError.UsageLimitError, sleepFor: Duration): String =
    val mins  = math.max(1, sleepFor.toMinutes)
    val until = err.resetAt.fold("")(at => s" until $at")
    s"⏳ usage limit (${err.provider}) — sleeping ${mins}m$until"

  def executeStream(prompt: String): Stream[LlmError, LlmChunk]                     = underlying.executeStream(prompt)
  def executeStreamWithHistory(m: List[Message]): Stream[LlmError, LlmChunk]        = underlying.executeStreamWithHistory(m)
  def isAvailable: UIO[Boolean]                                                     = underlying.isAvailable

  def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    patient(underlying.executeWithTools(prompt, tools))

  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    patient(underlying.executeStructured[A](prompt, schema))

  override def executeStructuredWithUsage[A: JsonCodec](
    prompt: String,
    schema: JsonSchema,
  ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
    patient(underlying.executeStructuredWithUsage[A](prompt, schema))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.UsageLimitAwareSpec'`
Expected: PASS (3 tests). The 2h `TestClock.adjust` unblocks the sleep deterministically.

- [ ] **Step 6: Commit**

```bash
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitAware.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/UsageLimitAwareSpec.scala
git commit -m "feat(flow): UsageLimitAware decorator (sleep-until-reset on idempotent calls)"
```

---

### Task 6: `FlowError.Llm` carries the typed cause

So the combinator can recognize a usage limit after the `LlmError` is mapped into `FlowError`.

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowError.scala`
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Drive.scala` (`liftErr`)
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowErrorSpec.scala`

- [ ] **Step 1: Write the failing test** — create `FlowErrorSpec.scala`:

```scala
package llm4zio.flow

import zio.test.*

import llm4zio.core.LlmError

object FlowErrorSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & TestEnvironment, Any] = suite("FlowError.Llm")(
    test("carries an optional typed LlmError cause; default None") {
      val plain = FlowError.Llm("boom")
      val caused = FlowError.Llm("boom", Some(LlmError.UsageLimitError(None, "codex", "limit")))
      assertTrue(
        plain.cause.isEmpty,
        plain.message == "boom",
        caused.cause.exists(_.isInstanceOf[LlmError.UsageLimitError]),
      )
    }
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowErrorSpec'`
Expected: FAIL — `Llm` has no `cause` member.

- [ ] **Step 3: Enrich `FlowError.Llm`** — in `FlowError.scala`, change the `Llm` case (add the import for `LlmError`):

```scala
import llm4zio.core.LlmError
```

```scala
  /** A wrapped failure from the underlying LLM service. `cause` carries the typed error when available. */
  final case class Llm(message: String, cause: Option[LlmError] = None) extends FlowError
```

- [ ] **Step 4: Thread the cause in `Drive.liftErr`** — in `Drive.scala`:

```scala
  private def liftErr(e: LlmError): FlowError = FlowError.Llm(e.message, Some(e))
```

- [ ] **Step 5: Run tests to verify they pass + flow still compiles**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowErrorSpec' llm4zioFlow/compile`
Expected: PASS; flow compiles (existing `FlowError.Llm(e.message)` call sites still compile via the defaulted `cause`).

- [ ] **Step 6: Commit**

```bash
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowError.scala modules/llm4zio-flow/src/main/scala/llm4zio/flow/Drive.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowErrorSpec.scala
git commit -m "feat(flow): FlowError.Llm carries optional typed LlmError cause"
```

---

### Task 7: `withUsageLimitRetry` combinator

Flow-level backstop: on a `FlowError.Llm(_, Some(UsageLimitError))`, sleep (until reset, capped) then re-enter the flow; bounded by a re-entry cap.

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitRetry.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/UsageLimitRetrySpec.scala`

- [ ] **Step 1: Write the failing test** — create `UsageLimitRetrySpec.scala`:

```scala
package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.LlmError

object UsageLimitRetrySpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("withUsageLimitRetry")(
    test("sleeps then re-enters the flow until it succeeds") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        calls  <- Ref.make(0)
        // First call fails with a usage limit 1h out; second call succeeds.
        flow    = calls.updateAndGet(_ + 1).flatMap(n =>
                    if n == 1 then ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(Some(now.plusSeconds(3600)), "codex", "limit"))))
                    else ZIO.succeed("done")
                  )
        fiber  <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.patient)(flow).fork
                  }
        _      <- TestClock.adjust(1.hour + 1.minute)
        out    <- fiber.join
        n      <- calls.get
      yield assertTrue(out == "done", n == 2)
    },
    test("disabled policy does not retry") {
      for
        events <- FlowEvents.collecting
        flow    = ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(None, "codex", "limit"))))
        exit   <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.off)(flow).exit
                  }
      yield assertTrue(exit.isFailure)
    },
    test("gives up after the re-entry cap") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        flow    = ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(Some(now.plusSeconds(60)), "codex", "limit"))))
        fiber  <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.patient, maxReentries = 3)(flow).fork
                  }
        _      <- TestClock.adjust(10.minutes)
        exit   <- fiber.join.exit
      yield assertTrue(exit.isFailure)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.UsageLimitRetrySpec'`
Expected: FAIL — `not found: withUsageLimitRetry`.

- [ ] **Step 3: Implement the combinator** — create `UsageLimitRetry.scala`:

```scala
package llm4zio.flow

import zio.*

import llm4zio.core.LlmError

/** Flow-level backstop for provider usage caps: when `flow` fails with a usage-limit (a `FlowError.Llm` carrying a
  * [[LlmError.UsageLimitError]]), sleep until the reset (capped at `policy.maxWait`) then re-enter `flow`. Bounded by
  * `maxReentries`. Intended for the streaming coder + interactive `Drive` paths, where in-place retry is impossible;
  * re-entry leans on `PlanStore`/session resumability to skip completed work.
  */
def withUsageLimitRetry[R, A](
  policy: UsageLimitPolicy,
  maxReentries: Int = 3,
)(flow: ZIO[R, FlowError, A])(using events: FlowEvents): ZIO[R, FlowError, A] =
  def loop(attempt: Int, waited: Duration): ZIO[R, FlowError, A] =
    flow.catchSome {
      case e @ FlowError.Llm(_, Some(u: LlmError.UsageLimitError)) if policy.enabled && attempt < maxReentries =>
        Clock.instant.flatMap { now =>
          val sleepFor = u.resetAt match
            case Some(at) =>
              val remaining = Duration.fromInterval(now, at)
              (if remaining.isNegative then Duration.Zero else remaining) + 30.seconds
            case None     => policy.pollInterval
          if waited + sleepFor > policy.maxWait then ZIO.fail(e)
          else
            events.publish(FlowEvent.Info(s"⏳ usage limit (${u.provider}) — sleeping ${math.max(1, sleepFor.toMinutes)}m, re-entering")) *>
              ZIO.sleep(sleepFor) *>
              loop(attempt + 1, waited + sleepFor)
        }
    }
  loop(0, Duration.Zero)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.UsageLimitRetrySpec'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/UsageLimitRetry.scala modules/llm4zio-flow/src/test/scala/llm4zio/flow/UsageLimitRetrySpec.scala
git commit -m "feat(flow): withUsageLimitRetry combinator (sleep-and-reenter backstop)"
```

---

### Task 8: Runner wiring (opt-in via param + env)

Thread the policy through `DefaultFlowContext` (wrap services with `UsageLimitAware`) and `Llm4zio.run` (param + `LLM4ZIO_USAGE_WAIT` env + wrap the body with `withUsageLimitRetry`).

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/UsageWaitEnv.scala`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/UsageWaitEnvSpec.scala`

- [ ] **Step 1: Write the failing test** for the env parser — create `UsageWaitEnvSpec.scala`:

```scala
package llm4zio.runner

import zio.*
import zio.test.*

import llm4zio.flow.UsageLimitPolicy

object UsageWaitEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & TestEnvironment, Any] = suite("UsageWaitEnv.parse")(
    test("unset or 'off' → off") {
      assertTrue(UsageWaitEnv.parse(None) == UsageLimitPolicy.off, UsageWaitEnv.parse(Some("off")) == UsageLimitPolicy.off)
    },
    test("'4h' → enabled with a 4h cap") {
      val p = UsageWaitEnv.parse(Some("4h"))
      assertTrue(p.enabled, p.maxWait == 4.hours)
    },
    test("'90m' → enabled with a 90m cap") {
      assertTrue(UsageWaitEnv.parse(Some("90m")).maxWait == 90.minutes)
    },
    test("bare 'on'/'true' → patient default") {
      assertTrue(UsageWaitEnv.parse(Some("on")) == UsageLimitPolicy.patient)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.UsageWaitEnvSpec'`
Expected: FAIL — `not found: UsageWaitEnv`.

- [ ] **Step 3: Implement the env parser** — create `UsageWaitEnv.scala`:

```scala
package llm4zio.runner

import zio.*

import llm4zio.flow.UsageLimitPolicy

/** Parse the `LLM4ZIO_USAGE_WAIT` env value into a [[UsageLimitPolicy]]: `off`/unset disables; `on`/`true` enables
  * with the default cap; `<n>h` / `<n>m` enables with that cap.
  */
object UsageWaitEnv:
  private val hours = """(?i)(\d+)h""".r
  private val mins  = """(?i)(\d+)m""".r

  def parse(value: Option[String]): UsageLimitPolicy =
    value.map(_.trim.toLowerCase) match
      case None | Some("") | Some("off") | Some("false") => UsageLimitPolicy.off
      case Some("on") | Some("true")                     => UsageLimitPolicy.patient
      case Some(hours(n))                                => UsageLimitPolicy.patient.copy(maxWait = n.toInt.hours)
      case Some(mins(n))                                 => UsageLimitPolicy.patient.copy(maxWait = n.toInt.minutes)
      case Some(_)                                       => UsageLimitPolicy.patient
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.UsageWaitEnvSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Thread the policy through `DefaultFlowContext`** — in `DefaultFlowContext.scala`, give `make`/`build` a policy param and wrap each service. Change the `make` signature and `tap`:

```scala
  def make(
    reasoning: LlmService,
    coder: LlmService,
    workDir: Path,
    reviewers: List[LlmService] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  ): UIO[(FlowContext, FlowEvents.Hub)] =
    FlowEvents.hub().map { hub =>
      given FlowEvents = hub
      def tap(svc: LlmService, agent: String): LlmService =
        val tapped = EventTappingService(svc, agent, hub, workDir)
        if usageLimit.enabled then UsageLimitAware(tapped, usageLimit) else tapped
      val reasoningT = tap(reasoning, "reasoning")
      val coderT     = tap(coder, "coder")
      val reviewersT = reviewers.zipWithIndex.map { case (r, i) => tap(r, s"reviewer:${i + 1}") }
      (FlowContext(reasoningT, coderT, GitTool(workDir), GhTool(workDir), hub, reviewersT), hub)
    }
```

And `build` forwards `usageLimit`:

```scala
  def build(
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    workDir: Path,
    reviewerCfgs: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  ): ZIO[HttpClient, LlmError, (FlowContext, FlowEvents.Hub)] =
    ZIO.serviceWithZIO[HttpClient] { http =>
      val registry = ConnectorFactories.createRegistry(http, LiveCliProcessExecutor.instance)
      for
        reasoningC <- registry.resolve(prepare(reasoning, workDir))
        coderC     <- registry.resolveCli(coder.copy(workingDir = Some(workDir.toString)))
        reviewers  <- ZIO.foreach(reviewerCfgs)(cfg => registry.resolve(prepare(cfg, workDir)))
        bundle     <- make(reasoningC, coderC, workDir, reviewers, usageLimit)
      yield bundle
    }
```

(`UsageLimitAware`/`UsageLimitPolicy` come from `llm4zio.flow.*`, already imported.)

- [ ] **Step 6: Wire `Llm4zio.run`** — in `Llm4zio.scala`, add the param + env read + body wrap. Change the `run` signature:

```scala
  def run(
    workDir: Path,
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  )(
    body: FlowContext => ZIO[Any, Any, Any]
  ): ZIO[Any, Throwable, Unit] =
    val policy = if usageLimit.enabled then usageLimit else UsageWaitEnv.parse(sys.env.get("LLM4ZIO_USAGE_WAIT"))
```

Then pass `policy` to `DefaultFlowContext.build(reasoning, coder, workDir, reviewers, policy)`, and wrap the body. The body returns `ZIO[Any, Any, Any]`; the combinator needs a `FlowError` channel, so wrap a refined view:

```scala
                       _         <- {
                                      given FlowEvents = hub
                                      withUsageLimitRetry(policy)(
                                        body(ctx).mapError {
                                          case fe: FlowError => fe
                                          case other         => FlowError.Llm(other.toString)
                                        }
                                      ).unit
                                        .ensuring(tracker.summary.flatMap(s => surface.log("\n" + s)))
                                    }
```

(Add `import llm4zio.flow.{ UsageLimitPolicy, withUsageLimitRetry }` if not covered by an existing `llm4zio.flow.*` import; `FlowError`/`FlowEvents` already in scope.)

- [ ] **Step 7: Run the runner suite + compile**

Run: `sbt llm4zioRunner/compile 'llm4zioRunner/testOnly llm4zio.runner.UsageWaitEnvSpec'`
Expected: PASS; runner compiles clean.

- [ ] **Step 8: Commit**

```bash
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/UsageWaitEnv.scala modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala modules/llm4zio-runner/src/test/scala/llm4zio/runner/UsageWaitEnvSpec.scala
git commit -m "feat(runner): opt-in usage-limit waiting (Llm4zio.run param + LLM4ZIO_USAGE_WAIT env)"
```

---

### Task 9: Full verification, docs, format

**Files:**
- Modify: `CHANGELOG.md`
- (no new code)

- [ ] **Step 1: Format + lint**

Run: `sbt fmt`
Expected: EXIT 0 (scalafix + scalafmt clean).

- [ ] **Step 2: Full unit + integration suite**

Run: `sbt test 'llm4zioFlow/It/test' 'llm4zioRunner/It/test'`
Expected: all green (existing suite + the new ErrorsSpec/UsageLimitsSpec/CodexConnectorSpec/UsageLimitAwareSpec/FlowErrorSpec/UsageLimitRetrySpec/UsageWaitEnvSpec tests).

- [ ] **Step 3: Add CHANGELOG entry** — insert under the header in `CHANGELOG.md`:

```markdown
## [2.7.0] - <DATE>

### Added

- **Usage-limit handling.** A typed `LlmError.UsageLimitError(resetAt, provider, message)` (distinct from the
  transient `RateLimitError`), produced by a per-provider `UsageLimits.classify` matcher (codex/claude wall-clock
  caps → `UsageLimitError`; gemini's short "reset after Ns" → `RateLimitError`). Opt-in "patient mode"
  (`UsageLimitPolicy`, via `Llm4zio.run(usageLimit = …)` or `LLM4ZIO_USAGE_WAIT=4h`) makes a flow **sleep until the
  reset and continue** instead of failing: the `UsageLimitAware` decorator waits in place on idempotent
  planning/review/tool calls, and the `withUsageLimitRetry` combinator sleeps-and-re-enters for the streaming coder
  and interactive `Drive`. Bounded by a `maxWait` cap (default 4h). Off by default (usage limits then fail fast, but
  with the typed error).

### Changed

- `FlowError.Llm` gains an optional `cause: Option[LlmError]` (defaulted) so the typed error survives into the flow layer.

[2.7.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.0
```

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: 2.7.0 CHANGELOG — usage-limit handling"
```

- [ ] **Step 5: Release (separate, user-authorized)** — merge `feat/usage-limit-handling` → `main`, tag `v2.7.0`, push (CI publishes to Maven Central). Bump example pins to 2.7.0 if any example opts into patient mode. **Do not push without explicit user authorization.**

---

## Self-Review

**Spec coverage:** ADT (T1) ✓; per-provider `classify` (T2) + wiring (T3) ✓; `UsageLimitPolicy` (T4) ✓; `UsageLimitAware` decorator (T5) ✓; `FlowError.Llm` cause (T6) ✓; `withUsageLimitRetry` combinator (T7) ✓; opt-in param + env + auto-wiring (T8) ✓; events via `FlowEvent.Info` (T5/T7) ✓; edge cases — cap (T5/T7), past-reset (T5/T7 `isNegative`→Zero), interruption (inherent to `ZIO.sleep`), re-entry cap (T7) ✓; tests via `TestClock` ✓; CHANGELOG (T9) ✓. **Refinement noted:** decorator covers IO methods only (not streams) — the spec's "all methods" is realized as the idempotent/non-idempotent split (decorator + combinator), documented in the header.

**Placeholder scan:** `<DATE>` in T9 CHANGELOG is the only intentional fill-in (release date), flagged. No other TBD/TODO; every code step has complete code.

**Type consistency:** `UsageLimitError(resetAt: Option[Instant], provider: String, message: String)` used identically across T1/T2/T3/T5/T6/T7. `UsageLimitPolicy(enabled, maxWait, pollInterval)` + `.off`/`.patient` consistent T4/T5/T7/T8. `withUsageLimitRetry(policy, maxReentries=3)(flow)(using FlowEvents)` matches T7 def and T8 call. `FlowError.Llm(message, cause=None)` matches T6 def and T7/T8 matches. `UsageLimitAware(svc, policy)(using FlowEvents)` matches T5 def and T8 call.
