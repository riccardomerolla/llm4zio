# LLM-as-a-Judge (core eval layer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Layer 1 (deterministic checks) and Layer 2 (LLM-as-a-Judge with range-based variance) of the "three layers of evaluation" as a thin, composable `llm4zio.eval` package in `llm4zio-core`.

**Architecture:** One `Evaluator[-In]` primitive returns `IO[LlmError, EvalResult]`. Deterministic `Checks` are `Evaluator[String]` that never fail. `Judge.of(llm, dimensions)` is an `Evaluator[Sample]` that calls `executeStructured` for strict-JSON dimension scores. `Eval.repeat` runs an evaluator N times and flags wide-spread (flaky) dimensions. `EvalSuite.run` drives a list of cases for the test/CI harness; the gate surface is just `EvalResult.meets` + ordinary ZIO composition.

**Tech Stack:** Scala 3.8.3, ZIO 2.x, zio-json, ZIO Test. sbt 2.x.

## Global Constraints

- **ZIO-native, typed errors.** Effects are `IO[LlmError, A]`; no `Throwable` in signatures. Errors travel the typed channel. (Copied from CLAUDE.md / ADR-0002.)
- **`-Werror` / `-Wunused:all`** — unused imports are fatal. Import only what each file uses; prefer specific imports in `main` sources.
- **No `var`** — use `Ref`/immutable folds for state.
- **TDD.** Every behaviour is driven by a failing test first; use a `Mock`/stub `LlmService` for deterministic LLM behaviour. No network in tests.
- **Recoverable vs catastrophic.** A low or flaky score is a *value* (`EvalResult`/`RepeatedEval`/`SuiteReport`); an unreachable judge *fails* the effect (`LlmError`).
- **Package & module.** All production code lives under `modules/llm4zio-core/src/main/scala/llm4zio/eval/`; tests under `modules/llm4zio-core/src/test/scala/llm4zio/eval/`. sbt project id is `llm4zioCore`.
- **Scope.** No `llm4zio-flow` / `llm4zio-runner` / `examples/*.sc` changes — Layer 3 (behavioural) and flow gate sugar are a separate sub-project.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `eval/Eval.scala` | Value model (`Dimension`, `DimensionScore`, `EvalResult`, `Sample`); later the `Eval` object (`repeat`) + `RepeatedEval` + `DimensionStats`. | 1, then 5 |
| `eval/Evaluator.scala` | The `Evaluator[-In]` trait + `contramap` + `Evaluator.all`. | 2 |
| `eval/Checks.scala` | Layer 1 deterministic checks (`noPii`, `validJson`, `matches`, `lengthBetween`). | 3 |
| `eval/Judge.scala` | Layer 2: `Judge.of` + `JudgeResponse` DTO + prompt/schema/clamping. | 4 |
| `eval/EvalSuite.scala` | Harness: `EvalCase`, `CaseReport`, `SuiteReport`, `EvalSuite.run`. | 6 |

(This refines the spec's "~4 files" by giving the harness its own file rather than folding it into `Judge.scala` — `EvalSuite` is a distinct responsibility from the judge.)

---

### Task 1: Value model (`Eval.scala`)

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSpec.scala`

**Interfaces:**
- Produces:
  - `final case class Dimension(name: String, rubric: String, maxScore: Int = 2)`
  - `final case class DimensionScore(name: String, score: Int, reasoning: String = "")` (derives `JsonCodec`)
  - `final case class EvalResult(scores: List[DimensionScore], summary: String = "")` (derives `JsonCodec`) with `score(name): Option[Int]`, `total: Int`, `meets(minPerDimension: Int): Boolean`
  - `final case class Sample(response: String, query: Option[String] = None, context: Option[String] = None, expected: Option[String] = None)`

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSpec.scala`:

```scala
package llm4zio.eval

import zio.test.*

object EvalSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Eval value model")(
    test("score looks up a dimension by name") {
      val r = EvalResult(List(DimensionScore("correctness", 2), DimensionScore("safety", 1)))
      assertTrue(r.score("correctness").contains(2), r.score("safety").contains(1), r.score("missing").isEmpty)
    },
    test("total sums dimension scores") {
      val r = EvalResult(List(DimensionScore("a", 2), DimensionScore("b", 1)))
      assertTrue(r.total == 3)
    },
    test("meets is true only when every dimension reaches the minimum") {
      val r = EvalResult(List(DimensionScore("a", 2), DimensionScore("b", 1)))
      assertTrue(r.meets(1), !r.meets(2))
    },
    test("meets on an empty result is vacuously true") {
      assertTrue(EvalResult(Nil).meets(2))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvalSpec'`
Expected: FAIL — compile error, `EvalResult`/`DimensionScore` not found.

- [ ] **Step 3: Write minimal implementation**

Create `modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala`:

```scala
package llm4zio.eval

import zio.json.JsonCodec

/** One scored axis of an evaluation: how to score it (`rubric`) and the top of its scale (`maxScore`, default 0–2). */
final case class Dimension(name: String, rubric: String, maxScore: Int = 2)

/** A model's score on one dimension, with a one-line justification. */
final case class DimensionScore(name: String, score: Int, reasoning: String = "") derives JsonCodec

/** The outcome of evaluating one input across dimensions. */
final case class EvalResult(scores: List[DimensionScore], summary: String = "") derives JsonCodec:
  def score(name: String): Option[Int] = scores.find(_.name == name).map(_.score)
  def total: Int                       = scores.map(_.score).sum
  /** Gate predicate: every dimension scored at least `minPerDimension`. Empty ⇒ vacuously true. */
  def meets(minPerDimension: Int): Boolean = scores.forall(_.score >= minPerDimension)

/** The default judge input: the response under test plus the optional material to judge it against. */
final case class Sample(
  response: String,
  query: Option[String] = None,
  context: Option[String] = None,
  expected: Option[String] = None,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvalSpec'`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSpec.scala
git commit -m "feat(eval): value model — Dimension, DimensionScore, EvalResult, Sample

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: The `Evaluator` primitive (`Evaluator.scala`)

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/eval/Evaluator.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvaluatorSpec.scala`

**Interfaces:**
- Consumes: `EvalResult`, `DimensionScore` (Task 1); `LlmError` (`llm4zio.core`).
- Produces:
  - `trait Evaluator[-In] { def evaluate(input: In): IO[LlmError, EvalResult]; def contramap[In2](f: In2 => In): Evaluator[In2] }`
  - `object Evaluator { def all[In](es: Evaluator[In]*): Evaluator[In] }`

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvaluatorSpec.scala`:

```scala
package llm4zio.eval

import zio.*
import zio.test.*

object EvaluatorSpec extends ZIOSpecDefault:

  private def constEval(score: DimensionScore, summary: String = ""): Evaluator[String] =
    (_: String) => ZIO.succeed(EvalResult(List(score), summary))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Evaluator")(
    test("all runs every evaluator and merges their dimensions and summaries") {
      val e = Evaluator.all(
        constEval(DimensionScore("a", 2), "left"),
        constEval(DimensionScore("b", 1), "right"),
      )
      for r <- e.evaluate("x")
      yield assertTrue(
        r.scores == List(DimensionScore("a", 2), DimensionScore("b", 1)),
        r.summary == "left; right",
      )
    },
    test("all drops empty summaries when joining") {
      val e = Evaluator.all(constEval(DimensionScore("a", 2)), constEval(DimensionScore("b", 1), "only"))
      for r <- e.evaluate("x")
      yield assertTrue(r.summary == "only")
    },
    test("contramap adapts the input type") {
      val onString: Evaluator[String] = (s: String) => ZIO.succeed(EvalResult(List(DimensionScore("len", s.length))))
      val onSample: Evaluator[Sample] = onString.contramap(_.response)
      for r <- onSample.evaluate(Sample(response = "abcd"))
      yield assertTrue(r.score("len").contains(4))
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvaluatorSpec'`
Expected: FAIL — compile error, `Evaluator` not found.

- [ ] **Step 3: Write minimal implementation**

Create `modules/llm4zio-core/src/main/scala/llm4zio/eval/Evaluator.scala`:

```scala
package llm4zio.eval

import zio.{ IO, ZIO }

import llm4zio.core.LlmError

/** Scores an input into an [[EvalResult]]. Contravariant in `In` so a String-based check adapts to a richer input via
  * [[contramap]]. A deterministic evaluator simply never fails (`ZIO.succeed`); an LLM judge fails as its backend does.
  */
trait Evaluator[-In]:
  def evaluate(input: In): IO[LlmError, EvalResult]

  /** View this evaluator through a projection of a wider input — e.g. an `Evaluator[String]` over `_.response`. */
  def contramap[In2](f: In2 => In): Evaluator[In2] =
    (in2: In2) => evaluate(f(in2))

object Evaluator:
  /** Run every evaluator on the same input; concatenate their dimension scores and join non-empty summaries. */
  def all[In](es: Evaluator[In]*): Evaluator[In] =
    (in: In) =>
      ZIO.foreach(es.toList)(_.evaluate(in)).map { results =>
        EvalResult(
          scores = results.flatMap(_.scores),
          summary = results.map(_.summary).filter(_.nonEmpty).mkString("; "),
        )
      }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvaluatorSpec'`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/Evaluator.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/EvaluatorSpec.scala
git commit -m "feat(eval): Evaluator primitive — contramap + all

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Layer 1 deterministic checks (`Checks.scala`)

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/eval/Checks.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/ChecksSpec.scala`

**Interfaces:**
- Consumes: `Evaluator`, `EvalResult`, `DimensionScore` (Tasks 1–2).
- Produces (each returns `Evaluator[String]`, scoring `0` or `maxScore`):
  - `Checks.noPii(maxScore: Int = 2)`
  - `Checks.validJson(maxScore: Int = 2)`
  - `Checks.matches(regex: String, name: String = "format", maxScore: Int = 2)`
  - `Checks.lengthBetween(min: Int, max: Int, maxScore: Int = 2)`

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/ChecksSpec.scala`:

```scala
package llm4zio.eval

import zio.test.*

object ChecksSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Checks (Layer 1 deterministic)")(
    test("noPii scores max on clean text and 0 when PII is present") {
      for
        clean <- Checks.noPii().evaluate("The weather is fine today.")
        email <- Checks.noPii().evaluate("contact me at jane.doe@example.com")
        ssn   <- Checks.noPii().evaluate("my ssn is 123-45-6789")
        card  <- Checks.noPii().evaluate("card 4111 1111 1111 1111")
        ip    <- Checks.noPii().evaluate("host at 192.168.0.1")
      yield assertTrue(
        clean.score("no-pii").contains(2),
        email.score("no-pii").contains(0),
        ssn.score("no-pii").contains(0),
        card.score("no-pii").contains(0),
        ip.score("no-pii").contains(0),
        email.scores.head.reasoning.contains("email"),
      )
    },
    test("validJson passes valid JSON and fails non-JSON") {
      for
        ok  <- Checks.validJson().evaluate("""{"a":1}""")
        bad <- Checks.validJson().evaluate("not json at all")
      yield assertTrue(ok.score("valid-json").contains(2), bad.score("valid-json").contains(0))
    },
    test("matches checks a whole-string regex") {
      for
        ok  <- Checks.matches("[a-z]+", name = "lower").evaluate("abc")
        bad <- Checks.matches("[a-z]+", name = "lower").evaluate("abc123")
      yield assertTrue(ok.score("lower").contains(2), bad.score("lower").contains(0))
    },
    test("lengthBetween respects inclusive bounds") {
      for
        inside <- Checks.lengthBetween(2, 4).evaluate("abc")
        atMin  <- Checks.lengthBetween(2, 4).evaluate("ab")
        atMax  <- Checks.lengthBetween(2, 4).evaluate("abcd")
        over   <- Checks.lengthBetween(2, 4).evaluate("abcde")
      yield assertTrue(
        inside.score("length").contains(2),
        atMin.score("length").contains(2),
        atMax.score("length").contains(2),
        over.score("length").contains(0),
      )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.ChecksSpec'`
Expected: FAIL — compile error, `Checks` not found.

- [ ] **Step 3: Write minimal implementation**

Create `modules/llm4zio-core/src/main/scala/llm4zio/eval/Checks.scala`:

```scala
package llm4zio.eval

import scala.util.matching.Regex

import zio.ZIO
import zio.json.*
import zio.json.ast.Json

/** Layer 1 — deterministic, pure checks. Each is an `Evaluator[String]` scoring `0` or `maxScore`; they never fail.
  * Adapt to a richer input with `.contramap`, e.g. `Checks.noPii().contramap[Sample](_.response)`.
  */
object Checks:

  /** Common PII shapes. Regex-only (NER is out of scope); extend by composing your own `matches`. */
  private val piiPatterns: List[(String, Regex)] = List(
    "email"       -> """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""".r,
    "ssn"         -> """\b\d{3}-\d{2}-\d{4}\b""".r,
    "credit-card" -> """\b(?:\d[ -]?){13,16}\b""".r,
    "phone"       -> """\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""".r,
    "ipv4"        -> """\b(?:\d{1,3}\.){3}\d{1,3}\b""".r,
  )

  private def one(name: String, ok: Boolean, maxScore: Int, why: String): EvalResult =
    EvalResult(List(DimensionScore(name, if ok then maxScore else 0, why)))

  /** No common PII (email, SSN, credit-card, phone, IPv4). */
  def noPii(maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val hits = piiPatterns.collect { case (label, re) if re.findFirstIn(s).isDefined => label }
      ZIO.succeed(
        one("no-pii", hits.isEmpty, maxScore, if hits.isEmpty then "no PII detected" else s"matched: ${hits.mkString(", ")}")
      )

  /** Output parses as JSON. */
  def validJson(maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val ok = s.fromJson[Json].isRight
      ZIO.succeed(one("valid-json", ok, maxScore, if ok then "parses as JSON" else "not valid JSON"))

  /** Output fully matches `regex` (`String.matches` — whole-string match, the format-validation case). */
  def matches(regex: String, name: String = "format", maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val ok = s.matches(regex)
      ZIO.succeed(one(name, ok, maxScore, if ok then s"matches /$regex/" else s"does not match /$regex/"))

  /** Length within `[min, max]` inclusive. */
  def lengthBetween(min: Int, max: Int, maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val n = s.length
      ZIO.succeed(one("length", n >= min && n <= max, maxScore, s"length $n; bounds [$min, $max]"))
```

> **`-Wunused` note:** the pure `one` helper references no `ZIO`; `ZIO.succeed` is used by the four public methods, so `import zio.ZIO` is the only zio import this file needs (plus `zio.json.*` + `zio.json.ast.Json` for `validJson`, and `scala.util.matching.Regex`).

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.ChecksSpec'`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/Checks.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/ChecksSpec.scala
git commit -m "feat(eval): Layer 1 deterministic checks — noPii, validJson, matches, lengthBetween

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Layer 2 — the LLM-as-a-Judge (`Judge.scala`)

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/eval/Judge.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/JudgeSpec.scala`

**Interfaces:**
- Consumes: `Evaluator`, `Dimension`, `DimensionScore`, `EvalResult`, `Sample` (Tasks 1–2); `LlmService`, `LlmError`, `SchemaDerivation`, `StructuredOutputs` (`llm4zio.core`); `JsonSchema` (`llm4zio.tools`).
- Produces:
  - `final case class JudgeResponse(scores: List[DimensionScore])` (derives `JsonCodec`)
  - `object Judge { val defaultSystem: String; def of(llm: LlmService, dimensions: List[Dimension], system: String = defaultSystem): Evaluator[Sample] }`
- Behaviour: scores clamped to `0..maxScore`; a dimension the model omitted is reported `score = 0`, `reasoning = "missing"`; the built prompt contains each dimension's name, scale `0..maxScore`, and rubric.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/JudgeSpec.scala`:

```scala
package llm4zio.eval

import zio.*
import zio.json.*
import zio.stream.{ Stream, ZStream }
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object JudgeSpec extends ZIOSpecDefault:

  /** A minimal LlmService that records the last structured prompt and returns a fixed JSON body, parsed with the real
    * StructuredOutputs path (so a malformed body yields a genuine ParseError).
    */
  private def stubLlm(json: String, promptRef: Ref[String]): LlmService = new LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(None, Nil, "stop"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      promptRef.set(prompt) *> StructuredOutputs.parseFromText[A](json, schema)
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val dims = List(
    Dimension("correctness", "Does the response match the expected outcome?"),
    Dimension("safety", "Does the response avoid PII leakage?"),
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Judge (Layer 2)")(
    test("maps dimension scores from the model response") {
      val json = """{"scores":[{"name":"correctness","score":2,"reasoning":"matches"},
                   |{"name":"safety","score":1,"reasoning":"borderline"}]}""".stripMargin
      for
        ref <- Ref.make("")
        r   <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("the answer"))
      yield assertTrue(
        r.score("correctness").contains(2),
        r.score("safety").contains(1),
      )
    },
    test("clamps an out-of-range score and reports a missing dimension as 0") {
      val json = """{"scores":[{"name":"correctness","score":3,"reasoning":"too high"}]}"""
      for
        ref <- Ref.make("")
        r   <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("x"))
      yield assertTrue(
        r.score("correctness").contains(2),                       // clamped 3 -> maxScore 2
        r.score("safety").contains(0),                            // omitted -> 0
        r.scores.find(_.name == "safety").exists(_.reasoning == "missing"),
      )
    },
    test("the prompt carries each dimension's rubric and scale") {
      val json = """{"scores":[]}"""
      for
        ref    <- Ref.make("")
        _      <- Judge.of(stubLlm(json, ref), dims).evaluate(Sample("x", query = Some("q")))
        prompt <- ref.get
      yield assertTrue(
        prompt.contains("Does the response match the expected outcome?"),
        prompt.contains("Does the response avoid PII leakage?"),
        prompt.contains("0..2"),
        prompt.contains("q"),
      )
    },
    test("a non-JSON response fails with a parse error") {
      for
        ref  <- Ref.make("")
        exit <- Judge.of(stubLlm("sorry, I cannot comply", ref), dims).evaluate(Sample("x")).exit
      yield assertTrue(exit.isFailure)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.JudgeSpec'`
Expected: FAIL — compile error, `Judge` not found.

- [ ] **Step 3: Write minimal implementation**

Create `modules/llm4zio-core/src/main/scala/llm4zio/eval/Judge.scala`:

```scala
package llm4zio.eval

import zio.json.JsonCodec

import llm4zio.core.{ LlmService, SchemaDerivation }

/** The wire shape the judge model returns. */
final case class JudgeResponse(scores: List[DimensionScore]) derives JsonCodec

/** Layer 2 — the LLM-as-a-Judge. Builds a strict-JSON scoring prompt from `dimensions`, asks the model for per-dimension
  * scores via `executeStructured`, then clamps each score to its dimension scale and fills any omitted dimension with 0.
  */
object Judge:

  val defaultSystem: String =
    "You are an impartial evaluator. Score each dimension strictly on its rubric; when unsure, score lower. " +
      "Return ONLY valid JSON, no prose."

  def of(llm: LlmService, dimensions: List[Dimension], system: String = defaultSystem): Evaluator[Sample] =
    (sample: Sample) =>
      llm
        .executeStructured[JudgeResponse](buildPrompt(system, dimensions, sample), SchemaDerivation.derive[JudgeResponse])
        .map(toResult(dimensions, _))

  private def buildPrompt(system: String, dimensions: List[Dimension], sample: Sample): String =
    val dims = dimensions.map(d => s"- ${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
    val user = List(
      sample.query.map(q => s"Query: $q"),
      sample.context.map(c => s"Context: $c"),
      Some(s"Response: ${sample.response}"),
      sample.expected.map(e => s"Expected: $e"),
    ).flatten.mkString("\n")
    s"""$system
       |
       |Score each dimension on its 0..max scale per the rubric:
       |$dims
       |
       |Return ONLY this JSON: {"scores":[{"name":"<dimension>","score":<int>,"reasoning":"<one sentence>"}]}
       |
       |$user""".stripMargin

  private def toResult(dimensions: List[Dimension], response: JudgeResponse): EvalResult =
    val byName = response.scores.map(s => s.name -> s).toMap
    val scores = dimensions.map { d =>
      byName.get(d.name) match
        case Some(s) => DimensionScore(d.name, s.score.max(0).min(d.maxScore), s.reasoning)
        case None    => DimensionScore(d.name, 0, "missing")
    }
    EvalResult(scores)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.JudgeSpec'`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/Judge.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/JudgeSpec.scala
git commit -m "feat(eval): Layer 2 LLM-as-a-Judge — scored dimensions, clamping, missing-dim handling

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Variance / repeat-N (extend `Eval.scala`)

**Files:**
- Modify: `modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala` (append the `Eval` object + `RepeatedEval` + `DimensionStats`)
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/VarianceSpec.scala`

**Interfaces:**
- Consumes: `Evaluator`, `EvalResult`, `DimensionScore` (Tasks 1–2); `LlmError` (`llm4zio.core`).
- Produces:
  - `final case class DimensionStats(name: String, median: Int, min: Int, max: Int)` with `spread: Int`, `isFlaky(threshold: Int): Boolean`
  - `final case class RepeatedEval(runs: List[EvalResult], stats: List[DimensionStats], spreadThreshold: Int)` with `flakyDimensions: List[String]`, `isFlaky: Boolean`, `aggregate: EvalResult`
  - `object Eval { def repeat[In](e: Evaluator[In], input: In, n: Int = 3, spreadThreshold: Int = 1): IO[LlmError, RepeatedEval] }`
- Behaviour: per dimension across the N runs, `median` is the lower-middle of the sorted scores (`sorted((size-1)/2)`); `spread = max - min`; a dimension is flaky when `spread > spreadThreshold`; `aggregate` rebuilds an `EvalResult` from the per-dimension medians.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/VarianceSpec.scala`:

```scala
package llm4zio.eval

import zio.*
import zio.test.*

object VarianceSpec extends ZIOSpecDefault:

  /** An Evaluator that returns the supplied results in order, one per call (ignoring the input). */
  private def seqEvaluator(results: List[EvalResult]): UIO[Evaluator[Unit]] =
    Ref.make(results).map { ref =>
      (_: Unit) =>
        ref.modify {
          case head :: tail => (head, tail)
          case Nil          => (EvalResult(Nil), Nil)
        }
    }

  private def one(name: String, score: Int): EvalResult = EvalResult(List(DimensionScore(name, score)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Variance (repeat-N)")(
    test("aggregates median/min/max and flags a wide-spread dimension") {
      for
        e   <- seqEvaluator(List(one("correctness", 2), one("correctness", 0), one("correctness", 1)))
        rep <- Eval.repeat(e, (), n = 3, spreadThreshold = 1)
      yield
        val s = rep.stats.head
        assertTrue(
          s.name == "correctness",
          s.median == 1,
          s.min == 0,
          s.max == 2,
          s.spread == 2,
          rep.isFlaky,
          rep.flakyDimensions == List("correctness"),
          rep.aggregate.score("correctness").contains(1),
        )
    },
    test("a higher threshold tolerates the same spread") {
      for
        e   <- seqEvaluator(List(one("correctness", 2), one("correctness", 0), one("correctness", 1)))
        rep <- Eval.repeat(e, (), n = 3, spreadThreshold = 2)
      yield assertTrue(!rep.isFlaky)
    },
    test("identical scores are never flaky and the median equals the score") {
      for
        e   <- seqEvaluator(List(one("a", 2), one("a", 2), one("a", 2)))
        rep <- Eval.repeat(e, (), n = 3, spreadThreshold = 1)
      yield assertTrue(!rep.isFlaky, rep.stats.head.median == 2, rep.stats.head.spread == 0)
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.VarianceSpec'`
Expected: FAIL — compile error, `Eval`/`RepeatedEval`/`DimensionStats` not found.

- [ ] **Step 3: Write minimal implementation**

Append to `modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala`. First add the imports at the top of the file (below the existing `import zio.json.JsonCodec`):

```scala
import zio.{ IO, ZIO }

import llm4zio.core.LlmError
```

Then append these declarations to the end of the file:

```scala
/** Per-dimension stats across N repeated evaluations. */
final case class DimensionStats(name: String, median: Int, min: Int, max: Int):
  def spread: Int                      = max - min
  def isFlaky(threshold: Int): Boolean = spread > threshold

/** The result of running an evaluator N times: the raw runs, per-dimension stats, and the spread threshold used. */
final case class RepeatedEval(runs: List[EvalResult], stats: List[DimensionStats], spreadThreshold: Int):
  def flakyDimensions: List[String] = stats.filter(_.isFlaky(spreadThreshold)).map(_.name)
  def isFlaky: Boolean              = flakyDimensions.nonEmpty
  /** A single representative result built from per-dimension medians. */
  def aggregate: EvalResult =
    EvalResult(stats.map(s => DimensionScore(s.name, s.median, s"median of ${runs.size} runs")), s"aggregate of ${runs.size} runs")

object Eval:
  /** Run `e` on `input` `n` times; aggregate per-dimension median/min/max and flag dimensions whose spread exceeds
    * `spreadThreshold` ("flaky"). The talk's non-determinism fix: run each case N times, flag variance above a bound.
    */
  def repeat[In](e: Evaluator[In], input: In, n: Int = 3, spreadThreshold: Int = 1): IO[LlmError, RepeatedEval] =
    ZIO.foreach((1 to n).toList)(_ => e.evaluate(input)).map { runs =>
      val names = runs.flatMap(_.scores.map(_.name)).distinct
      val stats = names.map { name =>
        val vals = runs.flatMap(_.score(name)).sorted
        DimensionStats(name, median(vals), vals.headOption.getOrElse(0), vals.lastOption.getOrElse(0))
      }
      RepeatedEval(runs, stats, spreadThreshold)
    }

  /** Lower-middle element of the sorted scores (integer buckets; no fractional median). Empty ⇒ 0. */
  private def median(sorted: List[Int]): Int =
    if sorted.isEmpty then 0 else sorted((sorted.size - 1) / 2)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.VarianceSpec'`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/Eval.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/VarianceSpec.scala
git commit -m "feat(eval): repeat-N variance — RepeatedEval, DimensionStats, Eval.repeat

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: The harness (`EvalSuite.scala`)

**Files:**
- Create: `modules/llm4zio-core/src/main/scala/llm4zio/eval/EvalSuite.scala`
- Test: `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSuiteSpec.scala`

**Interfaces:**
- Consumes: `Evaluator`, `EvalResult`, `Eval.repeat`, `RepeatedEval` (Tasks 1–5); `LlmError` (`llm4zio.core`).
- Produces:
  - `final case class EvalCase[In](name: String, input: In, minPerDimension: Int = 1)`
  - `final case class CaseReport(name: String, result: EvalResult, repeated: Option[RepeatedEval], passed: Boolean)`
  - `final case class SuiteReport(cases: List[CaseReport])` with `passed: Boolean`, `failures: List[CaseReport]`
  - `object EvalSuite { def run[In](e: Evaluator[In], cases: List[EvalCase[In]], repeats: Int = 1, spreadThreshold: Int = 1): IO[LlmError, SuiteReport] }`
- Behaviour: with `repeats <= 1`, evaluate once and pass when the result `meets(minPerDimension)`; with `repeats > 1`, run via `Eval.repeat` and pass when the *aggregate* meets the threshold **and** the case is not flaky.

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSuiteSpec.scala`:

```scala
package llm4zio.eval

import zio.*
import zio.test.*

object EvalSuiteSpec extends ZIOSpecDefault:

  private def seqEvaluator(results: List[EvalResult]): UIO[Evaluator[String]] =
    Ref.make(results).map { ref =>
      (_: String) =>
        ref.modify {
          case head :: tail => (head, tail)
          case Nil          => (EvalResult(Nil), Nil)
        }
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("EvalSuite (harness)")(
    test("a single-shot suite passes only when every case meets its threshold") {
      val e = Checks.lengthBetween(3, 100)
      val cases = List(
        EvalCase("good", "good text", minPerDimension = 1),
        EvalCase("short", "no", minPerDimension = 1),
      )
      for report <- EvalSuite.run(e, cases)
      yield assertTrue(
        !report.passed,
        report.failures.map(_.name) == List("short"),
        report.cases.find(_.name == "good").exists(_.passed),
      )
    },
    test("with repeats, a high-scoring but flaky case is reported as failed") {
      val results = List(
        EvalResult(List(DimensionScore("q", 2))),
        EvalResult(List(DimensionScore("q", 0))),
        EvalResult(List(DimensionScore("q", 2))),
      )
      for
        e      <- seqEvaluator(results)
        report <- EvalSuite.run(e, List(EvalCase("flaky", "input", minPerDimension = 1)), repeats = 3, spreadThreshold = 1)
      yield
        val c = report.cases.head
        assertTrue(
          !report.passed,
          c.repeated.exists(_.isFlaky),
          c.result.score("q").contains(2),   // aggregate median meets the threshold...
          !c.passed,                          // ...but flakiness fails the case
        )
    },
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvalSuiteSpec'`
Expected: FAIL — compile error, `EvalSuite`/`EvalCase` not found.

- [ ] **Step 3: Write minimal implementation**

Create `modules/llm4zio-core/src/main/scala/llm4zio/eval/EvalSuite.scala`:

```scala
package llm4zio.eval

import zio.{ IO, ZIO }

import llm4zio.core.LlmError

/** One evaluation case: a named input and the per-dimension minimum it must reach to pass. */
final case class EvalCase[In](name: String, input: In, minPerDimension: Int = 1)

/** The outcome for one case: its (aggregate, when repeated) result, the repeated stats if N>1, and pass/fail. */
final case class CaseReport(name: String, result: EvalResult, repeated: Option[RepeatedEval], passed: Boolean)

/** The outcome of a whole suite. `passed` ⇒ every case passed. */
final case class SuiteReport(cases: List[CaseReport]):
  def passed: Boolean            = cases.forall(_.passed)
  def failures: List[CaseReport] = cases.filterNot(_.passed)

/** The test/CI harness: run an evaluator over a list of cases. With `repeats > 1`, each case runs through
  * [[Eval.repeat]] and passes only when its aggregate meets the threshold AND it is not flaky.
  */
object EvalSuite:
  def run[In](
    e: Evaluator[In],
    cases: List[EvalCase[In]],
    repeats: Int = 1,
    spreadThreshold: Int = 1,
  ): IO[LlmError, SuiteReport] =
    ZIO
      .foreach(cases) { c =>
        if repeats <= 1 then e.evaluate(c.input).map(r => CaseReport(c.name, r, None, r.meets(c.minPerDimension)))
        else
          Eval.repeat(e, c.input, repeats, spreadThreshold).map { rep =>
            val agg = rep.aggregate
            CaseReport(c.name, agg, Some(rep), agg.meets(c.minPerDimension) && !rep.isFlaky)
          }
      }
      .map(SuiteReport(_))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt 'llm4zioCore/testOnly llm4zio.eval.EvalSuiteSpec'`
Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add modules/llm4zio-core/src/main/scala/llm4zio/eval/EvalSuite.scala \
        modules/llm4zio-core/src/test/scala/llm4zio/eval/EvalSuiteSpec.scala
git commit -m "feat(eval): EvalSuite harness — cases, threshold + flaky pass/fail

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Full-module verification

**Files:** none (verification only).

- [ ] **Step 1: Run the whole core suite (forced full run)**

Run: `sbt llm4zioCore/testFull`
Expected: PASS — all existing core specs plus the six new `llm4zio.eval` specs.

- [ ] **Step 2: Verify formatting and lint**

Run: `sbt fmt && sbt check`
Expected: no changes needed / `check` succeeds (scalafmt + scalafix clean, no `-Wunused` failures).

- [ ] **Step 3: Commit any formatting changes**

```bash
git add -A
git commit -m "chore(eval): scalafmt/scalafix the new eval package

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" || echo "nothing to format"
```

---

## Self-Review

**Spec coverage:**
- Shared `Evaluator` abstraction (contramap, all) → Task 2. ✓
- Data model `Dimension`/`DimensionScore`/`EvalResult`/`Sample` with `meets` gate predicate → Task 1. ✓
- Layer 1 deterministic checks (noPii regex / validJson / matches / lengthBetween) → Task 3. ✓
- Layer 2 `Judge.of` with user-defined dims, strict-JSON via `executeStructured`, clamping + missing-dim-as-0 → Task 4. ✓
- Range-based variance (`Eval.repeat`, `RepeatedEval`, `DimensionStats`, median + spread + flaky) → Task 5. ✓
- Harness surface (`EvalSuite`/`EvalCase`/`SuiteReport`, threshold + flaky pass/fail) → Task 6. ✓
- Gate surface = `EvalResult.meets` + ZIO composition → delivered in Task 1 (`meets`); documented as a pattern, no new machinery (per spec). ✓
- Error handling: deterministic checks never fail; judge fails `LlmError`; low/flaky is a value → enforced by types across Tasks 3–6 and the parse-failure test in Task 4. ✓
- Mock/stub provider for offline judge tests → Task 4 `stubLlm`. ✓
- Out of scope (flow/runner/.sc, NER, schema-conformance check) → no tasks, as intended. ✓

**Placeholder scan:** No "TBD"/"add error handling"/"similar to Task N"/"write tests for the above". Every code step shows complete, compilable code; every run step has an exact command and expected outcome.

**Type consistency:** `Evaluator.evaluate`/`contramap`/`Evaluator.all`, `EvalResult.meets`/`score`/`total`, `Eval.repeat`, `RepeatedEval.aggregate`/`isFlaky`/`flakyDimensions`, `DimensionStats.spread`/`isFlaky`, `Judge.of`/`JudgeResponse`, `EvalSuite.run`/`EvalCase`/`CaseReport`/`SuiteReport` — names and signatures match between their producing task and every consuming task and test.
