# Design — usage-limit handling (typed ADT + ZIO sleep-until-reset)

Status: approved (brainstorm), pending implementation plan.
Date: 2026-06-04.
Target version: 2.7.0 (additive feature; `FlowError.Llm` gains a defaulted field).

## Problem

When a CLI coding agent hits a hard **usage/credit cap** (not a per-minute rate
limit), llm4zio surfaces it as an opaque `ProviderError` and the flow dies. Real
example, codex during `reviewAndFixLoop` after the coder had finished the task:

```
codex error: You've hit your usage limit ... try again at 2:38 PM.
```

These caps reset at a **known wall-clock time**. A flow running unattended (e.g.
in a tmux session) should be able to **wait until the reset and continue**, rather
than failing and forcing a manual re-run. ZIO makes this natural: `ZIO.sleep`
parks a fiber cheaply (no held thread, fully interruptible), so "sleep until
14:38 then resume" is a one-liner — no polling daemon.

This is distinct from the existing transient rate-limit path: gemini's
"exhausted capacity, reset after 2s" is seconds-scale and belongs to
`RateLimitError` with short exponential backoff. A usage/credit cap is
hours-scale and resets at a specific instant.

## Goals / non-goals

**Goals**
- A typed, actionable error carrying the reset instant.
- Opt-in "patient mode": wait until reset (capped) and auto-continue, for
  unattended runs.
- Cover the CLI trio (claude, codex, gemini) used by the flows.

**Non-goals (v1)**
- API providers (OpenAI/Anthropic/GeminiApi/Ollama/LmStudio) — their `429` +
  `Retry-After` path overlaps `RateLimitError`; out of scope, easy to add later.
- A dedicated `FlowEvent` case for the wait notice (reuse `Info`; see Events).
- Persisting the wait across process restarts — the cap is handled in-process.

## Design

### 1. ADT + detection

New pure-ADT case in `llm4zio.core` (`Errors.scala`), alongside `RateLimitError`
(`LlmError` is a pure ADT — not a `Throwable` — with a `message` member):

```scala
case class UsageLimitError(resetAt: Option[Instant], provider: String, message: String) extends LlmError
```

(The `message` field satisfies `LlmError`'s abstract `def message`, exactly as
`ProviderError(message, …)` does.)

- `resetAt` — the absolute reset instant when we could parse one; `None` when the
  provider's message wasn't recognized.
- `provider` — "codex" / "claude" / … (for the wait notice).
- `message` — the raw provider text (carried through for surfacing).

Detection is a small, pure, unit-testable matcher in `llm4zio.providers`:

```scala
object UsageLimits:
  /** Classify a provider's raw error text into the right typed error, or None if unrecognized.
    * `now`/`zone` are passed in (not read from a Clock) so parsing stays pure and testable. */
  def classify(provider: String, text: String, now: Instant, zone: ZoneId): Option[LlmError]
```

- **codex**: `"You've hit your usage limit … try again at 2:38 PM"` →
  `UsageLimitError(resetAt = next occurrence of 14:38 in `zone`, "codex", text)`.
  "Next occurrence" = today's date at that wall-clock time; if already ≤ `now`,
  roll forward one day.
- **claude**: analogous wall-clock message ("usage limit reached … resets at 3pm")
  → `UsageLimitError`.
- **gemini**: `"exhausted your capacity … reset after 2s"` → `RateLimitError(Some(2.seconds))`
  (transient, seconds-scale — NOT a usage-limit). A genuine gemini *daily*
  exhaustion that states a time maps to `UsageLimitError`.

Detection plugs in **where each provider already constructs its error**:
- codex: the `codexError` surfacing in `CodexConnector` (structured + stream
  paths) calls `classify` before falling back to `ProviderError`.
- gemini: the `Result(status = error)` / stream-error path in `GeminiCliProvider`.
- claude: the error path in `ClaudeCliConnector`.

The `now`/`zone` at the call site come from `Clock`/system zone; the matcher
itself takes them as parameters so tests pin them.

### 2. Policy + the two mechanisms

Policy (in `llm4zio.flow`):

```scala
case class UsageLimitPolicy(
  enabled: Boolean = false,
  maxWait: Duration = 4.hours,        // ceiling on total wait before giving up
  pollInterval: Duration = 2.minutes, // used only when resetAt is unknown
)
object UsageLimitPolicy:
  val off     = UsageLimitPolicy(enabled = false)
  val patient = UsageLimitPolicy(enabled = true)
```

**(1) Decorator — `UsageLimitAware(underlying: LlmService, policy)(using FlowEvents)`** — the workhorse.
Wraps **all** `LlmService` methods at the **connector level**, where the typed
`UsageLimitError` still exists (before `Chat.ask`/review flatten it to
`FlowError`). On a `UsageLimitError` when `policy.enabled`:
- `resetAt` known → `ZIO.sleep` until it (+ a small buffer), capped at `maxWait`;
- `resetAt` unknown → poll every `pollInterval`, capped at `maxWait`;
- emit a `FlowEvent.Info` notice, then retry the call;
- total wait would exceed `maxWait` → re-raise the `UsageLimitError`.

Covers the autonomous reasoning / review / coder calls entirely (including the
real failure above — the reviewer waits in place and resumes).

**(2) Combinator — `withUsageLimitRetry(policy)(flow)`** — flow-level backstop.
Distinct value: the **interactive `Drive`/`AgentSession`** path, where a held
bidirectional session that hits the limit mid-turn cannot be retried in place
(the process is dead). The combinator sleeps (until `resetAt`, capped) then
re-enters; `implementTaskLoopLive` reopens a fresh session and `PlanStore`
resumability skips completed tasks. A small **re-entry cap (default 3)** bounds
loops.

Requires one enrichment so the combinator can recognize a usage-limit *after*
the `LlmError` has been mapped into the flow's error type:

```scala
// llm4zio.flow FlowError
final case class Llm(message: String, cause: Option[LlmError] = None) extends FlowError
```

The defaulted `cause` keeps existing `FlowError.Llm(e.message)` call sites
compiling; the key mappings (`Chat.ask`, `Drive`) pass the underlying `LlmError`
so the combinator can match `FlowError.Llm(_, Some(_: UsageLimitError))`.

### 3. Opt-in surface + wiring

- **`Llm4zio.run(..., usageLimit: UsageLimitPolicy = UsageLimitPolicy.off)`** — explicit, in scripts.
- **`LLM4ZIO_USAGE_WAIT`** env convenience read by the runner: e.g. `4h` enables
  patient mode with a 4h cap; unset/`off` disables. Unattended run becomes
  `LLM4ZIO_USAGE_WAIT=4h scala-cli run epic.scala …` — no script edit.

When enabled, the runner wires both mechanisms automatically:
- `DefaultFlowContext.build` wraps `reasoning`/`coder`/`reviewers` with
  `UsageLimitAware(policy)` (slotting into the existing decorator stack, with the
  context's `FlowEvents`).
- `Llm4zio.run` wraps the flow body with `withUsageLimitRetry(policy)`.

Off by default → today's behavior (fail fast), but now with the **typed**
`UsageLimitError` instead of an opaque `ProviderError`.

### 4. Events

When it sleeps, emit `FlowEvent.Info` — e.g.
`"⏳ usage limit (codex) — sleeping 2h11m until 14:38"`. Reusing `Info` avoids a
new `FlowEvent` case (which would force an exhaustive-match update in
`TerminalListener`, the `-Werror` non-exhaustive-match pitfall we already hit) for
marginal rendering gain. A dedicated `UsageLimitWait(provider, resetAt)` event is
a deferred future nicety.

### 5. Error handling / edge cases

- **Cap exceeded** → re-raise the typed `UsageLimitError`.
- **`resetAt` in the past** (clock skew / already reset) → sleep 0, retry now.
- **Interruption** — `ZIO.sleep` is interruptible and fiber-parked, so Ctrl-C
  during a long wait in tmux aborts cleanly.
- **Decorator + combinator overlap** — in the normal case (reset within cap) the
  decorator resolves it and the combinator never fires. The combinator only waits
  on the interactive `Drive` path (no decorator on a held session). The
  re-entry cap (3) bounds the pathological "reset > cap" case where both could
  wait; this bounded double-wait is an accepted v1 edge, documented.
- **Clock** — use ZIO `Clock` for `now`/`sleep`; tests use `TestClock` (no real waiting).

## Testing (all deterministic via `TestClock`)

- **`UsageLimits.classify`** unit tests against fixtures: codex `"try again at
  2:38 PM"` → correct `resetAt` for a fixed `now`/zone; next-occurrence rollover
  when the time already passed; claude wall-clock message; gemini short cap →
  `RateLimitError`; unrecognized → `None`.
- **`UsageLimitAware`** with a stub `LlmService` failing once then succeeding:
  `resetAt = now+2h` → `TestClock.adjust(2h)` → success + emitted `FlowEvent.Info`;
  `resetAt = now+5h` with a 4h cap → fails; unknown `resetAt` → polls
  (`adjust(pollInterval)×n`) then succeeds.
- **`withUsageLimitRetry`** with a scripted flow failing once with
  `FlowError.Llm(cause = Some(UsageLimitError(...)))` → sleeps, re-enters,
  succeeds; re-entry cap enforced.

## Components & boundaries

| Unit | Layer | Responsibility | Depends on |
|---|---|---|---|
| `LlmError.UsageLimitError` | core | typed error carrying `resetAt` | — |
| `UsageLimits.classify` | providers | pure text → typed error matcher | core |
| provider error paths | providers | call `classify` at error construction | UsageLimits, Clock |
| `UsageLimitPolicy` | flow | config + presets | core |
| `UsageLimitAware` | flow | per-call sleep-until-reset decorator | LlmService, FlowEvents, Clock |
| `withUsageLimitRetry` | flow | flow-level sleep-and-reenter backstop | FlowError(+cause), Clock |
| `FlowError.Llm(.., cause)` | flow | carry the typed cause across the boundary | core |
| runner wiring | runner | opt-in (param + env), wrap services + body | flow |

## Out of scope / future

- API-provider detection (429 + `Retry-After`).
- Dedicated `FlowEvent.UsageLimitWait` for distinct terminal rendering.
- Persisting wait state across process restarts.
