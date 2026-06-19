# Flow Trace Recorder + Raw Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an always-on, per-run JSONL flight recorder that captures every `FlowEvent` plus the raw Gemini provider stream — including the no-chunk empty-stream failure — and split a flaky-stream retry class out of the transient bucket so the pipeline stops dying after 3 retries.

**Architecture:** A tiny `StreamRecorder` hook in core (an ambient `FiberRef`, default no-op) lets core providers emit raw lines / stream errors without depending on flow. A `FlowRecorder` in flow implements that hook *and* subscribes to the existing `FlowEvents.Hub`, serializing both channels to `.llm4zio/trace-<runId>.jsonl` under a single semaphore (monotonic `seq`, never fails the flow). `Llm4zio.run` installs it alongside `TerminalListener`. The retry quick-win adds a second, independently-budgeted counter to `TransientRetry`.

**Tech Stack:** Scala 3, ZIO 2.1.25, zio-streams, zio-json, zio-process, ZIO Test. sbt 2.x.

## Global Constraints

- **ZIO-native throughout.** No `Future`, no blocking-by-default. Wrap blocking filesystem work in `ZIO.attemptBlocking`.
- **Typed errors.** Core uses `LlmError`; flow uses `FlowError`. The recorder itself must surface **no** error channel — all recorder ops return `UIO`.
- **The recorder never fails the flow.** File-I/O errors degrade to no-op + one `ZIO.logWarning`. Hard invariant.
- **No `var`** — use `Ref`/`Semaphore`/`FiberRef`.
- **`-Werror` / `-Wunused:all`** — unused imports are fatal. A wildcard `import zio.*` brings `zio.Task`, which shadows `flow.Task` in *type* position; none of the new files name `Task`, so `import zio.*` is safe in them — but do not add it to files that reference `flow.Task`.
- **Dependency direction:** `runner → flow → core`. Never the reverse. `StreamRecorder` (the interface) therefore lives in **core**; `FlowRecorder` (the impl) lives in **flow**.
- **Build commands:** `sbt llm4zioCore/test`, `sbt llm4zioFlow/test`, `sbt 'llm4zioFlow/testOnly llm4zio.flow.FooSpec'`. sbt 2's `test` is incremental — use `testFull` to force all. `sbt fmt` before committing.
- **Default values (env-overridable):** trace retention `LLM4ZIO_TRACE_KEEP=20`; flaky-stream retries `LLM4ZIO_FLAKY_RETRIES=6`.
- **Trace file location:** `<workDir>/.llm4zio/trace-<runId>.jsonl`, sibling to `PlanStore`'s `.llm4zio/plan-*.md`.

---

## File Structure

**Create:**
- `modules/llm4zio-core/src/main/scala/llm4zio/observability/StreamRecorder.scala` — the hook trait, `noop`, and the ambient `FiberRef` (Task 1).
- `modules/llm4zio-core/src/test/scala/llm4zio/observability/StreamRecorderSpec.scala` (Task 1).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala` — `TraceEvent` ADT, `TraceLine` JSON record, `runId`/`prune` helpers (Tasks 2, 4).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala` (Tasks 2, 4).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala` — the recorder engine + `install` (Tasks 3, 7).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala` (Tasks 3, 7).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/FlakyRetryEnv.scala` (Task 8).
- `modules/llm4zio-runner/src/test/scala/llm4zio/runner/FlakyRetryEnvSpec.scala` (Task 8).

**Modify:**
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala` — flaky-stream class split (Task 5).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala` — new cases (Task 5).
- `modules/llm4zio-core/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` — two recorder taps (Task 6).
- `modules/llm4zio-core/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala` — capture cases (Task 6).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala` — thread `flakyRetries` (Task 8).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala` — install the recorder (Task 8).

---

### Task 1: `StreamRecorder` core hook

The boundary-crossing interface: core providers emit to it; flow implements it. An ambient `FiberRef` (default `noop`) carries the live recorder without changing any provider/factory signatures.

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/observability/StreamRecorder.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/observability/StreamRecorderSpec.scala`

**Interfaces:**
- Produces:
  - `trait StreamRecorder { def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]; def streamError(provider: String, model: Option[String], message: String): UIO[Unit] }`
  - `StreamRecorder.noop: StreamRecorder`
  - `StreamRecorder.current: FiberRef[StreamRecorder]` (ambient, default `noop`)

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.observability

import zio.*
import zio.test.*

object StreamRecorderSpec extends ZIOSpecDefault:
  def spec = suite("StreamRecorder")(
    test("default ambient recorder is the no-op") {
      for rec <- StreamRecorder.current.get
      yield assertTrue(rec eq StreamRecorder.noop)
    },
    test("no-op methods succeed and do nothing") {
      for
        _ <- StreamRecorder.noop.rawLine("gemini-cli", Some("m"), "x")
        _ <- StreamRecorder.noop.streamError("gemini-cli", None, "boom")
      yield assertCompletes
    },
    test("a locally-installed recorder is visible to current.get within the scope") {
      val probe = new StreamRecorder:
        def rawLine(p: String, m: Option[String], l: String): UIO[Unit]    = ZIO.unit
        def streamError(p: String, m: Option[String], s: String): UIO[Unit] = ZIO.unit
      ZIO.scoped {
        StreamRecorder.current.locallyScoped(probe) *>
          StreamRecorder.current.get.map(seen => assertTrue(seen eq probe))
      }
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.observability.StreamRecorderSpec'`
Expected: FAIL — `StreamRecorder` not found / does not compile.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.observability

import zio.*

/** A sink for low-level provider stream signals — raw output lines and stream errors. Defined in core so providers can
  * emit to it; the flow layer supplies the implementation (a JSONL flight recorder) and installs it via [[current]].
  *
  * The contract is deliberately `UIO`: recording must never fail or interrupt the work it observes.
  */
trait StreamRecorder:
  /** A single raw line read from a provider's stream, before any normalization into `LlmChunk`. */
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]

  /** A stream error surfaced by a provider (including the no-chunk empty-stream / malformed-tool-call case). */
  def streamError(provider: String, model: Option[String], message: String): UIO[Unit]

object StreamRecorder:
  /** Discards everything. The ambient default, so providers that run outside a flow record nothing. */
  val noop: StreamRecorder = new StreamRecorder:
    def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]     = ZIO.unit
    def streamError(provider: String, model: Option[String], message: String): UIO[Unit] = ZIO.unit

  /** Ambient recorder for the current fiber and its children. The runner installs a live recorder for the duration of a
    * flow via `current.locallyScoped(recorder)`; providers read it with `current.get`. A top-level `FiberRef` is the
    * ZIO-idiomatic way to thread cross-cutting context without touching every constructor.
    */
  val current: FiberRef[StreamRecorder] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(noop))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.observability.StreamRecorderSpec'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-core/src/main/scala/llm4zio/observability/StreamRecorder.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/observability/StreamRecorderSpec.scala
git commit -m "feat(core): StreamRecorder hook for raw provider stream capture"
```

---

### Task 2: `TraceEvent` model + JSON serialization

The recorded data model: a superset of `FlowEvent` plus the low-level cases, and a flat `TraceLine` record that derives a zio-json encoder. Keeping serialization on a flat `Map[String, String]` payload avoids needing JSON codecs for `LlmError`/`TokenUsage`.

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala`

**Interfaces:**
- Consumes: `FlowEvent` (from `FlowEvents.scala`), `TokenUsage` (core).
- Produces:
  - `enum TraceEvent` with cases `FromFlow(event: FlowEvent)`, `RawLine(provider, model, line)`, `StreamError(provider, model, message)`, plus methods `kind: String` and `fields: Map[String, String]`.
  - `final case class TraceLine(seq: Long, ts: String, runId: String, kind: String, fields: Map[String, String])` with `def toJson: String` (zio-json).
  - `object TraceEvent { def fromFlow(e: FlowEvent): TraceEvent }`

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import zio.test.*

import llm4zio.core.TokenUsage

object FlowTraceSpec extends ZIOSpecDefault:
  def spec = suite("FlowTrace")(
    test("maps FlowEvent cases to a kind + fields") {
      val started = TraceEvent.fromFlow(FlowEvent.StageStarted("branch"))
      val tokens  = TraceEvent.fromFlow(FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), TokenUsage(10, 2, 12)))
      assertTrue(
        started.kind == "StageStarted",
        started.fields("stage") == "branch",
        tokens.kind == "TokensUsed",
        tokens.fields("agent") == "coder",
        tokens.fields("model") == "gemini-2.5-pro",
        tokens.fields("total") == "12",
      )
    },
    test("low-level RawLine and StreamError carry their payload") {
      val raw = TraceEvent.RawLine("gemini-cli", Some("gemini-2.5-pro"), """{"type":"error"}""")
      val err = TraceEvent.StreamError("gemini-cli", None, "Invalid stream: empty response")
      assertTrue(
        raw.kind == "RawLine",
        raw.fields("provider") == "gemini-cli",
        raw.fields("line") == """{"type":"error"}""",
        err.kind == "StreamError",
        err.fields("message") == "Invalid stream: empty response",
        !err.fields.contains("model"),
      )
    },
    test("TraceLine.toJson is one valid object with the envelope fields") {
      val line = TraceLine(7L, "2026-06-19T10:00:00Z", "rid", "Info", Map("message" -> "hi")).toJson
      assertTrue(
        line.startsWith("{") && line.endsWith("}"),
        line.contains("\"seq\":7"),
        line.contains("\"runId\":\"rid\""),
        line.contains("\"kind\":\"Info\""),
        line.contains("\"message\":\"hi\""),
        !line.contains("\n"),
      )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: FAIL — `TraceEvent`/`TraceLine` not found.

- [ ] **Step 3: Write minimal implementation**

Create `FlowTrace.scala` with the model and serialization (the `runId`/`prune` helpers come in Task 4 — leave the `object FlowTrace` stub ready):

```scala
package llm4zio.flow

import zio.json.*

/** One recorded event: either a high-level [[FlowEvent]] lifted into the trace, or a low-level provider signal. */
enum TraceEvent:
  case FromFlow(event: FlowEvent)
  case RawLine(provider: String, model: Option[String], line: String)
  case StreamError(provider: String, model: Option[String], message: String)

  def kind: String = this match
    case FromFlow(e)            => TraceEvent.flowKind(e)
    case _: RawLine             => "RawLine"
    case _: StreamError         => "StreamError"

  def fields: Map[String, String] = this match
    case FromFlow(e)                          => TraceEvent.flowFields(e)
    case RawLine(provider, model, line)       =>
      Map("provider" -> provider, "line" -> line) ++ model.map("model" -> _)
    case StreamError(provider, model, message) =>
      Map("provider" -> provider, "message" -> message) ++ model.map("model" -> _)

object TraceEvent:
  def fromFlow(e: FlowEvent): TraceEvent = FromFlow(e)

  private def flowKind(e: FlowEvent): String = e match
    case _: FlowEvent.StageStarted     => "StageStarted"
    case _: FlowEvent.StageCompleted   => "StageCompleted"
    case _: FlowEvent.StageFailed      => "StageFailed"
    case _: FlowEvent.Aborted          => "Aborted"
    case _: FlowEvent.Info             => "Info"
    case _: FlowEvent.ToolUse          => "ToolUse"
    case _: FlowEvent.AssistantMessage => "AssistantMessage"
    case _: FlowEvent.TokensUsed       => "TokensUsed"

  private def flowFields(e: FlowEvent): Map[String, String] = e match
    case FlowEvent.StageStarted(stage)        => Map("stage" -> stage)
    case FlowEvent.StageCompleted(stage)      => Map("stage" -> stage)
    case FlowEvent.StageFailed(stage, msg)    => Map("stage" -> stage, "message" -> msg)
    case FlowEvent.Aborted(message)           => Map("message" -> message)
    case FlowEvent.Info(message)              => Map("message" -> message)
    case FlowEvent.ToolUse(tool, args)        => Map("tool" -> tool, "args" -> args)
    case FlowEvent.AssistantMessage(text)     => Map("text" -> text)
    case FlowEvent.TokensUsed(agent, model, usage) =>
      Map(
        "agent"      -> agent,
        "prompt"     -> usage.prompt.toString,
        "completion" -> usage.completion.toString,
        "total"      -> usage.total.toString,
      ) ++ model.map("model" -> _)

/** The on-disk shape of one trace line. Flat by design so zio-json can derive an encoder without codecs for
  * `LlmError`/`TokenUsage`.
  */
final case class TraceLine(
  seq: Long,
  ts: String,
  runId: String,
  kind: String,
  fields: Map[String, String],
) derives JsonEncoder:
  def toJson: String = JsonEncoder[TraceLine].encodeJson(this, None).toString

object FlowTrace:
  // runId + prune land here in Task 4.
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala
git commit -m "feat(flow): TraceEvent model + TraceLine JSONL serialization"
```

---

### Task 3: `FlowRecorder` engine

Implements `StreamRecorder` (low-level) and records `FlowEvent`s (high-level), serializing both to a JSONL file under one `Semaphore` so `seq` order equals write order. Never fails: file errors flip a `degraded` flag and log once.

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala`

**Interfaces:**
- Consumes: `StreamRecorder` (core), `TraceEvent`/`TraceLine` (Task 2), `FlowEvent`/`FlowEvents.Hub` (existing).
- Produces:
  - `final class FlowRecorder extends StreamRecorder` with `def record(event: FlowEvent): UIO[Unit]` and `def consume(hub: FlowEvents.Hub): ZIO[Scope, Nothing, Unit]`.
  - `object FlowRecorder { def open(path: java.nio.file.Path, runId: String): UIO[FlowRecorder] }`

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage

object FlowRecorderSpec extends ZIOSpecDefault:

  private def linesOf(p: Path): List[String] =
    Files.readAllLines(p).asScala.toList.filter(_.nonEmpty)

  def spec = suite("FlowRecorder")(
    test("serializes high-level and low-level events to JSONL in monotonic seq order") {
      ZIO.scoped {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          file = dir.resolve("trace-rid.jsonl")
          rec <- FlowRecorder.open(file, "rid")
          _   <- rec.record(FlowEvent.StageStarted("branch"))
          _   <- rec.rawLine("gemini-cli", Some("gemini-2.5-pro"), """{"type":"error"}""")
          _   <- rec.streamError("gemini-cli", None, "Invalid stream: empty response")
          _   <- rec.record(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3)))
          ls  <- ZIO.attemptBlocking(linesOf(file)).orDie
        yield assertTrue(
          ls.length == 4,
          ls.head.contains("\"seq\":0") && ls.head.contains("\"kind\":\"StageStarted\""),
          ls(1).contains("\"seq\":1") && ls(1).contains("\"kind\":\"RawLine\""),
          ls(2).contains("\"seq\":2") && ls(2).contains("Invalid stream"),
          ls(3).contains("\"seq\":3") && ls(3).contains("\"kind\":\"TokensUsed\""),
        )
      }
    },
    test("never fails when the file cannot be written (degrades silently)") {
      ZIO.scoped {
        for
          dir   <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          // A path whose parent is a regular file ⇒ writes fail. The recorder must still succeed.
          clash  = dir.resolve("afile")
          _     <- ZIO.attemptBlocking(Files.writeString(clash, "x")).orDie
          file   = clash.resolve("trace-rid.jsonl")
          rec   <- FlowRecorder.open(file, "rid")
          _     <- rec.record(FlowEvent.Info("noise"))
          _     <- rec.rawLine("gemini-cli", None, "x")
        yield assertCompletes
      }
    },
    test("consume(hub) records events published to the hub") {
      ZIO.scoped {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          file = dir.resolve("trace-rid.jsonl")
          rec <- FlowRecorder.open(file, "rid")
          hub <- FlowEvents.hub()
          _   <- rec.consume(hub)
          _   <- hub.publish(FlowEvent.StageStarted("design"))
          _   <- hub.publish(FlowEvent.StageCompleted("design"))
          // give the forked subscriber a moment to drain
          _   <- ZIO.sleep(50.millis)
          ls  <- ZIO.attemptBlocking(linesOf(file)).orDie
        yield assertTrue(ls.exists(_.contains("\"design\"")), ls.length == 2)
      }
    } @@ TestAspect.withLiveClock,
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: FAIL — `FlowRecorder` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import zio.*

import llm4zio.observability.StreamRecorder

/** A per-run flight recorder. Serializes high-level [[FlowEvent]]s (via [[consume]]/[[record]]) and low-level provider
  * signals (via the [[StreamRecorder]] interface) to a single JSONL file. A [[Semaphore]] makes `seq` order equal write
  * order across the concurrent hub subscriber and provider fibers. Writing never fails the flow: an I/O error flips
  * `degraded` and logs once.
  */
final class FlowRecorder private (
  path: Path,
  runId: String,
  seq: Ref[Long],
  lock: Semaphore,
  degraded: Ref[Boolean],
) extends StreamRecorder:

  private def append(event: TraceEvent): UIO[Unit] =
    lock.withPermit {
      degraded.get.flatMap {
        case true  => ZIO.unit
        case false =>
          for
            n  <- seq.getAndUpdate(_ + 1)
            ts <- Clock.instant
            ln  = TraceLine(n, ts.toString, runId, event.kind, event.fields).toJson
            _  <- ZIO
                    .attemptBlocking {
                      Files.write(
                        path,
                        (ln + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                      )
                      ()
                    }
                    .catchAll(e =>
                      ZIO.logWarning(s"flow trace disabled (write failed: ${e.getMessage})") *> degraded.set(true)
                    )
          yield ()
      }
    }

  def record(event: FlowEvent): UIO[Unit]                                            = append(TraceEvent.fromFlow(event))
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]      =
    append(TraceEvent.RawLine(provider, model, line))
  def streamError(provider: String, model: Option[String], message: String): UIO[Unit] =
    append(TraceEvent.StreamError(provider, model, message))

  /** Fork a subscriber that records every event from `hub` until the scope closes (mirrors [[CostTracker.consume]]). */
  def consume(hub: FlowEvents.Hub): ZIO[Scope, Nothing, Unit] =
    hub.stream.foreach(record).forkScoped.unit

object FlowRecorder:
  /** Open a recorder for `path`. Creating the parent directory is best-effort: if it fails, the recorder starts already
    * degraded (records nothing) rather than failing. Returns a `UIO` — opening a flight recorder must never break a run.
    */
  def open(path: Path, runId: String): UIO[FlowRecorder] =
    for
      seq      <- Ref.make(0L)
      lock     <- Semaphore.make(1)
      created  <- ZIO.attemptBlocking(Option(path.getParent).foreach(Files.createDirectories(_))).either
      degraded <- Ref.make(created.isLeft)
    yield new FlowRecorder(path, runId, seq, lock, degraded)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala
git commit -m "feat(flow): FlowRecorder — JSONL flight recorder over FlowEvents + StreamRecorder"
```

---

### Task 4: `FlowTrace.runId` + `FlowTrace.prune`

The run identifier (timestamp-based) and bounded retention. Both never fail.

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala` (fill the `object FlowTrace` stub)
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala` (add cases)

**Interfaces:**
- Produces:
  - `FlowTrace.runId: UIO[String]` — `yyyyMMdd-HHmmss-SSS` in the system zone.
  - `FlowTrace.prune(dir: java.nio.file.Path, keep: Int): UIO[Unit]` — keep the newest `keep` `trace-*.jsonl` by mtime, delete the rest.

- [ ] **Step 1: Write the failing test (append to `FlowTraceSpec`)**

Add these to the existing `suite("FlowTrace")(...)` list:

```scala
    ,
    test("runId is a timestamp slug of the expected shape") {
      for id <- FlowTrace.runId
      yield assertTrue(id.matches("""\d{8}-\d{6}-\d{3}"""))
    },
    test("prune keeps the newest `keep` trace files and deletes older ones") {
      import java.nio.file.{ Files, attribute }
      for
        dir   <- ZIO.attemptBlocking(Files.createTempDirectory("prune-test")).orDie
        _     <- ZIO.attemptBlocking {
                   // three trace files + one unrelated file; stamp distinct mtimes
                   List("trace-a.jsonl" -> 1000L, "trace-b.jsonl" -> 2000L, "trace-c.jsonl" -> 3000L)
                     .foreach { case (name, ms) =>
                       val p = dir.resolve(name)
                       Files.writeString(p, "x")
                       Files.setLastModifiedTime(p, attribute.FileTime.fromMillis(ms))
                     }
                   Files.writeString(dir.resolve("keep-me.txt"), "x")
                 }.orDie
        _     <- FlowTrace.prune(dir, keep = 1)
        names <- ZIO.attemptBlocking {
                   import scala.jdk.CollectionConverters.*
                   Files.list(dir).iterator.asScala.map(_.getFileName.toString).toSet
                 }.orDie
      yield assertTrue(
        names.contains("trace-c.jsonl"),  // newest kept
        !names.contains("trace-a.jsonl"),
        !names.contains("trace-b.jsonl"),
        names.contains("keep-me.txt"),    // non-trace files untouched
      )
    }
```

Also add `import zio.*` to the top of `FlowTraceSpec.scala` (the new cases use `ZIO`).

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: FAIL — `FlowTrace.runId` / `FlowTrace.prune` not found.

- [ ] **Step 3: Write minimal implementation (replace the `object FlowTrace` stub)**

```scala
object FlowTrace:
  import java.nio.file.{ Files, Path }
  import java.time.ZoneId
  import java.time.format.DateTimeFormatter
  import scala.jdk.CollectionConverters.*

  import zio.*

  private val runIdFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault)

  /** A timestamp-based run id, unique per run at millisecond resolution. */
  val runId: UIO[String] = Clock.instant.map(runIdFormat.format)

  /** Keep the newest `keep` `trace-*.jsonl` files in `dir` by mtime; delete the rest. Best-effort: any I/O error is
    * swallowed (retention must never break a run).
    */
  def prune(dir: Path, keep: Int): UIO[Unit] =
    ZIO
      .attemptBlocking {
        if Files.isDirectory(dir) then
          val traces = Files
            .list(dir)
            .iterator
            .asScala
            .filter { p =>
              val n = p.getFileName.toString
              n.startsWith("trace-") && n.endsWith(".jsonl")
            }
            .toList
          traces
            .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
            .drop(math.max(0, keep))
            .foreach(Files.deleteIfExists(_))
      }
      .ignore
```

Note: the file-level `import zio.json.*` for `TraceLine` remains at the top; the `object FlowTrace` adds its own local imports as shown.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala
git commit -m "feat(flow): FlowTrace.runId + bounded prune retention"
```

---

### Task 5: `TransientRetry` flaky-stream class split (the quick-win)

Split `empty response` / `malformed tool call` / `invalid stream` into their own independently-budgeted class with a short fixed backoff (a fresh `gemini` process is cheap and almost always succeeds), separate from the exponential transient bucket.

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala`
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala`

**Interfaces:**
- Produces (changed constructor + new predicate):
  - `class TransientRetry(underlying, maxRetries = 3, baseDelay = 1.second, flakyRetries = 6, flakyDelay = 1.second)(using FlowEvents)`
  - `TransientRetry.isFlakyStream(e: LlmError): Boolean`
  - `TransientRetry.isTransient(e: LlmError): Boolean` (the three flaky signals are **removed** from its list)

- [ ] **Step 1: Write the failing test (add to `TransientRetrySpec`)**

```scala
    ,
    test("isFlakyStream matches empty-stream signals; isTransient no longer does") {
      val flaky = LlmError.ProviderError("Gemini CLI stream error: Invalid stream: empty response", None)
      assertTrue(
        TransientRetry.isFlakyStream(flaky),
        !TransientRetry.isTransient(flaky),
        !TransientRetry.isFlakyStream(LlmError.ProviderError("connection reset", None)),
        TransientRetry.isTransient(LlmError.ProviderError("connection reset", None)),
      )
    },
    test("a flaky stream is retried on its own budget, independent of maxRetries") {
      // maxRetries = 0 (transient budget exhausted) but flakyRetries = 2 ⇒ two flaky retries still happen.
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        calls  <- Ref.make(0)
        svc     = new TransientRetrySpec.CountingStream(calls, failWith =
                    LlmError.ProviderError("Gemini CLI stream error: Invalid stream: empty response", None),
                    failTimes = 2)
        rt      = TransientRetry(svc, maxRetries = 0, flakyRetries = 2, flakyDelay = zio.Duration.Zero)
        out    <- rt.executeStream("p").runCollect
        n      <- calls.get
      yield assertTrue(out.map(_.delta).mkString == "ok", n == 3) // 2 failures + 1 success
    },
    test("a flaky stream past its budget fails") {
      for
        events <- FlowEvents.collecting
        given FlowEvents = events
        calls  <- Ref.make(0)
        svc     = new TransientRetrySpec.CountingStream(calls, failWith =
                    LlmError.ProviderError("Invalid stream: empty response", None),
                    failTimes = 5)
        rt      = TransientRetry(svc, maxRetries = 0, flakyRetries = 2, flakyDelay = zio.Duration.Zero)
        exit   <- rt.executeStream("p").runCollect.exit
      yield assertTrue(exit.isFailure)
    }
```

Add this test helper to the `object TransientRetrySpec` body (a minimal stateful `LlmService`):

```scala
  // A stream that fails `failTimes` times with `failWith`, then emits a single "ok" chunk. Counts attempts in `calls`.
  final class CountingStream(calls: Ref[Int], failWith: LlmError, failTimes: Int) extends LlmService:
    import zio.stream.{ Stream, ZStream }
    def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
      ZStream.unwrap(calls.updateAndGet(_ + 1).map { n =>
        if n <= failTimes then ZStream.fail(failWith)
        else ZStream.succeed(LlmChunk(delta = "ok"))
      })
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] = executeStream("")
    def executeWithTools(prompt: String, tools: List[llm4zio.tools.AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: llm4zio.tools.JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("n/a"))
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)
```

Ensure `TransientRetrySpec.scala` imports cover these: `import zio.*`, `import llm4zio.core.*`, `import zio.test.*` (check existing header and add only what's missing — `-Wunused:all` forbids redundant imports).

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TransientRetrySpec'`
Expected: FAIL — `isFlakyStream` not found; `flakyRetries` param not found.

- [ ] **Step 3: Write minimal implementation (edit `TransientRetry.scala`)**

Replace the class header, the retry loops, and add the predicate. New class header and loops:

```scala
final class TransientRetry(
  underlying: LlmService,
  maxRetries: Int = 3,
  baseDelay: Duration = 1.second,
  flakyRetries: Int = 6,
  flakyDelay: Duration = 1.second,
)(using events: FlowEvents
) extends LlmService:

  private def backoff(attempt: Int): Duration = baseDelay * math.pow(2, attempt.toDouble)

  private def notice(what: String, attempt: Int, max: Int, e: LlmError): UIO[Unit] =
    events.publish(FlowEvent.Info(s"⟳ $what — retry ${attempt + 1}/$max: ${e.message}"))

  private def retryIO[A](what: String)(io: IO[LlmError, A]): IO[LlmError, A] =
    def loop(tN: Int, fN: Int): IO[LlmError, A] =
      io.catchSome {
        case e if TransientRetry.isFlakyStream(e) && fN < flakyRetries =>
          notice(s"flaky $what (fresh retry)", fN, flakyRetries, e) *> ZIO.sleep(flakyDelay) *> loop(tN, fN + 1)
        case e if TransientRetry.isTransient(e) && tN < maxRetries     =>
          notice(s"transient $what", tN, maxRetries, e) *> ZIO.sleep(backoff(tN)) *> loop(tN + 1, fN)
      }
    loop(0, 0)

  private def retryStream(what: String)(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, LlmChunk] =
    def loop(tN: Int, fN: Int): Stream[LlmError, LlmChunk] =
      stream.catchSome {
        case e if TransientRetry.isFlakyStream(e) && fN < flakyRetries =>
          ZStream.fromZIO(notice(s"flaky $what (fresh retry)", fN, flakyRetries, e) *> ZIO.sleep(flakyDelay)).drain ++
            loop(tN, fN + 1)
        case e if TransientRetry.isTransient(e) && tN < maxRetries     =>
          ZStream.fromZIO(notice(s"transient $what", tN, maxRetries, e) *> ZIO.sleep(backoff(tN))).drain ++
            loop(tN + 1, fN)
      }
    loop(0, 0)
```

In the companion `object TransientRetry`, add `isFlakyStream` and remove the three flaky signals from `isTransient`'s list:

```scala
  /** Flaky-stream class: gemini intermittently closes the stream with no candidates or a half-formed function call.
    * Non-deterministic; a fresh process (which a retried stream spawns) almost always succeeds, so this gets its own,
    * more generous budget with a short fixed backoff — distinct from rate-limit-flavoured transients.
    */
  def isFlakyStream(e: LlmError): Boolean = e match
    case LlmError.ProviderError(message, _) =>
      val m = message.toLowerCase
      List("empty response", "malformed tool call", "invalid stream").exists(m.contains)
    case _                                  => false
```

And delete these three lines from the `isTransient` `List(...)` (lines 92-94 in the original):

```scala
        "empty response",
        "malformed tool call",
        "invalid stream",
```

(Leave the surrounding comment lines or trim them — keep the `code=500`…`504` entries.)

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TransientRetrySpec'`
Expected: PASS (existing cases + 3 new).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/TransientRetrySpec.scala
git commit -m "feat(flow): split flaky-stream retry class with its own budget (quick-win)"
```

---

### Task 6: Gemini provider raw capture

Emit to the ambient `StreamRecorder` from the provider's stream tap: `rawLine` for unparseable `LogLine`s and `streamError` for `Error` / `Result(error)` events — the latter is the no-chunk empty-stream failure. Capturing at the provider (not the executor) keeps it unit-testable with a stub executor.

**Files:**
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Modify: `modules/llm4zio-core/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

**Interfaces:**
- Consumes: `StreamRecorder.current` (Task 1), `GeminiCliStreamEvent` (existing).

- [ ] **Step 1: Write the failing test (add to `GeminiCliProviderSpec`)**

This uses a stub `GeminiCliExecutor` yielding a fixed event stream, with a collecting `StreamRecorder` installed via `FiberRef.locallyScoped`.

```scala
    ,
    test("executeStream taps raw LogLines and the no-chunk empty-stream error into the StreamRecorder") {
      import llm4zio.observability.StreamRecorder
      import zio.stream.ZStream

      // A collecting recorder.
      final class Collecting(raw: Ref[Chunk[String]], errs: Ref[Chunk[String]]) extends StreamRecorder:
        def rawLine(p: String, m: Option[String], line: String): UIO[Unit]    = raw.update(_ :+ line)
        def streamError(p: String, m: Option[String], msg: String): UIO[Unit] = errs.update(_ :+ msg)

      // A stub executor: one unparseable log line, then an empty-stream error event (emits NO LlmChunk).
      val stubExecutor = new GeminiCliExecutor:
        def checkGeminiInstalled: IO[LlmError, Unit] = ZIO.unit
        def runGeminiProcess(prompt: String, config: LlmConfig, ctx: GeminiCliExecutionContext): IO[LlmError, String] =
          ZIO.succeed("")
        def runGeminiProcessStream(prompt: String, config: LlmConfig, ctx: GeminiCliExecutionContext)
          : ZStream[Any, LlmError, GeminiCliStreamEvent] =
          ZStream(
            GeminiCliStreamEvent.LogLine("{garbled json"),
            GeminiCliStreamEvent.Error(
              message = Some("Invalid stream: The model returned an empty response or malformed tool call"),
              code = None,
              errorType = None,
            ),
          )

      val provider = GeminiCliProvider.make(LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro"), stubExecutor)

      for
        raw  <- Ref.make(Chunk.empty[String])
        errs <- Ref.make(Chunk.empty[String])
        rec   = new Collecting(raw, errs)
        exit <- ZIO.scoped {
                  StreamRecorder.current.locallyScoped(rec) *>
                    provider.executeStream("hi").runCollect.exit
                }
        rs   <- raw.get
        es   <- errs.get
      yield assertTrue(
        exit.isFailure, // the empty-stream error still fails the stream as before
        rs.exists(_.contains("garbled json")),
        es.exists(_.contains("Invalid stream")),
      )
    }
```

Confirm the spec's imports include `zio.*`, `zio.stream.*` (or add the inline imports as shown) and `llm4zio.core.*`. Use the exact `LlmConfig` constructor the rest of the file uses for the provider (check an existing `GeminiCliProvider.make(...)` call in this spec and copy its `LlmConfig(...)` shape).

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.GeminiCliProviderSpec'`
Expected: FAIL — recorder collects nothing (taps not added yet).

- [ ] **Step 3: Write minimal implementation (edit `GeminiCliProvider.scala`)**

Add the import near the other imports at the top of the file:

```scala
import llm4zio.observability.StreamRecorder
```

In `executeStream`, immediately **after** the existing `.tap { ... }` block that ends at line ~460 (the debug-logging tap) and **before** the `.flatMap { ... }` at line ~461, insert a second tap that emits to the ambient recorder:

```scala
              .tap {
                case GeminiCliStreamEvent.LogLine(line) =>
                  StreamRecorder.current.get.flatMap(_.rawLine("gemini-cli", Some(config.model), line))
                case GeminiCliStreamEvent.Error(message, code, errorType) =>
                  val details = List(errorType.map(t => s"type=$t"), code.map(c => s"code=$c")).flatten.mkString(", ")
                  val msg     = message.getOrElse("unknown error")
                  val text    = if details.nonEmpty then s"$msg ($details)" else msg
                  StreamRecorder.current.get.flatMap(_.streamError("gemini-cli", Some(config.model), text))
                case GeminiCliStreamEvent.Result(status, errorMessage, _) if status.contains("error") =>
                  StreamRecorder.current.get.flatMap(
                    _.streamError("gemini-cli", Some(config.model), errorMessage.getOrElse("Gemini CLI returned an error"))
                  )
                case _ => ZIO.unit
              }
```

This leaves the existing `.flatMap` conversion (and the `ZStream.fail` error path) unchanged — the stream still fails exactly as before; we only observe it on the way through.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.providers.GeminiCliProviderSpec'`
Expected: PASS (existing cases + the new one).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-core/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat(core): tap Gemini raw lines + stream errors into StreamRecorder"
```

---

### Task 7: `FlowRecorder.install` — compose open + prune + consume + ambient install

A single flow-layer entry point the runner calls. Testable without HttpClient.

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala`
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala`

**Interfaces:**
- Produces: `FlowRecorder.install(hub: FlowEvents.Hub, dir: java.nio.file.Path, keep: Int): ZIO[Scope, Nothing, FlowRecorder]` — prunes old traces, opens `dir/trace-<runId>.jsonl`, subscribes to `hub`, installs itself as `StreamRecorder.current` for the scope, and returns the recorder.

- [ ] **Step 1: Write the failing test (add to `FlowRecorderSpec`)**

```scala
    ,
    test("install prunes, opens under .llm4zio naming, subscribes to the hub, and installs the ambient recorder") {
      import llm4zio.observability.StreamRecorder
      import scala.jdk.CollectionConverters.*
      ZIO.scoped {
        for
          dir   <- ZIO.attemptBlocking(Files.createTempDirectory("install-test")).orDie
          hub   <- FlowEvents.hub()
          rec   <- FlowRecorder.install(hub, dir, keep = 20)
          ambient <- StreamRecorder.current.get
          _     <- hub.publish(FlowEvent.StageStarted("specify"))
          _     <- ZIO.sleep(50.millis)
          files <- ZIO.attemptBlocking(Files.list(dir).iterator.asScala.map(_.getFileName.toString).toList).orDie
          // also exercise the ambient low-level channel
          _     <- ambient.rawLine("gemini-cli", None, "raw-x")
          _     <- ZIO.sleep(20.millis)
          trace  = files.find(n => n.startsWith("trace-") && n.endsWith(".jsonl")).get
          lines <- ZIO.attemptBlocking(linesOf(dir.resolve(trace))).orDie
        yield assertTrue(
          ambient eq rec,
          files.exists(n => n.startsWith("trace-") && n.endsWith(".jsonl")),
          lines.exists(_.contains("specify")),
          lines.exists(_.contains("raw-x")),
        )
      }
    } @@ TestAspect.withLiveClock
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: FAIL — `FlowRecorder.install` not found.

- [ ] **Step 3: Write minimal implementation (add to `object FlowRecorder`)**

```scala
  /** Prune old traces, open a fresh `trace-<runId>.jsonl` under `dir`, subscribe to `hub`, and install the recorder as
    * the ambient [[StreamRecorder]] for the current scope. Returns the recorder. Pure flow/core — no HTTP, so the runner
    * stays a thin caller.
    */
  def install(hub: FlowEvents.Hub, dir: java.nio.file.Path, keep: Int): ZIO[Scope, Nothing, FlowRecorder] =
    for
      _     <- FlowTrace.prune(dir, keep)
      runId <- FlowTrace.runId
      rec   <- open(dir.resolve(s"trace-$runId.jsonl"), runId)
      _     <- rec.consume(hub)
      _     <- llm4zio.observability.StreamRecorder.current.locallyScoped(rec)
    yield rec
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala
git commit -m "feat(flow): FlowRecorder.install — one-call recorder wiring"
```

---

### Task 8: Runner wiring + flaky-retry env

Install the recorder in `Llm4zio.run`, thread `flakyRetries` through `DefaultFlowContext`, and parse the new env vars.

**Files:**
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/FlakyRetryEnv.scala`
- Create: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/FlakyRetryEnvSpec.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`

**Interfaces:**
- Consumes: `FlowRecorder.install` (Task 7), `TransientRetry`'s `flakyRetries` (Task 5).
- Produces: `FlakyRetryEnv.default: Int` (6) and `FlakyRetryEnv.parse(value: Option[String]): Int`.

- [ ] **Step 1: Write the failing test (`FlakyRetryEnvSpec`)**

```scala
package llm4zio.runner

import zio.test.*

object FlakyRetryEnvSpec extends ZIOSpecDefault:
  def spec = suite("FlakyRetryEnv")(
    test("unset / blank / invalid → default (6)") {
      assertTrue(
        FlakyRetryEnv.parse(None) == 6,
        FlakyRetryEnv.parse(Some("  ")) == 6,
        FlakyRetryEnv.parse(Some("nope")) == 6,
        FlakyRetryEnv.parse(Some("-1")) == 6,
      )
    },
    test("a valid non-negative int is used") {
      assertTrue(FlakyRetryEnv.parse(Some("0")) == 0, FlakyRetryEnv.parse(Some("10")) == 10)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.FlakyRetryEnvSpec'`
Expected: FAIL — `FlakyRetryEnv` not found.

- [ ] **Step 3a: Write `FlakyRetryEnv`**

```scala
package llm4zio.runner

/** Parse `LLM4ZIO_FLAKY_RETRIES` into the flaky-stream retry budget for [[llm4zio.flow.TransientRetry]]: how many times
  * an intermittent empty-stream / malformed-tool-call failure is retried (each retry spawns a fresh process) before
  * failing. Unset/blank/invalid → [[default]] (6); `0` → fail fast; `<n>` (n ≥ 0) → that many.
  */
object FlakyRetryEnv:
  val default: Int = 6

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
```

- [ ] **Step 3b: Thread `flakyRetries` through `DefaultFlowContext`**

In `make`, add a parameter and pass it to `TransientRetry`:

```scala
  def make(
    reasoning: LlmService,
    coder: LlmService,
    workDir: Path,
    reviewers: List[LlmService] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    retries: Int = 3,
    flakyRetries: Int = 6,
    coderCapabilities: ConnectorCapabilities = ConnectorCapabilities(),
  ): UIO[(FlowContext, FlowEvents.Hub)] =
```

and change the `TransientRetry(svc, maxRetries = retries)` line to:

```scala
        val retried = TransientRetry(svc, maxRetries = retries, flakyRetries = flakyRetries)
```

In `build`, add `flakyRetries: Int = 6` to its signature and pass it through to `make`:

```scala
  def build(
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    workDir: Path,
    reviewerCfgs: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    retries: Int = 3,
    flakyRetries: Int = 6,
  ): ZIO[HttpClient, LlmError, (FlowContext, FlowEvents.Hub)] =
```

and update the `make(...)` call inside `build` — change the existing call:

```scala
        bundle     <- make(reasoningC, coderC, workDir, reviewers, usageLimit, retries, coderC.capabilities)
```

to:

```scala
        bundle     <- make(reasoningC, coderC, workDir, reviewers, usageLimit, retries, flakyRetries, coderC.capabilities)
```

- [ ] **Step 3c: Install the recorder + parse env in `Llm4zio.run`**

Add imports at the top of `Llm4zio.scala` if missing: `llm4zio.flow.*` is already imported (it brings `FlowRecorder`, `FlowTrace`). Confirm `FlowRecorder` resolves; if `flow.*` is already wildcard-imported, no change needed.

Inside `run`, parse both env values where `retries` is parsed (line ~65), so they pass into `build`:

```scala
                       retries      = RetryEnv.parse(sys.env.get("LLM4ZIO_RETRIES"))
                       flakyRetries = FlakyRetryEnv.parse(sys.env.get("LLM4ZIO_FLAKY_RETRIES"))
                       traceKeep    = sys.env.get("LLM4ZIO_TRACE_KEEP").flatMap(_.trim.toIntOption).filter(_ >= 0).getOrElse(20)
                       bundle      <- DefaultFlowContext.build(reasoning, coder, workDir, reviewers, policy, retries, flakyRetries)
```

Then, after the two existing hub subscribers are attached (`consumed <- TerminalListener.consumeTo(...)` and `_ <- tracker.consume(hub)`, line ~72-73), add the recorder install:

```scala
                       _         <- FlowRecorder.install(hub, workDir.resolve(".llm4zio"), traceKeep)
```

This sits inside the same `ZIO.scoped { ... }` block, so `install`'s `locallyScoped` is in scope for the `body(ctx)` call below it, and the forked subscriber tears down with the scope.

- [ ] **Step 4: Run tests**

```bash
sbt 'llm4zioRunner/testOnly llm4zio.runner.FlakyRetryEnvSpec'
sbt llm4zioRunner/test
```
Expected: `FlakyRetryEnvSpec` PASS (2 tests); runner module compiles and existing tests still pass.

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/FlakyRetryEnv.scala \
        modules/llm4zio-runner/src/test/scala/llm4zio/runner/FlakyRetryEnvSpec.scala \
        modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala \
        modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala
git commit -m "feat(runner): install FlowRecorder + LLM4ZIO_FLAKY_RETRIES / LLM4ZIO_TRACE_KEEP env"
```

---

### Task 9: Full-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit test run across all modules**

Run: `sbt testFull`
Expected: all modules PASS (sbt 2's plain `test` is incremental — `testFull` forces the full run CI uses).

- [ ] **Step 2: Format + lint check**

Run: `sbt check`
Expected: clean (scalafmt + scalafix; `-Werror`/`-Wunused:all` would fail the build on any stray import).

- [ ] **Step 3: Manual smoke (optional, requires a real gemini CLI)**

Run a flow example and confirm a trace appears:
```bash
ls -t .llm4zio/trace-*.jsonl | head -1   # newest trace file from the run
```
Expected: a `trace-<timestamp>.jsonl` exists; `grep StreamError .llm4zio/trace-*.jsonl` shows the captured raw Gemini error if a flake occurred.

- [ ] **Step 4: Commit any formatting fixups**

```bash
git add -A
git commit -m "chore: scalafmt + verification fixups for flow trace recorder" || echo "nothing to commit"
```

---

## Deviations from the spec (intentional)

- **`runId` is timestamp-only** (`yyyyMMdd-HHmmss-SSS`), not `timestamp-slug-of-prompt`. The prompt is not cleanly available inside `Llm4zio.run` (it rides in the `FlowContext` built downstream), and a millisecond timestamp is unique per run and still time-associates with the run's log file. A prompt slug can be added in a later sub-project if needed.
- **`ProcessSpawn` and `RetryDecision` are not first-class `TraceEvent` kinds in A.** Process spawning happens in the real executor (not unit-testable without a live `gemini`), and retry decisions already appear in the trace as `Info` events (published by `TransientRetry` through the hub, captured by `FlowRecorder.consume`). Structured spawn/retry events are deferred to sub-project C (in-process auto-resume), which is the consumer that actually needs them.
- **Raw capture is at the provider tap, not the executor stdout reader.** The provider-level tap (`LogLine` + `Error`/`Result`) is unit-testable with a stub executor and guarantees the no-chunk empty-stream failure is recorded. Full byte-fidelity raw-stdout capture (the executor's `process.stdout.linesStream.tap`) is deferred to sub-project D, where deterministic replay needs exact bytes.

---

## Self-Review notes

- **Spec coverage:** keystone recorder (Tasks 1-4, 7), raw capture incl. no-chunk error (Task 6), always-on + retention (Tasks 4, 7, 8), JSONL crash-safe format (Task 2), recorder-never-fails invariant (Task 3 degraded test), retry quick-win with independent budget (Task 5), env overrides (Task 8). Non-goals B/C/D explicitly out of scope.
- **Type consistency:** `StreamRecorder.{rawLine, streamError}` signatures identical across Tasks 1/3/6; `TraceEvent.fromFlow` used in Tasks 2/3; `FlowRecorder.{open, install, consume, record}` consistent across Tasks 3/7/8; `flakyRetries` name consistent across Tasks 5/8.
- **No placeholders:** every code step shows full code; commands have expected output.
