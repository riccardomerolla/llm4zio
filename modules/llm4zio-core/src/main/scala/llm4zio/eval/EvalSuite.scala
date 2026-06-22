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
