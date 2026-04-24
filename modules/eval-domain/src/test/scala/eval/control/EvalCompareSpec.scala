package eval.control

import java.time.Instant

import zio.test.*

import eval.entity.*

object EvalCompareSpec extends ZIOSpecDefault:

  private def run(verdicts: List[(String, EvalVerdict)]): EvalRun =
    val results = verdicts.map { case (prompt, v) =>
      EvalCaseResult(prompt, expected = "", actual = "", verdict = v, exitCode = 0, durationMs = 0)
    }
    EvalRun(
      runId = java.util.UUID.randomUUID().toString,
      datasetPath = "mem",
      agentName = "stub",
      startedAt = Instant.EPOCH,
      finishedAt = Instant.EPOCH,
      results = results,
      summary = EvalSummary.from(results),
    )

  def spec = suite("EvalCompare")(
    test("flags regression when a prompt goes Pass → Fail") {
      val baseline  = run(List("a" -> EvalVerdict.Pass, "b" -> EvalVerdict.Pass))
      val candidate = run(List("a" -> EvalVerdict.Pass, "b" -> EvalVerdict.Fail))
      val report    = EvalCompare.compare(baseline, candidate)
      assertTrue(report.hasRegressions) &&
      assertTrue(report.regressions.map(_.prompt) == List("b")) &&
      assertTrue(report.improvements.isEmpty)
    },
    test("flags improvement when a prompt goes Fail → Pass") {
      val baseline  = run(List("a" -> EvalVerdict.Fail))
      val candidate = run(List("a" -> EvalVerdict.Pass))
      val report    = EvalCompare.compare(baseline, candidate)
      assertTrue(!report.hasRegressions) &&
      assertTrue(report.improvements.map(_.prompt) == List("a"))
    },
    test("no-op comparison has zero regressions and zero improvements") {
      val r      = run(List("a" -> EvalVerdict.Pass))
      val report = EvalCompare.compare(r, r)
      assertTrue(!report.hasRegressions) &&
      assertTrue(report.improvements.isEmpty)
    },
    test("Added and Removed prompts are classified but not flagged as regressions") {
      val baseline  = run(List("a" -> EvalVerdict.Pass))
      val candidate = run(List("b" -> EvalVerdict.Pass))
      val report    = EvalCompare.compare(baseline, candidate)
      assertTrue(!report.hasRegressions) &&
      assertTrue(report.rows.map(_.status).toSet == Set(EvalCompare.Status.Removed, EvalCompare.Status.Added))
    },
  )
