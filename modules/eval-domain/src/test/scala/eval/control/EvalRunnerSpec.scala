package eval.control

import zio.*
import zio.test.*

import eval.entity.*

object EvalRunnerSpec extends ZIOSpecDefault:

  private val sampleCases = List(
    EvalCase("sum 2 + 2", "4"),
    EvalCase("capital of France", "Paris"),
    EvalCase("pick a color", "blue"),
  )

  def spec = suite("EvalRunner")(
    test("all-pass executor yields Pass verdicts and full passRate") {
      val executor: EvalRunner.Executor = prompt =>
        val answer = prompt match
          case p if p.contains("2 + 2") => "The answer is 4."
          case p if p.contains("France") => "Paris"
          case _                         => "blue sky"
        ZIO.succeed((answer, 0))
      for
        run <- EvalRunner.run("mem://", "stub", sampleCases, executor)
      yield
        assertTrue(run.summary.total == 3) &&
        assertTrue(run.summary.passed == 3) &&
        assertTrue(run.summary.passRate == 1.0) &&
        assertTrue(run.results.forall(_.passed))
    },
    test("non-zero exit code fails the case regardless of output match") {
      val executor: EvalRunner.Executor = _ => ZIO.succeed(("Paris", 1))
      for
        run <- EvalRunner.run("mem://", "stub", List(EvalCase("capital?", "Paris")), executor)
      yield
        assertTrue(run.summary.failed == 1) &&
        assertTrue(run.results.head.verdict == EvalVerdict.Fail) &&
        assertTrue(run.results.head.exitCode == 1)
    },
    test("executor error is captured as Fail with error message") {
      val executor: EvalRunner.Executor = _ => ZIO.fail(new RuntimeException("boom"))
      for
        run <- EvalRunner.run("mem://", "stub", List(EvalCase("p", "e")), executor)
      yield
        assertTrue(run.summary.failed == 1) &&
        assertTrue(run.results.head.error.contains("boom"))
    },
  )
