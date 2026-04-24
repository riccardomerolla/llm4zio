package eval.control

import eval.entity.*

/** Diff between two [[EvalRun]]s, keyed by prompt. A regression is any case that passed in `baseline` but failed in
  * `candidate`; an improvement is the reverse.
  */
object EvalCompare:

  final case class Row(
    prompt: String,
    baseline: Option[EvalVerdict],
    candidate: Option[EvalVerdict],
  ):
    def status: Status =
      (baseline, candidate) match
        case (Some(EvalVerdict.Pass), Some(EvalVerdict.Fail)) => Status.Regression
        case (Some(EvalVerdict.Fail), Some(EvalVerdict.Pass)) => Status.Improvement
        case (Some(a), Some(b)) if a == b                     => Status.Unchanged
        case (None, Some(_))                                  => Status.Added
        case (Some(_), None)                                  => Status.Removed
        case _                                                => Status.Unchanged

  enum Status:
    case Unchanged, Improvement, Regression, Added, Removed

  final case class Report(rows: List[Row]):
    def regressions: List[Row]  = rows.filter(_.status == Status.Regression)
    def improvements: List[Row] = rows.filter(_.status == Status.Improvement)
    def hasRegressions: Boolean = regressions.nonEmpty

  def compare(baseline: EvalRun, candidate: EvalRun): Report =
    val byPrompt    = (run: EvalRun) => run.results.map(r => r.prompt -> r.verdict).toMap
    val baselineMap = byPrompt(baseline)
    val candMap     = byPrompt(candidate)
    val prompts     = (baselineMap.keySet ++ candMap.keySet).toList.sorted
    Report(prompts.map(p => Row(p, baselineMap.get(p), candMap.get(p))))
