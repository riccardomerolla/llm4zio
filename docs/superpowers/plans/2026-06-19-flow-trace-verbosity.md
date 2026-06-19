# Verbosity Levels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four verbosity levels (quiet/normal/verbose/debug) that filter what the live terminal renders, with `debug` teeing raw Gemini stream-json to the terminal — while the trace file stays always-full.

**Architecture:** `Verbosity` (a runner enum) gates terminal rendering in `TerminalListener.consumeTo` (depth tracking still runs for every event; only `surface.log` is gated). Raw lines reach the terminal at `debug` via a generic `Tee` StreamRecorder (flow) installed by `FlowRecorder.install`'s new optional `rawTerminalSink`. Level is resolved from an explicit `flow()`/`run()` param or the `LLM4ZIO_VERBOSITY` env fallback.

**Tech Stack:** Scala 3, ZIO 2.1.25, fansi, ZIO Test. sbt 2.x.

## Global Constraints

- **Verbosity changes only what is SHOWN, never what is captured.** The trace file is always full (sub-project A invariant); `CostTracker` still consumes `TokensUsed` for the footer regardless of level.
- **The cost footer and final ✔/✖ banner always show**, at every level (run-framing, outside the event-render filter).
- **Default level is `Normal`** = today's exact rendering behavior.
- **Resolution order:** explicit `flow()`/`run()` `verbosity` param wins; else `LLM4ZIO_VERBOSITY` env; else `Normal`. Mirrors how `usageLimit` resolves.
- **`Verbosity` lives in `llm4zio.runner`** (terminal concern). `Tee` lives in `llm4zio.flow` and is verbosity-agnostic (sink is a plain `String => UIO[Unit]`). Dependency direction runner → flow → core is preserved.
- **Scala 3 + ZIO 2.1.25.** All new rendering/recorder code returns `UIO` (no new error channel). No `var`.
- **`-Werror` / `-Wunused:all`** — unused/duplicate imports are a fatal compile error. A wildcard `import zio.*` brings `zio.Task`, which shadows `flow.Task` in *type* position; none of these files name `Task`, so `import zio.*` is safe in them, but match each file's existing import style.
- **Build commands:** `sbt llm4zioFlow/test`, `sbt llm4zioRunner/test`, `sbt 'llm4zioRunner/testOnly llm4zio.runner.FooSpec'`. sbt 2's `test` is incremental — `testFull` forces all. `sbt fmt` before committing; `sbt check` for the final lint gate (scalafmt + scalafix import order + -Werror).
- **Env var:** `LLM4ZIO_VERBOSITY=quiet|normal|verbose|debug` (case-insensitive, trimmed; unknown → normal).

---

## File Structure

**Create:**
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Verbosity.scala` — `Verbosity` enum + `renders` + `VerbosityEnv` (Task 1).
- `modules/llm4zio-runner/src/test/scala/llm4zio/runner/VerbositySpec.scala` (Task 1).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Tee.scala` — `Tee` StreamRecorder (Task 3).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TeeSpec.scala` (Task 3).

**Modify:**
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/TerminalListener.scala` — `line` formats `TokensUsed`; `consumeTo` gains `verbosity` and gates rendering; `consume` defaults to `Normal` (Task 2).
- `modules/llm4zio-runner/src/test/scala/llm4zio/runner/TerminalListenerSpec.scala` — new cases (Task 2; create the file if it does not exist).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala` — `install` gains `rawTerminalSink`; expose `tracePath` (Task 4).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala` — tee + tracePath cases (Task 4).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Palette.scala` — add `raw` style (Task 5).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala` — `flow()` gains `verbosity` (Task 5).
- `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala` — `run`/`script` gain `verbosity`; resolve level; thread to `consumeTo` + `install` (Task 5).

---

### Task 1: `Verbosity` enum + `VerbosityEnv`

**Files:**
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Verbosity.scala`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/VerbositySpec.scala`

**Interfaces:**
- Consumes: `llm4zio.flow.FlowEvent`.
- Produces:
  - `enum Verbosity { case Quiet, Normal, Verbose, Debug; def renders(event: FlowEvent): Boolean }`
  - `object VerbosityEnv { val default: Verbosity; def parse(value: Option[String]): Verbosity }`

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.runner

import zio.test.*

import llm4zio.core.TokenUsage
import llm4zio.flow.FlowEvent

object VerbositySpec extends ZIOSpecDefault:
  private val tokens = FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3))
  def spec = suite("Verbosity")(
    test("renders matrix") {
      assertTrue(
        // stages + abort + fail render at every level, including quiet
        Verbosity.Quiet.renders(FlowEvent.StageStarted("s")),
        Verbosity.Quiet.renders(FlowEvent.StageFailed("s", "x")),
        Verbosity.Quiet.renders(FlowEvent.Aborted("a")),
        // quiet hides prose/tool/info/tokens
        !Verbosity.Quiet.renders(FlowEvent.AssistantMessage("hi")),
        !Verbosity.Quiet.renders(FlowEvent.Info("i")),
        !Verbosity.Quiet.renders(FlowEvent.ToolUse("t", "a")),
        !Verbosity.Quiet.renders(tokens),
        // normal shows prose/tool/info, still hides tokens
        Verbosity.Normal.renders(FlowEvent.AssistantMessage("hi")),
        Verbosity.Normal.renders(FlowEvent.Info("i")),
        !Verbosity.Normal.renders(tokens),
        // verbose + debug show tokens
        Verbosity.Verbose.renders(tokens),
        Verbosity.Debug.renders(tokens),
      )
    },
    test("VerbosityEnv.parse") {
      assertTrue(
        VerbosityEnv.parse(None) == Verbosity.Normal,
        VerbosityEnv.parse(Some("  ")) == Verbosity.Normal,
        VerbosityEnv.parse(Some("nope")) == Verbosity.Normal,
        VerbosityEnv.parse(Some("QUIET")) == Verbosity.Quiet,
        VerbosityEnv.parse(Some(" verbose ")) == Verbosity.Verbose,
        VerbosityEnv.parse(Some("debug")) == Verbosity.Debug,
      )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.VerbositySpec'`
Expected: FAIL — `Verbosity` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.runner

import llm4zio.flow.FlowEvent

/** How much a flow renders to the live terminal. Purely a rendering filter — the trace file is always full and the cost
  * footer + final banner always show, regardless of level.
  */
enum Verbosity:
  case Quiet, Normal, Verbose, Debug

  /** Whether this level renders `event` to the terminal. Stage/abort/fail always render; prose/tool/info render at
    * normal and up; token lines render at verbose and up. (Raw provider lines reach the terminal at debug via a Tee, not
    * through the event stream, so they are not part of this gate.)
    */
  def renders(event: FlowEvent): Boolean = event match
    case _: FlowEvent.StageStarted | _: FlowEvent.StageCompleted | _: FlowEvent.StageFailed | _: FlowEvent.Aborted =>
      true
    case _: FlowEvent.Info | _: FlowEvent.ToolUse | _: FlowEvent.AssistantMessage =>
      this != Verbosity.Quiet
    case _: FlowEvent.TokensUsed =>
      this == Verbosity.Verbose || this == Verbosity.Debug

object VerbosityEnv:
  val default: Verbosity = Verbosity.Normal

  /** Parse `LLM4ZIO_VERBOSITY`. Unset/blank/unknown → [[default]] (normal). Case-insensitive, trimmed. */
  def parse(value: Option[String]): Verbosity =
    value.map(_.trim.toLowerCase) match
      case Some("quiet")   => Verbosity.Quiet
      case Some("normal")  => Verbosity.Normal
      case Some("verbose") => Verbosity.Verbose
      case Some("debug")   => Verbosity.Debug
      case _               => default
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.VerbositySpec'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/Verbosity.scala \
        modules/llm4zio-runner/src/test/scala/llm4zio/runner/VerbositySpec.scala
git commit -m "feat(runner): Verbosity enum + VerbosityEnv (quiet/normal/verbose/debug)"
```

---

### Task 2: `TerminalListener` — format tokens + gate by verbosity

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/TerminalListener.scala`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/TerminalListenerSpec.scala` (create if absent)

**Interfaces:**
- Consumes: `Verbosity` (Task 1), `FlowEvents.Hub`, `Palette`, `TerminalSurface`.
- Produces:
  - `TerminalListener.line(event: FlowEvent, palette: Palette): String` — now formats `TokensUsed` instead of returning `""`.
  - `TerminalListener.consumeTo(events: FlowEvents.Hub, palette: Palette, surface: TerminalSurface, verbosity: Verbosity): ZIO[Scope, Nothing, Ref[Long]]`.
  - `TerminalListener.consume(events: FlowEvents.Hub, palette: Palette): ZIO[Scope, Nothing, Ref[Long]]` — unchanged signature, defaults verbosity to `Normal`.

Current code for reference (`TerminalListener.scala`): `line` has `case FlowEvent.TokensUsed(_, _, _) => ""` at line 27; `consumeTo` (lines 70–86) computes `s = line(event, palette)` and logs via `ZIO.unlessDiscard(s.isEmpty)(surface.log(indentBlock(d, s)))` at line 83; `consume` (lines 98–99) calls `consumeTo(events, palette, _)`.

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.runner

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage
import llm4zio.flow.{ FlowEvent, FlowEvents }

object TerminalListenerSpec extends ZIOSpecDefault:

  // A surface that collects logged lines; status/suspend are no-ops.
  private def collecting(ref: Ref[Chunk[String]]): TerminalSurface = new TerminalSurface:
    def log(line: String): UIO[Unit]                              = ref.update(_ :+ line)
    def setStatus(label: Option[String]): UIO[Unit]              = ZIO.unit
    def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A]       = read

  private val plain = Palette(enabled = false)

  private def renderAt(verbosity: Verbosity, events: List[FlowEvent]): UIO[Chunk[String]] =
    ZIO.scoped {
      for
        ref      <- Ref.make(Chunk.empty[String])
        hub      <- FlowEvents.hub()
        consumed <- TerminalListener.consumeTo(hub, plain, collecting(ref), verbosity)
        _        <- ZIO.foreachDiscard(events)(hub.publish)
        _        <- TerminalListener.awaitDrained(hub, consumed, 3.seconds)
        out      <- ref.get
      yield out
    }

  def spec = suite("TerminalListener verbosity")(
    test("line formats TokensUsed (no longer empty)") {
      val s = TerminalListener.line(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(10, 2, 12)), plain)
      assertTrue(s.nonEmpty, s.contains("coder"), s.contains("10"), s.contains("2"))
    },
    test("quiet renders only stage lines; prose and tokens are hidden") {
      for out <- renderAt(
                   Verbosity.Quiet,
                   List(
                     FlowEvent.StageStarted("build"),
                     FlowEvent.AssistantMessage("hello there"),
                     FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3)),
                     FlowEvent.StageCompleted("build"),
                   ),
                 )
      yield assertTrue(
        out.exists(_.contains("build")),
        !out.exists(_.contains("hello there")),
        !out.exists(_.contains("coder")),
      )
    },
    test("verbose additionally renders the token line") {
      for out <- renderAt(
                   Verbosity.Verbose,
                   List(FlowEvent.StageStarted("build"), FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3))),
                 )
      yield assertTrue(out.exists(_.contains("build")), out.exists(_.contains("coder")))
    },
    test("nested stage indentation is preserved when a sibling event is filtered out") {
      for out <- renderAt(
                   Verbosity.Quiet,
                   List(
                     FlowEvent.StageStarted("outer"),
                     FlowEvent.AssistantMessage("filtered"),
                     FlowEvent.StageStarted("inner"),
                     FlowEvent.StageCompleted("inner"),
                     FlowEvent.StageCompleted("outer"),
                   ),
                 )
      yield assertTrue(out.exists(l => l.contains("inner") && l.startsWith("  ")))
    },
  ) @@ TestAspect.withLiveClock
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.TerminalListenerSpec'`
Expected: FAIL — `consumeTo` has no 4-arg overload; `line(TokensUsed)` returns `""`.

- [ ] **Step 3: Write minimal implementation**

In `TerminalListener.scala`, add the import:

```scala
import llm4zio.flow.{ FlowEvent, FlowEvents }
```

(if the file currently imports only `{ FlowEvent, FlowEvents }` already, leave it; add nothing unused.)

Replace the `TokensUsed` case in `line` (line 27):

```scala
      case FlowEvent.TokensUsed(agent, _, usage) =>
        palette.info(s"tokens: $agent ${usage.prompt} in / ${usage.completion} out")
```

Change `consumeTo`'s signature and the emit guard. New signature (line 70):

```scala
  def consumeTo(
    events: FlowEvents.Hub,
    palette: Palette,
    surface: TerminalSurface,
    verbosity: Verbosity,
  ): ZIO[Scope, Nothing, Ref[Long]] =
```

And change the log guard (line 83) from `ZIO.unlessDiscard(s.isEmpty)(...)` to:

```scala
                      _ <- ZIO.unlessDiscard(!verbosity.renders(event) || s.isEmpty)(surface.log(indentBlock(d, s)))
```

(The depth/status bookkeeping above it is unchanged — it still runs for every event, so the tree stays correct when an event is filtered.)

Update `consume` (line 98–99) to default the level:

```scala
  def consume(events: FlowEvents.Hub, palette: Palette): ZIO[Scope, Nothing, Ref[Long]] =
    TerminalSurface.plain.flatMap(consumeTo(events, palette, _, Verbosity.Normal))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.TerminalListenerSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/TerminalListener.scala \
        modules/llm4zio-runner/src/test/scala/llm4zio/runner/TerminalListenerSpec.scala
git commit -m "feat(runner): gate terminal rendering by Verbosity; render token lines at verbose+"
```

---

### Task 3: `Tee` StreamRecorder

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Tee.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/TeeSpec.scala`

**Interfaces:**
- Consumes: `llm4zio.observability.StreamRecorder`.
- Produces: `final class Tee(primary: StreamRecorder, sink: String => UIO[Unit]) extends StreamRecorder`.

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.observability.StreamRecorder

object TeeSpec extends ZIOSpecDefault:
  def spec = suite("Tee")(
    test("forwards rawLine and streamError to both primary and sink") {
      for
        primRaw <- Ref.make(Chunk.empty[String])
        primErr <- Ref.make(Chunk.empty[String])
        sinkBuf <- Ref.make(Chunk.empty[String])
        primary  = new StreamRecorder:
                     def rawLine(p: String, m: Option[String], l: String): UIO[Unit]    = primRaw.update(_ :+ l)
                     def streamError(p: String, m: Option[String], s: String): UIO[Unit] = primErr.update(_ :+ s)
        tee      = new Tee(primary, line => sinkBuf.update(_ :+ line))
        _       <- tee.rawLine("gemini-cli", Some("m"), """{"type":"x"}""")
        _       <- tee.streamError("gemini-cli", None, "Invalid stream")
        pr      <- primRaw.get
        pe      <- primErr.get
        sb      <- sinkBuf.get
      yield assertTrue(
        pr == Chunk("""{"type":"x"}"""),
        pe == Chunk("Invalid stream"),
        sb.exists(_.contains("""{"type":"x"}""")),
        sb.exists(_.contains("Invalid stream")),
      )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TeeSpec'`
Expected: FAIL — `Tee` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import zio.*

import llm4zio.observability.StreamRecorder

/** A [[StreamRecorder]] that fans every signal out to a `primary` recorder and a string `sink`. Used to tee raw provider
  * output to the live terminal (at debug verbosity) while still recording it to the flight-recorder file. The sink is a
  * plain function, so this stays verbosity- and terminal-agnostic.
  */
final class Tee(primary: StreamRecorder, sink: String => UIO[Unit]) extends StreamRecorder:
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit] =
    primary.rawLine(provider, model, line) *> sink(s"$provider: $line")

  def streamError(provider: String, model: Option[String], message: String): UIO[Unit] =
    primary.streamError(provider, model, message) *> sink(s"$provider error: $message")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.TeeSpec'`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Tee.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/TeeSpec.scala
git commit -m "feat(flow): Tee StreamRecorder — fan raw signals to file + a sink"
```

---

### Task 4: `FlowRecorder.install` raw terminal sink + `tracePath`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala`

**Interfaces:**
- Consumes: `Tee` (Task 3), `StreamRecorder.current`.
- Produces:
  - `FlowRecorder#tracePath: java.nio.file.Path` — the trace file this recorder writes to.
  - `FlowRecorder.install(hub, dir, keep, rawTerminalSink: Option[String => UIO[Unit]] = None): ZIO[Scope, Nothing, FlowRecorder]` — when the sink is `Some`, the ambient recorder is `Tee(rec, sink)`.

Current `install` (FlowRecorder.scala:82–89) installs bare `rec` via `StreamRecorder.current.locallyScoped(rec)`. The class has a private `path: Path` field (line 17).

- [ ] **Step 1: Write the failing test (add to `FlowRecorderSpec`)**

```scala
    ,
    test("install with a rawTerminalSink tees raw provider lines to file AND sink; tracePath points at the file") {
      import llm4zio.observability.StreamRecorder
      ZIO.scoped {
        for
          dir     <- ZIO.attemptBlocking(Files.createTempDirectory("install-tee")).orDie
          hub     <- FlowEvents.hub()
          sinkBuf <- Ref.make(Chunk.empty[String])
          rec     <- FlowRecorder.install(hub, dir, keep = 20, rawTerminalSink = Some(l => sinkBuf.update(_ :+ l)))
          ambient <- StreamRecorder.current.get
          _       <- ambient.rawLine("gemini-cli", None, "raw-y")
          _       <- ZIO.sleep(20.millis)
          sb      <- sinkBuf.get
          lines   <- ZIO.attemptBlocking(linesOf(rec.tracePath)).orDie
        yield assertTrue(
          rec.tracePath.getFileName.toString.startsWith("trace-"),
          sb.exists(_.contains("raw-y")),   // teed to the sink
          lines.exists(_.contains("raw-y")), // still in the file
        )
      }
    } @@ TestAspect.withLiveClock
```

(`linesOf` already exists in `FlowRecorderSpec` from sub-project A.)

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: FAIL — `install` has no `rawTerminalSink` param; `tracePath` not found.

- [ ] **Step 3: Write minimal implementation (edit `FlowRecorder.scala`)**

Add the accessor to the class (after the `streamError` method, ~line 54):

```scala
  /** The trace file this recorder writes to. */
  def tracePath: Path = path
```

Replace `install` (lines 82–89) with:

```scala
  def install(
    hub: FlowEvents.Hub,
    dir: Path,
    keep: Int,
    rawTerminalSink: Option[String => UIO[Unit]] = None,
  ): ZIO[Scope, Nothing, FlowRecorder] =
    for
      _      <- FlowTrace.prune(dir, keep)
      runId  <- FlowTrace.runId
      rec    <- open(dir.resolve(s"trace-$runId.jsonl"), runId)
      _      <- rec.consume(hub)
      ambient = rawTerminalSink.fold[StreamRecorder](rec)(sink => new Tee(rec, sink))
      _      <- StreamRecorder.current.locallyScoped(ambient)
    yield rec
```

(`rec.consume(hub)` still uses bare `rec`, so hub events go to the file; only the ambient *raw* path is teed. `rec` is returned and `StreamRecorder` is already imported in this file.)

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowRecorderSpec'`
Expected: PASS (existing cases + the new one).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowRecorder.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowRecorderSpec.scala
git commit -m "feat(flow): FlowRecorder.install rawTerminalSink (tee) + tracePath accessor"
```

---

### Task 5: Runner wiring — `verbosity` param, level resolution, debug tee

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Palette.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`

**Interfaces:**
- Consumes: `Verbosity`/`VerbosityEnv` (Task 1), `TerminalListener.consumeTo` 4-arg (Task 2), `FlowRecorder.install` with `rawTerminalSink` + `tracePath` (Task 4).
- Produces: `flow(...)`, `Llm4zio.run(...)`, `Llm4zio.script(...)` each gain `verbosity: Option[Verbosity] = None`; `Palette#raw`.

This task is verified by the module compiling + existing tests passing (the `run` wiring has no isolated unit test, consistent with sub-project A's runner wiring; the behavior is covered by Tasks 1–4).

- [ ] **Step 1: Add `Palette.raw`**

In `Palette.scala`, add after `toolCall` (line 19):

```scala
  def raw(s: String): String = paint(fansi.Color.DarkGray, s)
```

- [ ] **Step 2: Add `verbosity` to `flow()`**

In `Flow.scala`, add the param to `flow` (after `usageLimit`, line 35) and pass it through:

```scala
def flow(
  args: Array[String],
  coder: CliConnectorConfig = Connectors.coderFromEnv(),
  reasoning: Option[ConnectorConfig] = None,
  defaultPrompt: Option[String] = None,
  reviewers: List[ConnectorConfig] = Nil,
  usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  verbosity: Option[Verbosity] = None,
)(
  body: FlowContext ?=> ZIO[Any, FlowError, Any]
): Unit =
  val effect =
    Llm4zio.script(args.toList, coder, reasoning, defaultPrompt, reviewers, usageLimit, verbosity)(body)
```

(`Verbosity` is in the same `llm4zio.runner` package — no import needed.)

- [ ] **Step 3: Thread `verbosity` through `Llm4zio.script` and `run`; resolve and use the level**

In `Llm4zio.scala`:

(a) `script` — add `verbosity: Option[Verbosity] = None` (after `usageLimit`, before `workDir`) and pass it to `run`:

```scala
  def script(
    args: List[String],
    coder: CliConnectorConfig,
    reasoning: Option[ConnectorConfig] = None,
    defaultPrompt: Option[String] = None,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    verbosity: Option[Verbosity] = None,
    workDir: Path = Path.of(".").toAbsolutePath.normalize,
  )(
    body: FlowContext ?=> ZIO[Any, FlowError, Any]
  ): ZIO[Any, Throwable, Unit] =
    resolvePrompt(args, defaultPrompt) match
      case Left(usage)   => ZIO.fail(ScriptUsage(usage))
      case Right(prompt) =>
        run(workDir, scriptReasoning(coder, reasoning), coder, reviewers, usageLimit, verbosity)(
          withPrompt(prompt)(body)
        )
```

(b) `run` — add `verbosity: Option[Verbosity] = None` (after `usageLimit`):

```scala
  def run(
    workDir: Path,
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    verbosity: Option[Verbosity] = None,
  )(
    body: FlowContext => ZIO[Any, Any, Any]
  ): ZIO[Any, Throwable, Unit] =
```

(c) Inside `run`'s scoped for-comprehension, resolve the level next to the other env parses (after `retries`/`flakyRetries`/`traceKeep`, ~line 67):

```scala
                       level        = verbosity.getOrElse(VerbosityEnv.parse(sys.env.get("LLM4ZIO_VERBOSITY")))
```

(d) Pass `level` to `consumeTo` (was `consumeTo(hub, palette, surface)`, line 72):

```scala
                       consumed  <- TerminalListener.consumeTo(hub, palette, surface, level)
```

(e) Replace the `FlowRecorder.install(...)` call (line 74) so it tees raw lines to the terminal at debug, and prints the trace path once at debug:

```scala
                       rawSink    = Option.when(level == Verbosity.Debug)((l: String) => surface.log(palette.raw(l)))
                       recorder  <- FlowRecorder.install(hub, workDir.resolve(".llm4zio"), traceKeep, rawSink)
                       _         <- ZIO.when(level == Verbosity.Debug)(
                                      surface.log(palette.info(s"trace: ${recorder.tracePath}"))
                                    )
```

- [ ] **Step 4: Compile + run runner and flow suites**

```bash
sbt llm4zioFlow/test
sbt llm4zioRunner/test
```
Expected: both compile and pass (no regressions; `consume`/`consumeTo` callers updated).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-runner/src/main/scala/llm4zio/runner/Palette.scala \
        modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala \
        modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala
git commit -m "feat(runner): wire Verbosity into flow()/run(); tee raw to terminal at debug"
```

---

### Task 6: Full-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite, all modules**

Run: `sbt "; llm4zioCore/testFull; llm4zioFlow/testFull; llm4zioRunner/testFull"`
Expected: all PASS, 0 failures (core unchanged; flow + runner green with new tests).

- [ ] **Step 2: Format + lint gate**

Run: `sbt check`
Expected: clean (scalafmt + scalafix import order + -Werror/-Wunused:all). If scalafix reports import-order diffs, run `sbt fmt` and re-run `sbt check`.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "chore: scalafmt + verification fixups for verbosity levels" || echo "nothing to commit"
```

---

## Self-Review notes

- **Spec coverage:** levels + `renders` (Task 1), `VerbosityEnv` (Task 1), terminal gating + token formatting (Task 2), `Tee` (Task 3), `install` rawTerminalSink + tracePath (Task 4), param/env resolution + debug tee + trace-path print (Task 5), full verify (Task 6). Cost footer/banner untouched (always show — no task needed). Trace-always-full untouched (no task touches capture).
- **Type consistency:** `Verbosity.renders(FlowEvent): Boolean`, `consumeTo(…, verbosity: Verbosity)`, `install(…, rawTerminalSink: Option[String => UIO[Unit]])`, `Tee(primary, sink)`, `recorder.tracePath` — names match across Tasks 1–5.
- **No placeholders:** every step shows full code; commands have expected output.
