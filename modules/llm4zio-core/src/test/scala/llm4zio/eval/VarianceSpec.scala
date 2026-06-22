package llm4zio.eval

import zio.*
import zio.test.*

object VarianceSpec extends ZIOSpecDefault:

  /** An Evaluator that returns the supplied results in order, one per call (ignoring the input). */
  private def seqEvaluator(results: List[EvalResult]): UIO[Evaluator[Unit]] =
    Ref.make(results).map { ref => (_: Unit) =>
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
          rep.aggregate.summary == "aggregate of 3 runs",
          rep.aggregate.scores.head.reasoning == "median of 3 runs",
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
