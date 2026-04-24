package eval.control

import eval.entity.EvalVerdict

/** Decides whether `actual` satisfies `expected` for a given eval case.
  *
  * The default implementation is a case-insensitive substring match — good enough for smoke-level evals where an agent
  * prints surrounding chrome (prompt echo, trace output, etc.) around the expected answer. Stricter scorers (exact
  * match, LLM-as-judge) can be swapped in without changing the runner.
  */
trait EvalScorer:
  def score(expected: String, actual: String): EvalVerdict

object EvalScorer:
  val substringCaseInsensitive: EvalScorer = new EvalScorer:
    def score(expected: String, actual: String): EvalVerdict =
      if actual.toLowerCase.contains(expected.trim.toLowerCase) then EvalVerdict.Pass
      else EvalVerdict.Fail

  val exactMatch: EvalScorer = new EvalScorer:
    def score(expected: String, actual: String): EvalVerdict =
      if actual.trim == expected.trim then EvalVerdict.Pass
      else EvalVerdict.Fail
