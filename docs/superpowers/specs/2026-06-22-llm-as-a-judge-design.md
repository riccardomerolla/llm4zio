# LLM-as-a-Judge — core evaluation layer — design

**Date:** 2026-06-22
**Status:** Approved (brainstorming → spec)
**Sub-project:** A of 2. This spec = the shared `Evaluator` abstraction + Layer 1
(deterministic checks) + Layer 2 (LLM-as-a-Judge with variance), all in
`llm4zio-core`. **Layer 3 (behavioural — tool-call order, escalation, scope) is a
follow-on sub-project in `llm4zio-flow`**, where the tool-call trace lives; it also
carries the flow-native gate sugar and a worked `.sc` example.

---

## Problem & goal

The "Three layers of evaluation" talk (PILLAR 01) frames LLM quality assurance as
**evaluation**: score an LLM's output against expectations across named dimensions,
not just review a diff. llm4zio already has a *code-diff peer-review* loop
(`Reviewer`/`reviewAndFixLoop`) — that finds issues in a change and fixes them. It
has **no** primitive for scoring an output.

Goal: a thin, orca-shaped `llm4zio.eval` package that ships the talk's first two
layers as composable ZIO values:

- **Layer 1 — Deterministic.** Pure checks: PII (regex), output-format validation,
  response-length bounds. No LLM.
- **Layer 2 — Semantic.** The LLM-as-a-Judge: user-defined scored dimensions, strict
  JSON back via `executeStructured`, plus the talk's non-determinism fix — run each
  case N times and flag dimensions whose scores spread too wide ("flaky").

Both surfaces from the brainstorm are served at the core level: a **gate** (evaluate
→ check threshold → proceed/retry, just ZIO composition) and a **harness** (a small
case runner for tests/CI).

## Design choices (settled in brainstorming)

- **User-defined dimensions + rubric.** A `Dimension(name, rubric, maxScore = 2)` is
  supplied by the caller; the judge prompt is built from the list. Scale defaults to
  the talk's 0–2 but is per-dimension configurable. (Not the hard-coded
  correctness/groundedness/safety trio — this is a library.)
- **Reuse `LlmError`, no `EvalError`.** Deterministic evaluators never fail
  (`ZIO.succeed`); the judge already fails `LlmError`; a flow gate wraps it as
  `FlowError.Llm` for free. One error channel keeps eval composable with the rest of
  core.
- **Range-based variance.** Run N times; per dimension report median + spread
  (max − min); spread > threshold (default 1, i.e. runs disagree by more than one
  bucket) marks that dimension flaky. Keep the raw N runs.
- **Regex PII only.** NER is a heavy dependency and out of scope; the check API is
  open (users add regexes) and the judge's `safety` dimension can cover semantic PII.

## Non-goals

- **Layer 3 (behavioural).** Tool-call order / escalation / scope needs the
  `FlowTrace` and lives in `llm4zio-flow` — a separate sub-project.
- **NER-based PII.** Deferred; regex + a judge dimension cover the common cases.
- **Full JSON-Schema validation as a deterministic check.** `validJson` (parses?) and
  `matches(regex)` ship; structural schema-conformance is a later, optional check.
- **Flow/runner changes.** No `FlowContext` gate sugar and no `examples/*.sc` here —
  those land with Layer 3. This sub-project is core + ZIO Test only.
- **A scored "pass/fail" verdict type in core.** We expose predicates (`meets`) and a
  `SuiteReport`; we do not introduce a new flow-style `Verdict`.

---

## Architecture & components (all in `llm4zio.eval`, module `llm4zio-core`)

| Unit | One job |
|---|---|
| `Dimension` | A scored axis: `name`, `rubric` (how to score it), `maxScore = 2`. |
| `DimensionScore` (derives `JsonCodec`) | A model's score on one dimension: `name`, `score: Int`, `reasoning: String = ""`. |
| `EvalResult` (derives `JsonCodec`) | One evaluation's outcome: `scores: List[DimensionScore]`, `summary: String = ""`; helpers `score(name)`, `total`, `meets(min)`. |
| `Sample` | Default judge input: `response`, optional `query`/`context`/`expected` (the talk's USER block). |
| `Evaluator[-In]` (trait) | The unifying primitive: `evaluate(input): IO[LlmError, EvalResult]`; combinators `contramap`, and `Evaluator.all`. |
| `Checks` | Layer 1: `noPii`, `validJson`, `matches(regex)`, `lengthBetween(min, max)` — each an `Evaluator[String]`. |
| `Judge` | Layer 2: `Judge.of(llm, dimensions, system)` → `Evaluator[Sample]`. Prompt builder + wire DTO + schema. |
| `Eval.repeat` + `RepeatedEval`/`DimensionStats` | Run N times, aggregate, flag flaky dimensions. |
| `EvalSuite` + `EvalCase`/`SuiteReport` | The harness: run an evaluator over a list of cases, report per-case pass/fail. |

### Data model

```scala
package llm4zio.eval

final case class Dimension(name: String, rubric: String, maxScore: Int = 2)

final case class DimensionScore(name: String, score: Int, reasoning: String = "") derives JsonCodec

final case class EvalResult(scores: List[DimensionScore], summary: String = "") derives JsonCodec:
  def score(name: String): Option[Int] = scores.find(_.name == name).map(_.score)
  def total: Int                       = scores.map(_.score).sum
  /** Gate predicate: every dimension scored at least `minPerDimension`. Empty ⇒ true. */
  def meets(minPerDimension: Int): Boolean = scores.forall(_.score >= minPerDimension)

final case class Sample(
  response: String,
  query: Option[String] = None,
  context: Option[String] = None,
  expected: Option[String] = None,
)
```

### `Evaluator[-In]` (trait + combinators)

Contravariant so a `Checks`-style `Evaluator[String]` adapts to a richer input:

```scala
trait Evaluator[-In]:
  def evaluate(input: In): IO[LlmError, EvalResult]
  def contramap[In2](f: In2 => In): Evaluator[In2] =
    (in2: In2) => evaluate(f(in2))

object Evaluator:
  /** Run all evaluators on the same input and merge their dimension scores. */
  def all[In](es: Evaluator[In]*): Evaluator[In] =
    (in: In) =>
      ZIO.foreach(es.toList)(_.evaluate(in)).map { results =>
        EvalResult(
          scores = results.flatMap(_.scores),
          summary = results.map(_.summary).filter(_.nonEmpty).mkString("; "),
        )
      }
```

Composition example — Layer 1 + Layer 2 over a `Sample`:

```scala
val layer1: Evaluator[Sample] =
  Evaluator.all(Checks.noPii, Checks.lengthBetween(1, 2000)).contramap(_.response)
val layer2: Evaluator[Sample] =
  Judge.of(llm, dimensions = List(
    Dimension("correctness",  "Does the response match the expected outcome?"),
    Dimension("groundedness", "Is every factual claim supported by the context?"),
    Dimension("safety",       "Does the response avoid PII leakage / hallucinated data?"),
  ))
val evaluator: Evaluator[Sample] = Evaluator.all(layer1, layer2)
```

### Layer 1 — `Checks` (deterministic, pure)

Each check is an `Evaluator[String]` that produces a single binary `DimensionScore`
(0 or `maxScore`, default `maxScore = 2` to share the judge's scale). No LLM, so
`ZIO.succeed` — they never fail.

```scala
object Checks:
  /** No common PII (email, phone, credit-card, SSN-ish, IPv4). Regex-based; NER is out of scope. */
  def noPii(maxScore: Int = 2): Evaluator[String]
  /** Output parses as JSON. */
  def validJson(maxScore: Int = 2): Evaluator[String]
  /** Output fully matches `regex` (`String.matches` — whole-string match, the format-validation case). */
  def matches(regex: String, name: String = "format", maxScore: Int = 2): Evaluator[String]
  /** Length within [min, max] inclusive. */
  def lengthBetween(min: Int, max: Int, maxScore: Int = 2): Evaluator[String]
```

PII detectors are a small, documented regex set (extend by composing your own
`matches`). A failing check scores `0` and records *why* in `reasoning`
(e.g. `"matched email pattern"`); a passing check scores `maxScore`.

### Layer 2 — `Judge` (LLM-as-a-Judge)

```scala
object Judge:
  val defaultSystem: String =
    "You are an impartial evaluator. Score each dimension on its rubric. " +
      "Be strict; when unsure, score lower. Return ONLY valid JSON, no prose."

  def of(llm: LlmService, dimensions: List[Dimension], system: String = defaultSystem): Evaluator[Sample]
```

- **Prompt** (built from `dimensions` + the `Sample`) mirrors the talk: a SYSTEM block
  listing each dimension's name, rubric and `0..maxScore` scale and demanding strict
  JSON; a USER block with `query`/`context`/`response`/`expected` (omitting absent
  optionals).
- **Wire DTO + schema.** `final case class JudgeResponse(scores: List[DimensionScore]) derives JsonCodec`;
  schema via `SchemaDerivation.derive[JudgeResponse]`. The call is
  `llm.executeStructured[JudgeResponse](prompt, schema)` (the same path the reviewers
  use), then map to `EvalResult`. The robust `StructuredOutputs.parseFromText` fallback
  already handles fenced/dirty JSON.
- **Clamping.** Scores are clamped to `0..maxScore` per dimension on the way out
  (a model that returns 3 on a 0–2 scale is pinned to 2), and a dimension the model
  omitted is reported as score `0` with `reasoning = "missing"` so a gate can't be
  fooled by a dropped field.

### Variance / repeat-N

```scala
final case class DimensionStats(name: String, median: Int, min: Int, max: Int):
  def spread: Int                       = max - min
  def isFlaky(threshold: Int): Boolean  = spread > threshold

final case class RepeatedEval(runs: List[EvalResult], stats: List[DimensionStats], spreadThreshold: Int):
  def flakyDimensions: List[String] = stats.filter(_.isFlaky(spreadThreshold)).map(_.name)
  def isFlaky: Boolean              = flakyDimensions.nonEmpty
  /** A representative single result built from per-dimension medians. */
  def aggregate: EvalResult

object Eval:
  /** Run `e` on `input` `n` times; aggregate per-dimension stats; flag dimensions whose spread exceeds the threshold. */
  def repeat[In](e: Evaluator[In], input: In, n: Int = 3, spreadThreshold: Int = 1): IO[LlmError, RepeatedEval]
```

`repeat` runs sequentially by default (judge calls can be rate-limited; the brainstorm
deferred a parallelism knob — add it only if a backend needs it, mirroring
`reviewAndFixLoop`'s `parallelism`). Median of an even N is the lower-middle element
(integer buckets; no fractional scores).

### The two surfaces

**Gate (free — documented pattern, no new machinery):**

```scala
judge.evaluate(sample).flatMap { r =>
  ZIO.unless(r.meets(minPerDimension = 1))(retryOrEscalate(r)).as(r)
}
```

The `meets` predicate + ordinary ZIO composition is the gate. Inside a flow, the
evaluator's `LlmError` lifts to `FlowError.Llm`, so it drops straight into a `flow {}`
body. (The ergonomic `FlowContext` sugar is the Layer 3 sub-project's job.)

**Harness (small case runner):**

```scala
final case class EvalCase[In](name: String, input: In, minPerDimension: Int = 1)

final case class CaseReport(name: String, result: EvalResult, repeated: Option[RepeatedEval], passed: Boolean)
final case class SuiteReport(cases: List[CaseReport]):
  def passed: Boolean       = cases.forall(_.passed)
  def failures: List[CaseReport] = cases.filterNot(_.passed)

object EvalSuite:
  /** Run `e` over `cases`. With `repeats > 1`, each case runs through `Eval.repeat`;
    * a case passes when its (aggregate) result `meets(minPerDimension)` AND it is not flaky. */
  def run[In](
    e: Evaluator[In],
    cases: List[EvalCase[In]],
    repeats: Int = 1,
    spreadThreshold: Int = 1,
  ): IO[LlmError, SuiteReport]
```

A test asserts on `SuiteReport.passed` / inspects `failures`; CI fails the build when a
case regresses or goes flaky.

---

## Error handling

- Deterministic `Checks` never fail — they encode failure as a `score = 0`
  `DimensionScore`, not an effect failure.
- `Judge` fails only as the underlying `LlmService` does: `LlmError.ProviderError`,
  `LlmError.ParseError` (unparseable JSON after the `StructuredOutputs` fallbacks),
  etc. — exactly like the reviewers' structured calls.
- `Eval.repeat` / `EvalSuite.run` propagate the first `LlmError` from any underlying
  evaluation (a judge that can't be reached is a real failure, not a low score). A
  *low score* or a *flaky* dimension is a value, never an error — it shows up in
  `EvalResult` / `RepeatedEval` / `SuiteReport`.

This is the recoverable-vs-catastrophic split: a poor evaluation is a typed value; an
unreachable judge fails the effect.

---

## Testing (TDD, `Mock` provider for the judge)

1. **`EvalResult` helpers** — `score`, `total`, `meets` (empty scores ⇒ `meets` true;
   one sub-threshold dimension ⇒ false).
2. **`Checks.noPii`** — a clean string scores `maxScore`; strings containing an email /
   phone / credit-card / SSN / IPv4 each score `0` with a naming `reasoning`.
3. **`Checks.validJson` / `matches` / `lengthBetween`** — pass and fail cases, including
   boundary lengths (exactly `min`, exactly `max`).
4. **`Evaluator.all` + `contramap`** — two `Evaluator[String]` over `_.response` merge
   their dimensions; summaries concatenate.
5. **`Judge.of` happy path** — `Mock` returns canned
   `{"scores":[{"name":"correctness","score":2,"reasoning":"..."}, ...]}`; the prompt
   contains each dimension's rubric and the scale; the result maps 1:1.
6. **`Judge` clamping / missing dimension** — `Mock` returns a score of `3` (clamped to
   2) and omits one dimension (reported as `0`, `reasoning = "missing"`).
7. **`Judge` parse failure** — `Mock` returns non-JSON; evaluation fails
   `LlmError.ParseError`.
8. **`Eval.repeat` variance** — a `Mock` (or a tiny stub `Evaluator`) returning
   `[2, 0, 1]` for a dimension across 3 runs: median `1`, spread `2`, flagged flaky at
   `spreadThreshold = 1`; the same scores with `spreadThreshold = 2` are not flaky;
   identical scores across runs ⇒ not flaky, median equals the score.
9. **`EvalSuite.run`** — a 2-case suite where one case meets the threshold and one
   doesn't ⇒ `SuiteReport.passed == false`, `failures` names the failing case; with
   `repeats > 1`, a flaky-but-high-scoring case is reported as failed.

`Mock` provider returns deterministic text/JSON per the existing `MockProvider`
pattern, so every judge test is offline and reproducible.

---

## Component isolation check

- `Dimension`/`DimensionScore`/`EvalResult`/`Sample` — *what:* the value model;
  *depends on:* `zio.json` only. Pure.
- `Evaluator` — *what:* the unifying effect + combinators; *depends on:* `EvalResult`,
  `LlmError`, `ZIO`.
- `Checks` — *what:* Layer 1 deterministic scorers; *depends on:* `Evaluator`,
  `EvalResult`. Pure, no LLM — testable with plain strings, no `Mock`.
- `Judge` — *what:* Layer 2 prompt/DTO/schema + the `executeStructured` call;
  *depends on:* `Evaluator`, `Dimension`, `Sample`, `LlmService`, `SchemaDerivation`,
  `StructuredOutputs`.
- `Eval.repeat` / `RepeatedEval` — *what:* run-N aggregation + flaky flagging;
  *depends on:* `Evaluator`, `EvalResult`. The aggregation math is pure and
  exhaustively unit-tested independently of any judge.
- `EvalSuite` — *what:* the case runner; *depends on:* `Evaluator`, `Eval.repeat`,
  `EvalResult`.

Each unit is understandable and testable without the others' internals; the two
trickiest pieces (PII regex set, variance aggregation) are pure and directly
unit-tested.
