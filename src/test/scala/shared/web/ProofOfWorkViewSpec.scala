package shared.web

import java.time.Instant

import zio.test.*

import issues.entity.IssueWorkReport
import orchestration.entity.DiffStats
import shared.ids.Ids.{ IssueId, ReportId }
import taskrun.entity.{ CiStatus, PrStatus, TaskReport, TokenUsage }

object ProofOfWorkViewSpec extends ZIOSpecDefault:

  private val issueId = IssueId("issue-view-1")
  private val now     = Instant.parse("2026-03-05T10:00:00Z")

  private val emptyReport = IssueWorkReport.empty(issueId, now)

  def spec: Spec[Any, Nothing] =
    suite("ProofOfWorkView")(
      test("renders nothing for an empty report") {
        val html = ProofOfWorkView.panel(emptyReport, collapsed = true)
        assertTrue(!html.contains("data-proof-of-work"))
      },
      test("renders walkthrough text when present") {
        val report = emptyReport.copy(walkthrough = Some("Agent refactored auth module."))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(
          html.contains("Agent refactored auth module."),
          html.contains("data-proof-of-work"),
        )
      },
      test("renders PR link as clickable anchor with status badge") {
        val report = emptyReport.copy(
          prLink = Some("https://github.com/org/repo/pull/42"),
          prStatus = Some(PrStatus.Open),
        )
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(
          html.contains("https://github.com/org/repo/pull/42"),
          html.contains("Open"),
        )
      },
      test("renders CI status badge") {
        val report = emptyReport.copy(ciStatus = Some(CiStatus.Passed))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(html.contains("Passed"))
      },
      test("renders token usage") {
        val report = emptyReport.copy(tokenUsage = Some(TokenUsage(1000L, 500L, 1500L)), runtimeSeconds = Some(30L))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(
          html.contains("1500"),
          html.contains("30"),
        )
      },
      test("renders diff stats") {
        val report = emptyReport.copy(diffStats = Some(DiffStats(3, 45, 12)))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(
          html.contains("3"),
          html.contains("45"),
          html.contains("12"),
        )
      },
      test("hides signals absent from report — no PR text when prLink is None") {
        val report = emptyReport.copy(walkthrough = Some("Summary only."))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(!html.contains("github.com"))
      },
      test("renders collapsed panel with toggle attribute when collapsed=true") {
        val report = emptyReport.copy(walkthrough = Some("Summary."))
        val html   = ProofOfWorkView.panel(report, collapsed = true)
        assertTrue(html.contains("data-pow-collapsed"))
      },
      test("renders reports list when reports are present") {
        val report = emptyReport.copy(reports = List(TaskReport(ReportId("r1"), "analysis", "summary", "ok", now)))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(html.contains("analysis"))
      },
      test("renders agent summary when present") {
        val report = emptyReport.copy(agentSummary = Some("Agent completed 5 steps."))
        val html   = ProofOfWorkView.panel(report, collapsed = false)
        assertTrue(html.contains("Agent completed 5 steps."))
      },
    )
