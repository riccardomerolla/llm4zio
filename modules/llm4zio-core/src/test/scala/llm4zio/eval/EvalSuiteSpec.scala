package llm4zio.eval

import zio.*
import zio.test.*

object EvalSuiteSpec extends ZIOSpecDefault:

  private def seqEvaluator(results: List[EvalResult]): UIO[Evaluator[String]] =
    Ref.make(results).map { ref => (_: String) =>
      ref.modify {
        case head :: tail => (head, tail)
        case Nil          => (EvalResult(Nil), Nil)
      }
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("EvalSuite (harness)")(
    test("a single-shot suite passes only when every case meets its threshold") {
      val e     = Checks.lengthBetween(3, 100)
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
        report <-
          EvalSuite.run(e, List(EvalCase("flaky", "input", minPerDimension = 1)), repeats = 3, spreadThreshold = 1)
      yield
        val c = report.cases.head
        assertTrue(
          !report.passed,
          c.repeated.exists(_.isFlaky),
          c.result.score("q").contains(2), // aggregate median meets the threshold...
          !c.passed, // ...but flakiness fails the case
        )
    },
  )
