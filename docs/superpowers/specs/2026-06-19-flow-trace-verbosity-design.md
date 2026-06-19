# Verbosity levels — design

**Date:** 2026-06-19
**Status:** Approved (brainstorming → spec)
**Sub-project:** B of 4. Depends on A (Flow Trace recorder, merged v3.7.0).

---

## Problem

A flow currently renders one fixed level of terminal output: the stage tree, tool
calls, assistant messages, info/retry notices, and a final cost footer. There is no
way to ask for **less** (CI/scripted runs that only care about progress + pass/fail)
or **more** (watching the raw Gemini stream-json live while diagnosing the
intermittent empty-stream flake). Sub-project A made the recorder always capture
everything to `.llm4zio/trace-<runId>.jsonl`, including the raw provider stream —
but raw output never reaches the live terminal, and there are no verbosity tiers.

## Goal

Add four verbosity levels — **quiet / normal / verbose / debug** — that control
**only** what the live terminal renders. The trace file stays always-full (A's
invariant). At `debug`, the raw Gemini stream-json is teed live to the terminal.

## Non-goals

- Changing the trace format or what is captured to file (always full — that's A).
- CLI flag parsing (`-v`/`-q`). Env var + a `flow()` param only.
- Per-event custom filters or user-defined levels.
- Touching `RunnerLog` (the file-only ZIO logger) — that is separate from terminal
  verbosity.

---

## Levels & rendering (cumulative)

Verbosity is a **live-terminal rendering filter**. Two things are *run-framing* and
always show regardless of level: the final cost footer (`CostTracker.summary`) and
the final ✔/✖ banner.

| Level | Renders on the live terminal |
|---|---|
| **quiet** | `StageStarted` / `StageCompleted` / `StageFailed` / `Aborted` only |
| **normal** *(today's default)* | quiet **+** `Info` (incl. ⟳ retry notices) **+** `ToolUse` **+** `AssistantMessage` |
| **verbose** | normal **+** `TokensUsed` rendered inline (per-turn token lines; today footer-only) |
| **debug** | verbose **+** raw Gemini stream-json teed live to the terminal **+** the trace-file path printed once at run start |

Notes:

- `TokensUsed` events already flow through the hub and are consumed by `CostTracker`
  for the footer. Today `TerminalListener.line` returns `""` for them. At
  `verbose`/`debug` they additionally render inline; `CostTracker` is unaffected.
- The cost footer shows at every level (including quiet) — it is a one-time run
  summary, not part of the event stream.

---

## Architecture & components

Verbosity is a terminal-rendering concern, so the level enum lives in the **runner**
module. The only flow-module change is a generic `Tee` recorder and an optional sink
parameter on `FlowRecorder.install` — neither knows about `Verbosity`.

| Unit | Module | One job |
|---|---|---|
| `Verbosity` (enum) + `renders` | `llm4zio.runner` | The four levels and `def renders(event: FlowEvent): Boolean` — the gate. |
| `VerbosityEnv` | `llm4zio.runner` | Parse `LLM4ZIO_VERBOSITY` → `Verbosity`; unset/blank/invalid → `Normal`. |
| `TerminalListener` (modify) | `llm4zio.runner` | `consumeTo` takes a `verbosity`; gate `surface.log` by `verbosity.renders`. `line` extended to format `TokensUsed`. |
| `Tee` (StreamRecorder) | `llm4zio.flow` | Forward `rawLine`/`streamError` to both a primary `StreamRecorder` and a `String => UIO[Unit]` sink. Verbosity-agnostic. |
| `FlowRecorder.install` (modify) | `llm4zio.flow` | Gain `rawTerminalSink: Option[String => UIO[Unit]] = None`; when `Some`, install `Tee(rec, sink)` as the ambient recorder. |
| Wiring (modify) | `llm4zio.runner` (`flow`/`run`/`script`) | Add `verbosity: Option[Verbosity] = None`; resolve level (param-or-env); thread to `consumeTo`; pass the raw sink to `install` only at `debug`. |

### `Verbosity.renders` (the gate)

```
quiet   → StageStarted | StageCompleted | StageFailed | Aborted
normal  → quiet ∪ { Info, ToolUse, AssistantMessage }
verbose → normal ∪ { TokensUsed }
debug   → same set as verbose (raw lines arrive via the Tee, not the hub)
```

### `TerminalListener.consumeTo` — gate, don't skip depth

The current `consumeTo` already separates *depth tracking* (always runs, keeps the
tree indentation correct) from *line emission* (only when the rendered string is
non-empty). The change: emit a line only when **both** `verbosity.renders(event)`
**and** the rendered string is non-empty.

```scala
def consumeTo(events, palette, surface, verbosity): ZIO[Scope, Nothing, Ref[Long]] =
  // ... unchanged depth/status bookkeeping for EVERY event ...
  s = line(event, palette)
  _ <- ZIO.unlessDiscard(!verbosity.renders(event) || s.isEmpty)(surface.log(indentBlock(d, s)))
```

`line(TerminalListener)` is extended so `TokensUsed` formats a token line (e.g.
`tokens: <agent> <prompt> in / <completion> out`) instead of `""`. Because
`renders(TokensUsed)` is false at quiet/normal, the formatted line is filtered there
— the format function and the gate stay independent.

`consume` (the back-compat convenience that builds a plain surface) defaults
`verbosity = Verbosity.Normal`.

### Raw tee at debug

`Tee` is a `StreamRecorder` in flow:

```scala
final class Tee(primary: StreamRecorder, sink: String => UIO[Unit]) extends StreamRecorder:
  def rawLine(p, m, line)   = primary.rawLine(p, m, line) *> sink(s"$p: $line")
  def streamError(p, m, msg)= primary.streamError(p, m, msg) *> sink(s"$p error: $msg")
```

`FlowRecorder.install(hub, dir, keep, rawTerminalSink = None)`: when the sink is
`Some`, the ambient `StreamRecorder.current` is set to `Tee(rec, sink)`; otherwise to
bare `rec`. `rec` is still returned and still subscribed to the hub either way. No
hub pollution, no double-capture.

In `Llm4zio.run`, at `debug` the sink is `line => surface.log(<dim palette>(line))`;
otherwise `None`. The live surface already serializes writes above the status line,
so teed raw lines interleave cleanly with the tree.

### Control surface

`flow(...)`, `Llm4zio.run(...)`, and `Llm4zio.script(...)` gain
`verbosity: Option[Verbosity] = None`. In `run`:

```scala
val level = verbosity.getOrElse(VerbosityEnv.parse(sys.env.get("LLM4ZIO_VERBOSITY")))
```

Explicit param wins; env is the fallback; default `Normal`. Mirrors how `usageLimit`
resolves (explicit policy else `UsageWaitEnv`).

---

## Error handling

No new failure surface. `Verbosity`/`VerbosityEnv` are pure. `Tee` is `UIO`
throughout (it composes two `UIO` sinks). The raw terminal sink is `surface.log`,
already `UIO`. Verbosity changes what is *shown*, never what is *captured*, so it
cannot affect correctness or the recorder's never-fails invariant.

---

## Testing (TDD)

1. `VerbosityEnv.parse`: `quiet`/`normal`/`verbose`/`debug` map correctly;
   unset/blank/unknown → `Normal` (case-insensitive, trimmed).
2. `Verbosity.renders` matrix: quiet shows stage/abort/fail and hides
   info/tool/assistant/tokens; normal adds info/tool/assistant, still hides tokens;
   verbose and debug add tokens.
3. `TerminalListener.line(TokensUsed, …)` formats a non-empty token line.
4. `consumeTo` honours the level: with a collecting surface, publishing a fixed event
   sequence at `quiet` emits only stage lines; at `verbose` it additionally emits the
   token line; and the indentation/depth of a rendered child stays correct even when
   a sibling event between a stage open and close was filtered out.
5. `Tee`: `rawLine` and `streamError` reach both a collecting primary and a
   collecting sink (order: primary then sink).
6. `FlowRecorder.install` with `rawTerminalSink = Some(sink)` installs a tee — a
   provider-side `StreamRecorder.current.rawLine` lands in both the trace file and
   the sink; with `None`, only the file.

---

## Component isolation check

- `Verbosity` — *what:* the level + render gate; *depends on:* `FlowEvent` only.
- `VerbosityEnv` — *what:* env parse; *depends on:* `Verbosity`.
- `Tee` — *what:* fan-out recorder; *depends on:* `StreamRecorder` + a function.
- `TerminalListener` change — *what:* gated rendering; *depends on:* `Verbosity`.
- Wiring — *what:* resolve + thread the level; *depends on:* all the above.

Each is understandable and testable without reading the others' internals.
