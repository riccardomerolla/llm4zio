# Deterministic Debug Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replay a recorded trace (`.llm4zio/trace-<runId>.jsonl`) through a `ReplayConnector` (an `LlmService`) that reproduces the recorded LLM outcomes — assistant text and stream errors — in recorded order, turning a real flaky run into a deterministic offline test.

**Architecture:** A pure `ReplayTurn.segment` folds trace lines into ordered turns (Success/Failure). `ReplayConnector` advances a `Ref[Int]` cursor **per subscription**, so a `TransientRetry` re-subscription consumes the next recorded turn — reproducing fail→retry→succeed exactly. `Replay.fromTrace` reads the file (needs a `JsonDecoder` added to `TraceLine`), segments, and builds the connector.

**Tech Stack:** Scala 3, ZIO 2.1.25, zio-streams, zio-json, ZIO Test. sbt 2.x.

## Global Constraints

- **Order-based replay** — the Nth `executeStream` call (subscription) replays the Nth recorded turn. The trace records outputs, not prompts, so this is the only option (and it reproduces recovery: a recorded `[StreamError, StreamError, AssistantMessage]` replays as fail-fail-succeed).
- **Cursor advances per subscription** — use `ZStream.unwrap(cursor.getAndUpdate(_ + 1).map(...))` so each (re)subscription consumes one turn.
- **Normalized event-level fidelity** — reconstruct from `AssistantMessage`/`StreamError`/`TokensUsed`, NOT raw bytes.
- **A `Failure` turn fails with `LlmError.ProviderError(message)`** so `TransientRetry.isFlakyStream`/`isTransient` classify a recorded flaky error exactly as the live one.
- **All in `llm4zio.flow`** (`ReplayConnector` implements core's `LlmService`; flow→core is allowed). Do NOT change the trace format beyond adding a decoder to `TraceLine`.
- **Scala 3 + ZIO 2.1.25.** `-Werror` / `-Wunused:all` — unused/duplicate imports fatal. No `var`. NB: a wildcard `import zio.*` brings `zio.Task` which shadows `flow.Task` in *type* position; the new files name no `Task` type, so `import zio.*` is safe in them.
- **No network in tests** — replay is pure/in-memory; the round-trip test uses the real `FlowRecorder` + a temp file (no network), consistent with the repo rule.
- **Build:** `sbt llm4zioFlow/test`, `sbt 'llm4zioFlow/testOnly llm4zio.flow.FooSpec'`. sbt 2 `test` is incremental — `testFull` forces all. `sbt fmt` before committing; `sbt check` is the lint gate (it WRITES scalafix fixes — re-stage after running).

---

## File Structure

**Create:**
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayTurn.scala` — `ReplayTurn` enum + `segment` (Task 2).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayTurnSpec.scala` (Task 2).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayConnector.scala` — the `LlmService` (Task 3).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayConnectorSpec.scala` (Task 3).
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Replay.scala` — `Replay.read` + `Replay.fromTrace` (Task 4).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplaySpec.scala` (Task 4).

**Modify:**
- `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala` — `TraceLine` gains a decoder (Task 1).
- `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala` — round-trip case (Task 1).

---

### Task 1: `TraceLine` gains a `JsonDecoder`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala`

**Interfaces:**
- Produces: `TraceLine derives JsonCodec` (so `someJsonString.fromJson[TraceLine]` works).

Current (`FlowTrace.scala`, ~lines 55–62):
```scala
final case class TraceLine(
  seq: Long,
  ts: String,
  runId: String,
  kind: String,
  fields: Map[String, String],
) derives JsonEncoder:
  def toJson: String = JsonEncoder[TraceLine].encodeJson(this, None).toString
```
The file already imports `zio.json.*`, which includes `JsonCodec` and the `.fromJson` extension. `JsonCodec` provides both encoder and decoder, so `JsonEncoder[TraceLine]` in `toJson` still resolves.

- [ ] **Step 1: Write the failing test (add to `FlowTraceSpec`)**

```scala
    ,
    test("TraceLine round-trips through JSON (encode then decode)") {
      import zio.json.*
      val line = TraceLine(3L, "2026-06-19T10:00:00Z", "rid", "AssistantMessage", Map("text" -> "hi there"))
      val decoded = line.toJson.fromJson[TraceLine]
      assertTrue(decoded == Right(line))
    }
```

(If `FlowTraceSpec` already imports `zio.json.*` at the top, drop the inner import to avoid a duplicate under `-Wunused`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: FAIL — no `JsonDecoder[TraceLine]` (the `.fromJson[TraceLine]` doesn't compile).

- [ ] **Step 3: Write minimal implementation**

In `FlowTrace.scala`, change the `TraceLine` derivation:
```scala
) derives JsonCodec:
```
(i.e. `derives JsonEncoder` → `derives JsonCodec`). Leave `toJson` unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.FlowTraceSpec'`
Expected: PASS (existing cases + round-trip).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowTrace.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowTraceSpec.scala
git commit -m "feat(flow): TraceLine derives JsonCodec (readable back for replay)"
```

---

### Task 2: `ReplayTurn` + `segment`

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayTurn.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayTurnSpec.scala`

**Interfaces:**
- Consumes: `TraceLine` (Task 1), `llm4zio.core.TokenUsage`.
- Produces:
  - `enum ReplayTurn { case Success(text: String, usage: Option[TokenUsage], model: Option[String]); case Failure(message: String, model: Option[String]) }`
  - `ReplayTurn.segment(lines: List[TraceLine]): List[ReplayTurn]`

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import zio.test.*

import llm4zio.core.TokenUsage

object ReplayTurnSpec extends ZIOSpecDefault:
  private def tl(kind: String, fields: (String, String)*): TraceLine =
    TraceLine(0L, "t", "rid", kind, fields.toMap)

  def spec = suite("ReplayTurn.segment")(
    test("a TokensUsed then AssistantMessage becomes a Success carrying that usage; StreamError becomes a Failure") {
      val lines = List(
        tl("StageStarted", "stage" -> "build"),
        tl("TokensUsed", "agent" -> "coder", "model" -> "gemini-2.5-pro", "prompt" -> "10", "completion" -> "2", "total" -> "12"),
        tl("AssistantMessage", "text" -> "done"),
        tl("Info", "message" -> "noise"),
        tl("StreamError", "provider" -> "gemini-cli", "message" -> "Invalid stream: empty response"),
      )
      val turns = ReplayTurn.segment(lines)
      assertTrue(
        turns == List(
          ReplayTurn.Success("done", Some(TokenUsage(10, 2, 12)), Some("gemini-2.5-pro")),
          ReplayTurn.Failure("Invalid stream: empty response", None),
        )
      )
    },
    test("an AssistantMessage with no preceding TokensUsed has no usage") {
      assertTrue(
        ReplayTurn.segment(List(tl("AssistantMessage", "text" -> "hi"))) ==
          List(ReplayTurn.Success("hi", None, None))
      )
    },
    test("pending usage does not leak across turns") {
      val lines = List(
        tl("TokensUsed", "prompt" -> "1", "completion" -> "1", "total" -> "2"),
        tl("AssistantMessage", "text" -> "first"),
        tl("AssistantMessage", "text" -> "second"),
      )
      assertTrue(
        ReplayTurn.segment(lines) ==
          List(ReplayTurn.Success("first", Some(TokenUsage(1, 1, 2)), None), ReplayTurn.Success("second", None, None))
      )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplayTurnSpec'`
Expected: FAIL — `ReplayTurn` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import llm4zio.core.TokenUsage

/** One replayable LLM outcome reconstructed from a trace: the assistant text a turn produced, or the stream error it
  * failed with. Segmented in recorded order from a trace's [[TraceLine]]s.
  */
enum ReplayTurn:
  case Success(text: String, usage: Option[TokenUsage], model: Option[String])
  case Failure(message: String, model: Option[String])

object ReplayTurn:
  /** Fold trace lines into ordered turns. `TokensUsed` sets the pending usage/model for the next turn; an
    * `AssistantMessage` closes a Success, a `StreamError` closes a Failure. All other kinds (stage/info/tool/raw) are
    * ignored for turn boundaries. `TokensUsed` precedes its `AssistantMessage` in a turn (usage is emitted on the final
    * chunk, the message at stream-end flush), so "pending then close" is correct.
    */
  def segment(lines: List[TraceLine]): List[ReplayTurn] =
    val init = (Option.empty[TokenUsage], Option.empty[String], Vector.empty[ReplayTurn])
    val (_, _, out) = lines.foldLeft(init) {
      case ((pendingUsage, pendingModel, acc), line) =>
        line.kind match
          case "TokensUsed"       =>
            val usage =
              for
                p <- line.fields.get("prompt").flatMap(_.toIntOption)
                c <- line.fields.get("completion").flatMap(_.toIntOption)
                t <- line.fields.get("total").flatMap(_.toIntOption)
              yield TokenUsage(p, c, t)
            (usage.orElse(pendingUsage), line.fields.get("model").orElse(pendingModel), acc)
          case "AssistantMessage" =>
            (None, None, acc :+ Success(line.fields.getOrElse("text", ""), pendingUsage, pendingModel))
          case "StreamError"      =>
            (None, None, acc :+ Failure(line.fields.getOrElse("message", ""), line.fields.get("model")))
          case _                  =>
            (pendingUsage, pendingModel, acc)
    }
    out.toList
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplayTurnSpec'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayTurn.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayTurnSpec.scala
git commit -m "feat(flow): ReplayTurn + segment — fold a trace into ordered LLM outcomes"
```

---

### Task 3: `ReplayConnector`

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayConnector.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayConnectorSpec.scala`

**Interfaces:**
- Consumes: `ReplayTurn` (Task 2), core `LlmService`/`LlmChunk`/`LlmError`/`Message`/`Streaming`/`TokenUsage`, `llm4zio.tools.{AnyTool, JsonSchema}`, `TransientRetry` (test only).
- Produces: `final class ReplayConnector(turns: List[ReplayTurn], cursor: Ref[Int]) extends LlmService` and `object ReplayConnector { def make(turns: List[ReplayTurn]): UIO[ReplayConnector] }`.

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.{ LlmError, TokenUsage }

object ReplayConnectorSpec extends ZIOSpecDefault:
  def spec = suite("ReplayConnector")(
    test("replays success turns in order with text + usage") {
      for
        conn <- ReplayConnector.make(
                  List(
                    ReplayTurn.Success("first", Some(TokenUsage(1, 2, 3)), Some("m")),
                    ReplayTurn.Success("second", None, None),
                  )
                )
        a    <- conn.executeStream("p").runCollect
        b    <- conn.executeStream("p").runCollect
      yield assertTrue(
        a.map(_.delta).mkString == "first",
        a.head.usage.contains(TokenUsage(1, 2, 3)),
        b.map(_.delta).mkString == "second",
      )
    },
    test("a Failure turn fails with a ProviderError carrying the recorded message") {
      for
        conn <- ReplayConnector.make(List(ReplayTurn.Failure("Invalid stream: empty response", None)))
        exit <- conn.executeStream("p").runCollect.exit
      yield assertTrue(exit.isFailure, exit.causeOption.exists(_.failureOption.exists {
        case LlmError.ProviderError(m, _) => m.contains("Invalid stream")
        case _                            => false
      }))
    },
    test("a turn past the end fails with 'replay trace exhausted'") {
      for
        conn <- ReplayConnector.make(Nil)
        exit <- conn.executeStream("p").runCollect.exit
      yield assertTrue(exit.causeOption.exists(_.failureOption.exists(_.message.contains("exhausted"))))
    },
    test("wrapped in TransientRetry, a recorded flaky failure then success reproduces recovery") {
      given FlowEvents = FlowEvents.noop
      for
        conn <- ReplayConnector.make(
                  List(
                    ReplayTurn.Failure("Gemini CLI stream error: Invalid stream: empty response", None),
                    ReplayTurn.Success("ok", None, None),
                  )
                )
        out  <- TransientRetry(conn, flakyRetries = 2, flakyDelay = Duration.Zero).executeStream("p").runCollect
      yield assertTrue(out.map(_.delta).mkString == "ok")
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplayConnectorSpec'`
Expected: FAIL — `ReplayConnector` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import zio.*
import zio.json.*
import zio.stream.{ Stream, ZStream }

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** An [[LlmService]] that replays recorded [[ReplayTurn]]s in order: each `executeStream` subscription consumes the next
  * turn (so a [[TransientRetry]] re-subscription advances, reproducing fail→retry→succeed). Built from a trace via
  * [[Replay.fromTrace]]. Pure and in-memory — no network — so a real incident becomes a deterministic test.
  */
final class ReplayConnector(turns: List[ReplayTurn], cursor: Ref[Int]) extends LlmService:

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    ZStream.unwrap(
      cursor.getAndUpdate(_ + 1).map { i =>
        turns.lift(i) match
          case Some(ReplayTurn.Success(text, usage, model)) =>
            ZStream.succeed(
              LlmChunk(
                delta = text,
                finishReason = Some("stop"),
                usage = usage,
                metadata = Map("provider" -> "replay") ++ model.map("model" -> _),
              )
            )
          case Some(ReplayTurn.Failure(message, _))         =>
            ZStream.fail(LlmError.ProviderError(message, None))
          case None                                         =>
            ZStream.fail(LlmError.ProviderError(s"replay trace exhausted at turn $i", None))
      }
    )

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    executeStream("")

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    ZIO.fail(LlmError.InvalidRequestError("replay does not support tool calling"))

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    Streaming.collect(executeStream(prompt)).flatMap { resp =>
      ZIO
        .fromEither(resp.content.fromJson[A])
        .mapError(err => LlmError.ParseError(s"replay structured parse error: $err", resp.content))
    }

  override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

object ReplayConnector:
  def make(turns: List[ReplayTurn]): UIO[ReplayConnector] =
    Ref.make(0).map(new ReplayConnector(turns, _))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplayConnectorSpec'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReplayConnector.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplayConnectorSpec.scala
git commit -m "feat(flow): ReplayConnector — cursor-driven LlmService that replays recorded turns"
```

---

### Task 4: `Replay.read` + `Replay.fromTrace`

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Replay.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplaySpec.scala`

**Interfaces:**
- Consumes: `TraceLine` (Task 1), `ReplayTurn.segment` (Task 2), `ReplayConnector.make` (Task 3), `FlowError`, `FlowRecorder` (test only).
- Produces:
  - `Replay.read(path: java.nio.file.Path): IO[FlowError, List[TraceLine]]` — read the `.jsonl`, parse each non-blank line, skip (warn) unparseable lines.
  - `Replay.fromTrace(path: java.nio.file.Path): IO[FlowError, ReplayConnector]` — read → segment → build.

- [ ] **Step 1: Write the failing test**

```scala
package llm4zio.flow

import java.nio.file.Files

import zio.*
import zio.test.*

object ReplaySpec extends ZIOSpecDefault:
  def spec = suite("Replay")(
    test("round-trip: a recorder-written trace (failure then success) replays as fail-then-success") {
      ZIO.scoped {
        for
          dir  <- ZIO.attemptBlocking(Files.createTempDirectory("replay-rt")).orDie
          file  = dir.resolve("trace-rid.jsonl")
          rec  <- FlowRecorder.open(file, "rid")
          // Record a flaky failure turn, then a success turn (as the live run would).
          _    <- rec.streamError("gemini-cli", None, "Invalid stream: empty response")
          _    <- rec.record(FlowEvent.TokensUsed("coder", Some("m"), llm4zio.core.TokenUsage(1, 1, 2)))
          _    <- rec.record(FlowEvent.AssistantMessage("recovered"))
          conn <- Replay.fromTrace(file)
          // First subscription replays the failure; second replays the success.
          e1   <- conn.executeStream("p").runCollect.exit
          out  <- conn.executeStream("p").runCollect
        yield assertTrue(e1.isFailure, out.map(_.delta).mkString == "recovered")
      }
    },
    test("read skips a torn/unparseable final line instead of failing") {
      ZIO.scoped {
        for
          dir  <- ZIO.attemptBlocking(Files.createTempDirectory("replay-torn")).orDie
          file  = dir.resolve("trace-x.jsonl")
          good  = TraceLine(0L, "t", "rid", "AssistantMessage", Map("text" -> "ok")).toJson
          _    <- ZIO.attemptBlocking(Files.writeString(file, good + "\n{ this is not json")).orDie
          ls   <- Replay.read(file)
        yield assertTrue(ls.length == 1, ls.head.kind == "AssistantMessage")
      }
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplaySpec'`
Expected: FAIL — `Replay` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*

/** Build a [[ReplayConnector]] from a recorded trace file. */
object Replay:
  /** Read a `.jsonl` trace into [[TraceLine]]s. Blank lines are skipped; a line that fails to parse is skipped with a
    * warning (a crashed run can leave a torn final line) rather than aborting the read.
    */
  def read(path: Path): IO[FlowError, List[TraceLine]] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .mapError(e => FlowError.Persistence(s"failed to read trace at $path", Some(e)))
      .flatMap { content =>
        ZIO.foreach(content.linesIterator.filter(_.trim.nonEmpty).toList) { line =>
          line.fromJson[TraceLine] match
            case Right(tl) => ZIO.some(tl)
            case Left(err) => ZIO.logWarning(s"skipping unparseable trace line: $err").as(None)
        }
      }
      .map(_.flatten)

  /** Read + segment + build a connector with a fresh cursor. */
  def fromTrace(path: Path): IO[FlowError, ReplayConnector] =
    read(path).flatMap(lines => ReplayConnector.make(ReplayTurn.segment(lines)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ReplaySpec'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
sbt fmt
git add modules/llm4zio-flow/src/main/scala/llm4zio/flow/Replay.scala \
        modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReplaySpec.scala
git commit -m "feat(flow): Replay.read + Replay.fromTrace — build a ReplayConnector from a trace file"
```

---

### Task 5: Full-build verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite, all modules**

Run: `sbt "; llm4zioCore/testFull; llm4zioFlow/testFull; llm4zioRunner/testFull"`
Expected: all PASS, 0 failures.

- [ ] **Step 2: Format + lint gate**

Run: `sbt check`
Expected: clean. NB `check` *applies* scalafix fixes (writes files); if it changes anything, run `sbt fmt`, re-stage, commit the fixup, and re-run `sbt check`.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "chore: scalafmt + verification fixups for replay" || echo "nothing to commit"
```

---

## Self-Review notes

- **Spec coverage:** `TraceLine` decoder (Task 1), `ReplayTurn` + segment (Task 2), `ReplayConnector` incl. the TransientRetry-recovery headline + exhaustion + structured (Task 3), `Replay.read`/`fromTrace` + torn-line robustness + recorder round-trip (Task 4), full verify (Task 5). Limitations (order-based, single cursor, outcomes-not-effects, no ToolUse) are documented in the spec and need no code.
- **Type consistency:** `ReplayTurn.{Success(text,usage,model), Failure(message,model)}`, `ReplayTurn.segment(List[TraceLine]): List[ReplayTurn]`, `ReplayConnector(turns, cursor)` / `ReplayConnector.make(turns): UIO[ReplayConnector]`, `Replay.read(Path): IO[FlowError, List[TraceLine]]`, `Replay.fromTrace(Path): IO[FlowError, ReplayConnector]` — consistent across tasks.
- **No placeholders:** every step shows full code; commands have expected output.
