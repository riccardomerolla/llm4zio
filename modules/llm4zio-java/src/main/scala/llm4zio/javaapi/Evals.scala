package llm4zio.javaapi

import scala.jdk.CollectionConverters.SeqHasAsJava

import llm4zio.eval.*

/** Java-facing builders and combinators for the eval subsystem. Evaluators are opaque values here — build them with
  * these helpers (or [[JavaFlow.judge]]) and hand them to [[JavaFlow.evaluate]] / [[JavaFlow.runSuite]] to run.
  */
object Evals:
  /** A scored axis with the default 0–2 scale. */
  def dimension(name: String, rubric: String): Dimension = Dimension(name, rubric)

  /** A scored axis with an explicit top of scale. */
  def dimension(name: String, rubric: String, maxScore: Int): Dimension = Dimension(name, rubric, maxScore)

  /** A judge input carrying only the response under test. */
  def sample(response: String): Sample = Sample(response)

  /** A judge input with the optional material to judge against (`null` for absent). */
  def sample(response: String, query: String, expected: String): Sample =
    Sample(response, query = Option(query), expected = Option(expected))

  /** A judge input with retrieved context too (`null` for absent) — the groundedness axis judges against it. */
  def sample(response: String, query: String, context: String, expected: String): Sample =
    Sample(response, query = Option(query), context = Option(context), expected = Option(expected))

  /** A named suite case with the default per-dimension bar (1). */
  def evalCase(name: String, input: Sample): EvalCase[Sample] = EvalCase(name, input)

  /** A named suite case with an explicit per-dimension bar. */
  def evalCase(name: String, input: Sample, minPerDimension: Int): EvalCase[Sample] =
    EvalCase(name, input, minPerDimension)

  /** The deterministic no-PII check, viewed over a sample's response — Layer 1 of a layered suite. */
  def noPiiOnResponse(): Evaluator[Sample] = Checks.noPii().contramap(_.response)

  /** Every evaluator on the same input, dimension scores concatenated. */
  @annotation.varargs
  def all(es: Evaluator[Sample]*): Evaluator[Sample] = Evaluator.all(es*)

  /** The dimensions of `result` scoring under `bar` — what a gate loop feeds back to the coder. */
  def belowBar(result: EvalResult, bar: Int): java.util.List[DimensionScore] =
    result.scores.filter(_.score < bar).asJava

  /** A suite report's per-case reports, for Java iteration. */
  def caseReports(report: SuiteReport): java.util.List[CaseReport] = report.cases.asJava
