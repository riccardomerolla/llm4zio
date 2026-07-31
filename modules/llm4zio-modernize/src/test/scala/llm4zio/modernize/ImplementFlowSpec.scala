package llm4zio.modernize

import zio.Scope
import zio.test.*

import llm4zio.flow.*

/** `ImplementFlow`'s LLM-driven path (`specComplianceLoop`, `traceabilityPass`) needs a live `FlowContext`, a real git
  * repo, and a real judge — impractical to wire in a unit test. `judgeFeedback`, though, is pure text rendering over a
  * `ReviewResult` and is exercised directly here: no FlowContext, no LLM, no git.
  */
object ImplementFlowSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ImplementFlow")(
    test("judgeFeedback renders every issue as '- title: description', one per line, in order") {
      val findings      = ReviewResult(
        List(
          ReviewIssue(Severity.Critical, "judge[ACCTXFR]: spec-compliance scored 1", "missed the overdraft check"),
          ReviewIssue(Severity.Critical, "judge:traceability: scenario-coverage scored 0", "SCENARIO-7 orphaned"),
        ),
        "judge:merged",
      )
      val feedback      = ImplementFlow.judgeFeedback(findings)
      val expectedLines =
        "- judge[ACCTXFR]: spec-compliance scored 1: missed the overdraft check\n" +
          "- judge:traceability: scenario-coverage scored 0: SCENARIO-7 orphaned"
      assertTrue(feedback.endsWith(expectedLines))
    },
    test("judgeFeedback surfaces issues from BOTH a per-program judge and the traceability pass once merged") {
      // Mirrors specComplianceLoop's own composition: Reviewers.merge(List(perProgram, traceability)), then
      // judgeFeedback(merged) — the property this task actually wires up.
      val perProgram   = ReviewResult(
        List(ReviewIssue(Severity.Critical, "judge[ACCTXFR]: spec-compliance scored 1", "wrong reason code")),
        "judge:ACCTXFR",
      )
      val traceability = ReviewResult(
        List(ReviewIssue(Severity.Critical, "judge[traceability]: scenario-coverage scored 0", "rule moved, orphaned")),
        "judge:traceability",
      )
      val merged       = Reviewers.merge(List(perProgram, traceability))
      val feedback     = ImplementFlow.judgeFeedback(merged)
      assertTrue(
        feedback.contains("judge[ACCTXFR]: spec-compliance scored 1: wrong reason code"),
        feedback.contains("judge[traceability]: scenario-coverage scored 0: rule moved, orphaned"),
      )
    },
    test("judgeFeedback on a clean ReviewResult has no bullet lines") {
      val feedback = ImplementFlow.judgeFeedback(ReviewResult(Nil))
      assertTrue(!feedback.contains("- "))
    },
  )
