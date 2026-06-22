package llm4zio.eval

import zio.*
import zio.test.*

import llm4zio.eval.Sample

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
