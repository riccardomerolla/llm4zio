# Deterministic debug replay — design

**Date:** 2026-06-19
**Status:** Approved (brainstorming → spec)
**Sub-project:** D of 4. Depends on A (Flow Trace recorder / JSONL format, merged v3.7.0).

---

## Problem & goal

Sub-project A records every run to `.llm4zio/trace-<runId>.jsonl`. The original
inspiration (averlang.dev's execution-trace / oracle-stub model) is to turn a real,
non-deterministic run — especially a Gemini "Invalid stream: empty response" flake —
into a **reproducible, offline test**.

Goal: a `ReplayConnector` (an `LlmService`) built from a recorded trace that replays
the recorded LLM **outcomes** (assistant text and stream errors) in recorded order,
so a flaky incident can be reproduced deterministically with zero network — and the
flow's retry/auto-resume behavior verified against it.

## Key constraint shaping the design

The trace records **outputs, not inputs** (per turn: `RawLine*`, `TokensUsed`, then
either `AssistantMessage` on success or `StreamError` on failure — no prompts). So
replay must be **order-based**: the Nth `executeStream` call replays the Nth recorded
turn via a cursor. This is not a compromise — it is exactly what makes recovery
reproducible: a recorded `[StreamError, StreamError, AssistantMessage]` (a flake that
`TransientRetry` recovered from, recorded as three subscriptions) replays as
fail-fail-succeed, so the same recovery happens again.

## Non-goals

- Wire-level (raw-byte through the provider parser) replay — a future seam, not now.
- A `replay <trace>` CLI that re-runs a whole flow — re-running the body still
  performs real git/file/PR side effects (replay substitutes only the LLM), so it
  would be unsafe/misleading.
- Re-executing a CLI coder's file edits (replay reproduces outcomes, not effects).
- Multi-agent disambiguation beyond flat recorded order (see Limitations).
- Changing the trace format beyond adding a decoder to `TraceLine`.

---

## Architecture & components (all in `llm4zio.flow`)

| Unit | One job |
|---|---|
| `TraceLine` (modify A's `FlowTrace.scala`) | Add a `JsonDecoder` so the trace reads back — change `derives JsonEncoder` → `derives JsonCodec`. |
| `ReplayTurn` (enum) + `ReplayTurn.segment(lines: List[TraceLine]): List[ReplayTurn]` | The pure heart. `Success(text: String, usage: Option[TokenUsage], model: Option[String])` \| `Failure(message: String, model: Option[String])`. |
| `ReplayConnector(turns, cursor)` extends `LlmService` | Cursor-driven replay of the turns. |
| `Replay.fromTrace(path): IO[FlowError, ReplayConnector]` + `Replay.read(path)` | Read the `.jsonl`, parse + segment, build a connector with a fresh cursor. |

### `ReplayTurn.segment` (pure)

Walk the `TraceLine`s in order, holding pending `usage`/`model`:

- `TokensUsed` (kind) → set pending `usage` (from `prompt`/`completion`/`total`
  fields) and `model`.
- `AssistantMessage` → emit `Success(text = fields("text"), pendingUsage, pendingModel)`;
  reset pending.
- `StreamError` → emit `Failure(message = fields("message"), model = fields.get("model"))`;
  reset pending.
- `RawLine`, `StageStarted/Completed/Failed`, `Aborted`, `Info`, `ToolUse` → ignored
  for turn boundaries.

(`TokensUsed` precedes `AssistantMessage` within a turn — `EventTappingService` emits
usage on the final chunk, then the flushed `AssistantMessage` at stream end — so
"pending usage, then AssistantMessage closes the turn" is correct.)

### `ReplayConnector` (LlmService)

Built from `turns: List[ReplayTurn]` and a `cursor: Ref[Int]`. The cursor advances
**per subscription** so a `TransientRetry` re-subscription consumes the next recorded
turn:

```scala
def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
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
        case Some(ReplayTurn.Failure(message, _)) =>
          ZStream.fail(LlmError.ProviderError(message, None))
        case None =>
          ZStream.fail(LlmError.ProviderError(s"replay trace exhausted at turn $i", None))
    }
  )
```

- `executeStreamWithHistory(messages)` → delegates to `executeStream("")` (replay is
  order-based; history content is irrelevant to which turn is returned).
- `executeStructured[A]` → collect the next turn's text (`Streaming.collect(executeStream(""))`)
  and parse it (`text.fromJson[A]`, mapping a parse failure to `LlmError.ParseError`),
  mirroring `MockProvider.executeStructured`. The inherited `executeStructuredWithUsage`
  default applies.
- `executeWithTools` → `ZIO.fail(LlmError.InvalidRequestError("replay does not support tool calling"))`
  (CLI coders don't either).
- `isAvailable` → `ZIO.succeed(true)`.

A `Failure` turn's message is wrapped as `LlmError.ProviderError(message)`, so
`TransientRetry.isFlakyStream`/`isTransient` classify a recorded flaky error exactly
as the live one was — that is what lets a retry/auto-resume wrapper reproduce the
recovery.

### `Replay.fromTrace` / `Replay.read`

```
read(path): blocking read; split into lines; for each non-blank line, fromJson[TraceLine];
            skip (logWarning) a line that fails to parse (a crashed run can leave a torn
            final line); return List[TraceLine].
fromTrace(path): read → ReplayTurn.segment → Ref.make(0) → new ReplayConnector(turns, cursor).
```

`read` failures (file missing/unreadable) map to `FlowError.Persistence`.

---

## Limitations (documented)

- **Order-based.** Replay assumes the flow re-issues LLM calls in the recorded order;
  a divergent path mismatches. Acceptable for the target use (reproduce a recorded
  sequence).
- **Single global cursor.** The trace interleaves all agents (coder/reasoning/
  reviewers) as one ordered stream; the cursor is global. Replay is precise for
  single-connector scenarios (the common regression test); multi-agent flows replay
  in flat recorded order.
- **Outcomes, not effects.** Replay reproduces LLM outputs, not a coder's file edits.
- **ToolUse not re-emitted** in v1 (prose + error sequence is the core).

---

## Error handling

`ReplayConnector` never reads I/O after construction (turns are in memory), so its
methods only fail by *design* (a recorded `Failure`, exhaustion, a structured parse
miss) — all typed `LlmError`, exactly like a real connector. `Replay.read`/`fromTrace`
fail with `FlowError.Persistence` on file errors; a single unparseable trace line is
skipped with a warning rather than aborting (robust to torn crash output).

---

## Testing (TDD)

1. `TraceLine` JSON round-trip — encode then decode equals the original.
2. `ReplayTurn.segment` — an in-memory `List[TraceLine]` with a `TokensUsed` + an
   `AssistantMessage` then a `StreamError` yields `[Success(text, Some(usage), model),
   Failure(message, model)]` in order; interleaved `StageStarted`/`Info` are ignored.
3. `ReplayConnector` — successive `executeStream` calls return the turns in order;
   a `Failure` turn fails with `ProviderError(message)`; a turn past the end fails
   with "replay trace exhausted"; `executeStructured` parses a recorded JSON turn.
4. **Headline:** turns `[Failure("Gemini CLI stream error: Invalid stream: empty
   response"), Success("ok")]`, connector wrapped in `TransientRetry(_, flakyRetries =
   2)(using FlowEvents.noop)` → `executeStream("p").runCollect` yields `"ok"` — the
   recovery is reproduced deterministically.
5. **Round-trip integration** — `FlowRecorder` writes a trace containing a
   `StreamError` then an `AssistantMessage`; `Replay.fromTrace` reads that file;
   replaying reproduces fail-then-success.

---

## Component isolation check

- `ReplayTurn` — *what:* the turn model + pure segmentation; *depends on:* `TraceLine`,
  `TokenUsage`.
- `ReplayConnector` — *what:* a cursor-driven `LlmService`; *depends on:* `ReplayTurn`,
  `LlmChunk`/`LlmError`, `Streaming.collect`.
- `Replay` — *what:* read + build; *depends on:* `TraceLine` decoder, `ReplayTurn`,
  `ReplayConnector`, `FlowError`.

Each is understandable and testable without reading the others' internals; the pure
`segment` carries the trickiest logic and is exhaustively unit-tested.
