# Flow Trace recorder + raw capture — design

**Date:** 2026-06-19
**Status:** Approved (brainstorming → spec)
**Sub-project:** A of 4 (keystone). See "Program context" below.

---

## Problem

The `examples/pipeline.sc` flow intermittently dies with:

```
flow failed: Gemini CLI stream error: Invalid stream: The model returned an
empty response or malformed tool call
```

after exhausting its 3 retries. Re-launching the same script succeeds: `PlanStore`
resumes the run and the next `gemini` process spawn gets a good roll of the dice.
The flake is **non-deterministic**, and a *fresh process almost always fixes it*.

Two structural gaps make this worse than it should be:

1. **You debug blind.** Providers parse `stream-json` into normalized `LlmChunk` at
   the boundary; `EventTappingService` then keeps only prose/tool/token semantics.
   **Raw provider output is never captured anywhere.** When Gemini returns garbage,
   you cannot see what it actually returned. Worse, the failure we care about —
   *"empty response / malformed tool call"* — produces **no `LlmChunk` at all**, so
   any mechanism that piggybacks on chunks captures nothing for exactly this case.

2. **The retry budget runs out mid-run**, yet a 4th attempt (your manual relaunch)
   trivially succeeds. The flaky-stream class is conflated with rate-limits, which
   genuinely need long backoff.

## Goal

Build the **keystone** of a cohesive flow-trace subsystem: a per-run **flight
recorder** that captures every flow event *and the raw provider stream* to a
crash-safe, replayable file — so the first occurrence of the flake is always
captured, debugging is no longer blind, and the recording can later drive smarter
resilience (sub-project C) and deterministic replay (sub-project D).

Fold in a small **retry quick-win** to stop the immediate bleeding.

## Non-goals (own specs later)

- **B. Verbosity levels** (quiet/normal/verbose/debug) controlling what the terminal shows.
- **C. Resilience**: full flaky-stream retry tuning + in-process auto-resume over `PlanStore`.
- **D. Deterministic debug replay**: replay a recorded trace with recorded LLM
  outputs as oracle stubs (the Aver model). A's JSONL format is designed to feed this.

---

## Program context (the 4 sub-projects)

```
A. Flow Trace recorder + raw capture   ← THIS SPEC (keystone, unblocks all)
      │   per-run flight recorder → .llm4zio/trace-<runId>.jsonl
      │   captures FlowEvents + raw provider stream-json
      ├─► B. Verbosity levels
      ├─► C. Resilience: flaky-stream retry class + in-process auto-resume
      └─► D. Deterministic debug replay (Aver oracle-stub model)
```

Each sub-project gets its own spec → plan → implementation cycle. A is sequenced
first because B/C/D all serialize into or read from A's trace format; freezing that
format first prevents rework. A also has the highest standalone value.

---

## Architecture & components

Five focused units, each independently testable.

| Unit | Module / package | One job |
|---|---|---|
| `StreamRecorder` (trait) | `llm4zio.core.observability` | Receive low-level signals: `rawLine`, `streamError`, `processSpawn`. Interface + `noop` only. No I/O of its own. |
| `TraceEvent` (ADT) | `llm4zio.flow` | The recorded data model — a superset of `FlowEvent` plus low-level cases. |
| `FlowRecorder` | `llm4zio.flow` | Serialize `TraceEvent`s to JSONL via a single writer fiber. Sources events from two channels: subscribes to the existing `FlowEvents.Hub` for high-level events, and implements `StreamRecorder` for low-level signals. |
| Wiring | `llm4zio.runner` (`flow()`) | Lifecycle: gen `runId`, open file, subscribe to the Hub alongside `TerminalListener`, install the recorder, flush + close on scope exit, prune old traces. |
| Retry split | `llm4zio.flow` (`TransientRetry`) | Quick-win: separate the flaky-stream class from rate-limits. |

### Naming

The new flight recorder is named `FlowRecorder` / `FlowTrace` / `TraceEvent` and
lives in `llm4zio.flow`. This is deliberately distinct from the existing
`llm4zio.core.observability.Tracing` (OpenTelemetry-style spans), which is a
different concept. The `StreamRecorder` *hook* lives in core observability because
it is a core-level interface that core providers emit to.

### How raw output crosses the core→flow boundary

Core must never depend on flow. `StreamRecorder` is defined **in core** as the
interface; flow provides the implementation. It is made available to providers as
an **ambient `FiberRef[StreamRecorder]`** (default `noop`), set once at `flow()`
entry.

- `GeminiCliProvider` already taps every parsed event for debug logging
  (`executeStream`, around `GeminiCliProvider.scala:425-459`). That same tap is
  routed to `FiberRef.get.flatMap(_.rawLine(provider, model, line))`.
- `streamError` is emitted at the failure sites (`GeminiCliProvider.scala:508-531`,
  the `Error` and `Result(status="error")` branches) **with the raw bytes**, so the
  no-chunk empty-stream case is captured.
- `processSpawn` is emitted where the `gemini` process is launched
  (`runGeminiProcessStream`), recording the argv of each fresh spawn.

**Decision: FiberRef-ambient injection** (over threading a constructor param
through every `ConnectorFactory`). Rationale: zero API churn across factories,
ZIO-idiomatic (matches the "no `var`, use `Ref`/`FiberRef`" convention), and child
stream fibers inherit the value. Tests set it via `FiberRef.locally`. Constructor
injection was the considered alternative; rejected as more invasive for no real
gain here.

> Implementation note: confirm `FiberRef` inheritance reaches the fibers spawned by
> the provider's `ZStream`. ZIO copies `FiberRef` values to child fibers on fork by
> default; verify the recorder set at `flow()` entry is visible inside
> `executeStream`'s stream fibers (covered by a test).

---

## Trace format & data flow

### Format: JSONL — `.llm4zio/trace-<runId>.jsonl`

One JSON object per line:

```json
{"seq":12,"ts":"2026-06-19T10:32:01.412Z","runId":"20260619-103155-multiply","kind":"RawLine","provider":"gemini-cli","model":"gemini-2.5-pro","payload":{"line":"{\"type\":\"error\",\"message\":\"Invalid stream: ...\"}"}}
```

Chosen because JSONL is:

- **append-only** — fits the single-writer-fiber model;
- **greppable** — `grep RawLine trace-*.jsonl | jq` works;
- **crash-safe** — a flow that dies mid-run still leaves every prior line valid
  (no partial-document corruption, unlike a single JSON array);
- **replayable line-by-line** — directly feeds sub-project D.

Envelope fields on every line: `seq` (monotonic `Ref` counter), `ts`
(`Clock.instant`, ISO-8601), `runId`, `kind`, and a `kind`-specific `payload`.

### `runId`

Generated at `flow()` entry: `<yyyyMMdd-HHmmss>-<slug>` where the slug derives from
the user prompt / epic (same convention `Plan.defaultPath` uses), keeping a trace
file visually associable with its plan file. (No `Date.now`/`Math.random` purity
concern — this is real runtime via `Clock`.)

### `TraceEvent` kinds

- **High-level (mirrors every `FlowEvent` case):** `StageStarted`,
  `StageCompleted`, `StageFailed`, `Aborted`, `Info`, `ToolUse`,
  `AssistantMessage`, `TokensUsed`.
- **Low-level (new, from `StreamRecorder` + `TransientRetry`):**
  - `RawLine(provider, model, line)`
  - `StreamError(provider, model, error, attempt)`
  - `RetryDecision(error, attempt, delay, willRetry)`
  - `ProcessSpawn(provider, argv)`

### Data flow

```
FlowEvent (Hub) ─┐
                 ├─► FlowRecorder.Queue[TraceEvent] ─► writer fiber ─► trace-<runId>.jsonl
StreamRecorder ──┘        (monotonic seq, ordered, single writer)
 (FiberRef, from provider)
```

The existing Hub already fans `FlowEvent`s to `TerminalListener` via `consumeTo`.
`FlowRecorder` becomes a **parallel subscriber** using the same pattern — the
terminal output is unchanged. Low-level signals arrive via the `StreamRecorder`
FiberRef from inside the provider. Both paths enqueue into one `Queue`, drained by a
single background fiber that appends JSONL — guaranteeing ordering and no
interleaving.

---

## Error handling

**The recorder never fails the flow.** This is a hard invariant — a black box that
crashes the plane is worse than none.

- All `StreamRecorder` and recorder-enqueue operations return `UIO`.
- The writer fiber catches file I/O errors, degrades to noop, and emits a single
  `ZIO.logWarning` (not repeated per line).
- On scope exit (success *or* failure), the recorder flushes the queue and closes
  the file via `ZIO.ensuring` / `Scope`, so a failed flow still yields a complete
  trace up to the failure.

---

## Retention

On `flow()` start, prune `.llm4zio/trace-*.jsonl`, keeping the last **N** by mtime
(default `20`, override via `LLM4ZIO_TRACE_KEEP`). Structured JSONL is cheap; raw
Gemini output is the valuable-but-bulky part, so bounded retention controls disk
without losing recent runs.

---

## The retry quick-win (folded into A)

In `TransientRetry` (`llm4zio.flow.TransientRetry`), split a **`flakyStream`** class
out of the current single transient bucket:

- **flaky-stream signals** (`empty response`, `malformed tool call`,
  `invalid stream`): own budget, default **6** (override `LLM4ZIO_FLAKY_RETRIES`),
  with **short fixed backoff** — a fresh `gemini` process is cheap and almost always
  succeeds.
- **rate-limit / usage-limit signals**: unchanged long backoff.

Every retry decision is recorded as a `RetryDecision` `TraceEvent`. This is the
minimal change that would have kept the pipeline alive; full resilience tuning and
in-process auto-resume are sub-project C.

---

## Testing (TDD)

Unit:

1. `StreamRecorder.noop` is the default — existing core tests/providers are
   unaffected (no behavioral change when no recorder is installed).
2. `FlowRecorder` serializes a known sequence of `TraceEvent`s to JSONL in
   **monotonic `seq` order**, one valid JSON object per line.
3. `FlowRecorder` **swallows write errors** — a failing sink degrades to noop and
   logs once; the surrounding effect still succeeds.
4. `GeminiCliProvider` taps raw lines into an injected (test) recorder, **including
   the no-chunk empty-stream error case** — assert a `StreamError` carrying the raw
   `Invalid stream: ...` bytes is recorded even though no `LlmChunk` is emitted.
5. FiberRef inheritance: a recorder set at the outer scope is visible inside the
   provider's stream fibers.
6. Retry quick-win: a flaky-stream error is retried up to `flakyRetries`; a
   rate-limit error keeps its existing budget/backoff (no regression).

Integration (Mock provider, deterministic):

7. A full `flow()` run produces `.llm4zio/trace-<runId>.jsonl` containing the
   expected event kinds (stages, assistant messages, token usage), and retention
   prunes older trace files beyond `LLM4ZIO_TRACE_KEEP`.

---

## Component isolation check

- `StreamRecorder` — *what:* a sink for low-level provider signals; *how to use:*
  emit `rawLine`/`streamError`/`processSpawn`; *depends on:* nothing but core types.
- `TraceEvent` — *what:* the trace data model; *how:* construct from a `FlowEvent`
  or a low-level signal; *depends on:* `FlowEvent`, `TokenUsage`, `LlmError`.
- `FlowRecorder` — *what:* JSONL writer; *how:* subscribe to the Hub + install as
  the `StreamRecorder`; *depends on:* `TraceEvent`, a file sink, `Queue`/`Ref`.
- Wiring — *what:* run lifecycle; *how:* called inside `flow()`; *depends on:* all
  of the above + `Scope`.

Each unit can be understood and tested without reading the others' internals.
