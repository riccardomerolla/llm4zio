package llm4zio.eval

import zio.Scope
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
