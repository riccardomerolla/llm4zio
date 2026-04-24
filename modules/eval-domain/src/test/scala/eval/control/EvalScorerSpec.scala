package eval.control

import zio.test.*

import eval.entity.EvalVerdict

object EvalScorerSpec extends ZIOSpecDefault:

  def spec = suite("EvalScorer")(
    test("substringCaseInsensitive passes when expected is contained (case-insensitive)") {
      val s = EvalScorer.substringCaseInsensitive
      assertTrue(s.score("Paris", "The capital of France is paris.") == EvalVerdict.Pass) &&
      assertTrue(s.score("42", "the answer is 42, not 41") == EvalVerdict.Pass)
    },
    test("substringCaseInsensitive fails when expected is absent") {
      val s = EvalScorer.substringCaseInsensitive
      assertTrue(s.score("Paris", "Lyon") == EvalVerdict.Fail)
    },
    test("exactMatch passes only on full equality after trim") {
      val s = EvalScorer.exactMatch
      assertTrue(s.score("42", "  42 ") == EvalVerdict.Pass) &&
      assertTrue(s.score("42", "42!") == EvalVerdict.Fail)
    },
  )
