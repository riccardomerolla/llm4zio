# Bounded context for the modernization flows — design

**Date:** 2026-07-31
**Status:** Approved (brainstorming → spec)
**Targets:** `llm4zio-flow` (new `Context` primitive, `GitTool` path-scoped diff,
`TransientRetry` classifier fix), `llm4zio-modernize` (all five phase flows), `Pack`.

---

## Problem

Running the modernization pipeline against a real estate kills phases with:

```
transient error (structured) — retry 1/3: Gemini CLI returned an error: [API Error: [{
  "error": {
    "code": 400,
    "message": "The input token count exceeds the maximum number of tokens allowed 1048576.",
    "status": "INVALID_ARGUMENT"
  }
}]]
```

All four LLM-driven phases hit it: Extract, Implement, Verify, Review.

Investigation found **four independent causes**, not one. Only `ExtractFlow` has any
context budget at all (`JudgeSourcesLimit`, 400k chars, plus `capText` and the
`judgeWithShrink` ladder); nothing else does.

### (A) Unbounded prompt assembly

Whole spec packs and whole branch diffs are concatenated into single prompts:

| Site | What is attached, unbounded |
| ---- | --------------------------- |
| `ImplementFlow` — spec-compliance judge | `gatherSpecs` = **every** file under `specsDir` (all specs + traceability + mapping) as judge `context`, plus `git.diffVsBase` = **the whole branch diff** as `response`. |
| `ReviewFlow` | The same `specText` + whole `diff` pair, sent **N+2 times**: once per reviewer lens in the roster, once to the judge, and a third time in `distillPrompt`, which re-appends the entire diff on top of all findings. |
| `VerifyFlow` — `triagePrompt` | `traceability.md` plus **every** program spec concatenated, one prompt for the whole estate. |
| `SurveyFlow` | `graph.toJson` and the full rendered inventory. Scales with unit count rather than file contents, so it is the least likely to trip. |

Note: `reviewAndFixLoop`'s `git.diffAll` was initially suspected and is **not** a
problem — it is the working-tree diff, i.e. a single task's uncommitted work, which is
naturally bounded. It stays as-is.

### (B) Chat history accumulation

`ImplementFlow` creates **one** `coderChat` (`ImplementFlow.scala:189`) and reuses it
for every task in `implementTaskLoop`, every `reviewAndFixLoop` round, and every
`specComplianceLoop` feedback round.

`Chat` has no backend session token — by design it replays the entire
`List[Message]` through `executeStreamWithHistory` on every `ask`. So on a 30-task
plan, task 30's request carries all 29 prior task prompts and their full assistant
replies. Growth is monotonic and quadratic in total tokens shipped.

`ExtractFlow` already does this correctly: `Chat.start` sits *inside* the per-program
loop (`ExtractFlow.scala:217`), so each program gets a fresh conversation.

### (C) Inner CLI-agent accumulation

This is why Extract fails **despite** its 400k-char cap. 400k chars is only ~115k
tokens, nowhere near the 1M ceiling — the cap was never the thing failing there.

`programAsk` instructs the analyst to *"Read `$rel` and anything it references
(copybooks, includes, called programs) for context"*, with `AnalystTurns = 48`. That
is up to 48 rounds of the `gemini` CLI reading files into **its own** context, all
inside a single `Chat.ask`. A COBOL program with a deep copybook web blows the limit
there. No prompt-side cap can reach this, because the growth happens past the prompt
we assemble.

### (D) The error is retried when it can never succeed

`TransientRetry.isTransient` matches the bare substring `"api error"`
(`TransientRetry.scala:127`), which catches Gemini's `[API Error: {...}]` wrapper
around **every** error — including deterministic 4xx. A 400 `INVALID_ARGUMENT` is
therefore retried three times with exponential backoff, burning three identical
failures and ~7s before the real error surfaces, and obscuring the diagnosis behind a
"transient" label.

## Goal

Every LLM call in the modernization pipeline carries a bounded, predictable amount of
context. Oversized inputs degrade gracefully — shrink and complete with the truncation
recorded — rather than failing. Truncation is never silent.

## Non-goals

- **Retrieval / embedding-based selection of spec fragments.** Rejected: it adds a
  dependency and makes gate input non-deterministic, which is the opposite of what a
  clean-room evidence chain needs, and it fights the "stateless + plain files"
  convention.
- **Token-accurate budgeting.** Budgets stay in characters (see rationale below).
- **Chat compaction / summarisation.** Fresh-chat-per-task is sufficient here; a
  summarising strategy is not needed and is not built.
- **Changing `reviewAndFixLoop`, `PlanStore`, or `implementTaskLoop` semantics.**

---

## Design

### 1. `Context` — the budget primitive (`llm4zio-flow`)

Generalises what `ExtractFlow` already proved, so every flow (and every embedder) can
use it.

```scala
object Context:
  final case class Capped(text: String, originalChars: Int, truncated: Boolean)
  final case class Truncation(label: String, originalChars: Int, keptChars: Int)

  /** Head 3/4 + tail 1/4 with an elision marker, so entry points and trailing rules both survive. */
  def cap(text: String, limit: Int): Capped

  /** `cap`, publishing a FlowEvent and recording the truncation when one occurs. */
  def capped(label: String, text: String, limit: Int)(using FlowEvents): UIO[String]

  /** LLM4ZIO_CONTEXT_BUDGET (chars), default 400_000. */
  def budget: Int

  /** Run `f` at the full budget; on empty-response or context-overflow failure, retry at
    * 1/2 then 1/4. Each step publishes a FlowEvent and records the shrink.
    */
  def withShrink[A](label: String)(f: Int => IO[FlowError, A])(using FlowEvents): IO[FlowError, A]

  /** Truncations recorded on this fiber. */
  def truncations: UIO[Chunk[Truncation]]
```

`withShrink` is the load-bearing piece. It lifts `ExtractFlow.judgeWithShrink`'s
ladder into the library and **widens its trigger**: today it fires only on empty
responses; it must also fire on a detected context overflow. An oversized prompt then
degrades to a recorded truncation and completes, instead of failing the phase.

**Budgets are in characters, not tokens.** Deterministic, no tokenizer dependency, and
it is what the codebase already uses. Rule of thumb ~3.5 chars/token for code, so the
400k default is ~115k tokens — conservative against every provider. Per-phase
overrides are plain `Int` parameters.

`LLM4ZIO_JUDGE_SOURCES_LIMIT` is kept as a deprecated alias for
`LLM4ZIO_CONTEXT_BUDGET` so existing runbooks keep working.

**Recording.** `capped` and `withShrink` are the only writers: each appends to a
`FiberRef[Chunk[Truncation]]` as a side effect of truncating, so no call site has to
remember to record. `Provenance`
gains `contextTruncations: List[String] = Nil` (defaulted, so pre-existing manifests
still parse). Each phase writes what it collected into `provenance.json` at its
Provenance stage. A reader can then see that a gate verdict rested on a partial view —
which is the whole point of allowing truncation at all.

### 2. `GitTool.diffVsBase(base, paths)` (`llm4zio-flow`)

```scala
def diffVsBase(base: String, paths: List[String], threeDot: Boolean = true)(using Caps.GitRead): IO[FlowError, String]
```

Emits `git diff <base>...HEAD -- <paths>`. Does not exist today; every decomposition
below needs it. The existing no-paths overload stays.

### 3. `Pack.programFiles` (`llm4zio-flow`)

A regex template mapping a program name to its implementation files in the target
repo, e.g. `.*(?i)<NAME>.*\.java`. `<NAME>` is substituted with the program name.
Defaults to a case-insensitive program-name path match when the pack omits it.

This belongs in `Pack` because it is exactly the estate-specific knowledge `Pack`
exists to hold ("Packs are data"). Parsed via the existing `fields.get("programFiles")`
path alongside `sources` and `programs`.

### 4. Per-phase changes (`llm4zio-modernize`)

**ImplementFlow**

1. Fresh `Chat` per task, created *inside* `implementTaskLoop` — matching the pattern
   `ExtractFlow` already uses per program. `specComplianceLoop` gets its own chat.
   This is safe because nothing load-bearing lives only in the transcript: the repo is
   the shared memory. `plan.taskPrompt` carries the task, the system prompt carries the
   pack brief + lessons + pattern cards, and a CLI coder reads what earlier tasks wrote.
2. Spec-compliance judge goes **per program**: program *P*'s specs judged against
   `diffVsBase(base, P's files)` where the file set comes from `pack.programFiles`.
   Wrapped in `ReviewCache` (fingerprinted over spec + diff slice + rubric), so
   unchanged programs cost no LLM call and the gate is resumable — the same shape as
   `ExtractFlow.judgeProgram`.
3. Changed files matching no program form an **unassigned bucket**, judged in a single
   call routed through `Context.withShrink` — so an unusually large bucket shrinks and
   completes rather than failing, with the truncation recorded.
4. **Estate-wide traceability pass, retained.** One additional judge call over
   `traceability.md` plus the changed-file *list* (names only, not contents) — small
   and bounded — to catch cross-program breakage that per-program judging cannot see.

**ReviewFlow**

1. Each lens receives only the diff of the files it matched. `Reviewer.matches` already
   computes the matched-file predicate and the roster is already filtered by it; the
   *diff* simply is not scoped to it yet.
2. The judge shares Implement's per-program helper.
3. `distillPrompt` drops the whole-diff re-append. Findings already quote the relevant
   code, so that third copy of the diff is near-pure waste. Findings + scores only,
   capped as a backstop.

**VerifyFlow**

`triagePrompt` groups `failing` verdicts by `v.vector.program` and issues one call per
program, carrying only that program's spec. Mismatches already group naturally by
program, so this is a regrouping rather than a redesign.

**SurveyFlow**

Cap + record only, via `Context.capped` on `refinePrompt` and `triagePrompt`. These
scale with unit count rather than file contents and are the least likely to trip.
Chunking is deliberately deferred until the cap is observed to fire.

**ExtractFlow — the (C) fix**

1. Replace *"read anything it references"* with a **deterministically pre-resolved
   include closure**: reuse the pack's existing `survey:` dependency-edge regexes
   (CALL / COPY / EXEC PGM), already consumed by `Survey.graph`, to compute program
   *P*'s dependency set, and name those exact files in `programAsk`.
2. The closure list is itself capped and recorded — a program pulling 500 copybooks
   gets a bounded, visible subset rather than an unbounded read.
3. `AnalystTurns` becomes env-overridable (`LLM4ZIO_ANALYST_TURNS`). The default stays
   48; with a named file list the agent needs fewer turns naturally.
4. `judgeWithShrink` and `capText` are deleted in favour of the `Context` equivalents.

### 5. Error handling — the (D) fix

`TransientRetry`:

- `isTransient` gains a deterministic-4xx guard. Messages containing
  `INVALID_ARGUMENT`, `"code": 400`, or `exceeds the maximum number of tokens` are
  **not** transient, regardless of the `"api error"` substring.
- New `isContextOverflow(e: LlmError): Boolean` predicate for the token-count family.
- Context overflow routes to `Context.withShrink` (retry smaller) rather than to the
  transient budget.

When a call still fails after the shrink ladder, the surfaced message names the knob:
*"prompt exceeded the model's input limit after shrinking to N chars — lower
LLM4ZIO_CONTEXT_BUDGET or scope this phase"* — instead of raw Gemini JSON labelled
"transient".

---

## Testing

TDD per CLAUDE.md; the `Mock` provider gives deterministic LLM behaviour.

- **`ContextSpec`** — `cap` keeps head and tail and marks the elision; `Capped`
  reports `originalChars` and `truncated` accurately; text at or under the limit is
  returned untouched; `budget` reads `LLM4ZIO_CONTEXT_BUDGET` and honours the
  deprecated alias.
- **`ContextSpec` (shrink ladder)** — over `Mock`: a service failing with a
  context-overflow error at full budget and succeeding at half completes, publishes
  the shrink event, and records the truncation; exhausting the ladder fails with the
  budget-naming message.
- **`TransientRetrySpec`** — the 400 / `INVALID_ARGUMENT` / token-count message is
  **not** transient and is **not** retried; `isContextOverflow` matches it; every
  existing transient (connection reset, 5xx, rate limit, flaky stream) still behaves
  as before. This is a regression guard on a classifier that is easy to over-widen.
- **`GitToolItSpec`** — path-scoped `diffVsBase` against the existing temp-repo +
  local-bare-remote harness (no network).
- **`PackSpec`** — `programFiles` parses; `<NAME>` substitution; the default template
  applies when the field is absent.
- **Phase specs** — program→files grouping from the pack; the per-program judge issues
  exactly one call per program plus one traceability pass; `ReviewCache` reuse skips
  unchanged programs; `distillPrompt` output contains no diff; Implement starts one
  chat per task rather than one per flow.

## Rollout

Additive and backward-compatible. `Context`, `diffVsBase(paths)`, and
`Pack.programFiles` are new surface; `contextTruncations` is a defaulted field;
`LLM4ZIO_JUDGE_SOURCES_LIMIT` still works. The behavioural changes are confined to the
modernize phases.

Target: **v4.3.0**.

## Risks

- **`programFiles`' default is a guess at estate naming.** If a program's target code
  does not carry the program name in its path, the default regex assigns nothing and
  everything lands in the unassigned bucket. That still works — it degrades to the
  current whole-diff behaviour, now budgeted — but loses the per-program benefit. Worth
  validating against a real target repo early rather than trusting the fixture estate.
- **Per-program judging changes the gate verdict shape.** `gate/<NAME>.json` per
  program rather than one branch verdict, so `provenance.json`'s `gateVerdicts` map
  grows an entry per program. Consistent with Extract's existing gate, but it is a
  visible change to the evidence artifact.
- **Fresh-chat-per-task loses cross-task conversational memory.** Mitigated by the repo
  being the shared state, but a task whose correct implementation depends on an
  *unwritten* decision from an earlier task would regress. No such dependency exists in
  the current pack prompts; worth re-checking if pack authors start relying on it.
