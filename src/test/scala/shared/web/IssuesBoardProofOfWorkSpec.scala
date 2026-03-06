package shared.web

import java.time.Instant

import zio.test.*

import issues.entity.IssueWorkReport
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }
import orchestration.entity.DiffStats
import shared.ids.Ids.IssueId

/** Tests that the board card and detail view correctly embed the ProofOfWork panel. */
object IssuesBoardProofOfWorkSpec extends ZIOSpecDefault:

  private val issueId = IssueId("issue-pow-board-1")
  private val now     = Instant.parse("2026-03-05T10:00:00Z")

  private val baseIssue = AgentIssueView(
    id = Some(issueId.value),
    title = "Test Issue",
    description = "desc",
    issueType = "task",
    priority = IssuePriority.Medium,
    status = IssueStatus.InProgress,
    runId = None,
    tags = None,
    preferredAgent = None,
    assignedAgent = None,
    workspaceId = None,
    conversationId = None,
    requiredCapabilities = None,
    contextPath = None,
    sourceFolder = None,
    updatedAt = now,
    createdAt = now,
  )

  def spec: Spec[Any, Nothing] =
    suite("IssuesView board card with ProofOfWork")(
      test("board card without proof-of-work report renders no proof panel") {
        val html = IssuesView.boardCardFragment(baseIssue, Nil, workReport = None)
        assertTrue(!html.contains("data-proof-of-work"))
      },
      test("board card with proof-of-work walkthrough renders evidence bar (details element)") {
        val report = IssueWorkReport.empty(issueId, now).copy(walkthrough = Some("Auth refactored."))
        val html   = IssuesView.boardCardFragment(baseIssue, Nil, workReport = Some(report))
        assertTrue(
          html.contains("<details"),
          html.contains("Auth refactored."),
          !html.contains("data-pow-collapsed"),
        )
      },
      test("detail view with proof-of-work renders expanded panel") {
        val report = IssueWorkReport
          .empty(issueId, now)
          .copy(
            walkthrough = Some("Changed 3 files."),
            diffStats = Some(DiffStats(3, 20, 5)),
          )
        val html   = IssuesView.detailWithProofOfWork(baseIssue, Nil, Nil, Nil, workReport = Some(report))
        assertTrue(
          html.contains("data-proof-of-work"),
          !html.contains("data-pow-collapsed"),
          html.contains("Changed 3 files."),
          html.contains("3"),
        )
      },
      test("board card for InProgress issue has emerald left border class") {
        val issue = baseIssue.copy(status = IssueStatus.InProgress)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-emerald-400"))
      },
      test("board card for Open issue has indigo left border class") {
        val issue = baseIssue.copy(status = IssueStatus.Open)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-indigo-400"))
      },
      test("board card for Failed issue has rose left border class") {
        val issue = baseIssue.copy(status = IssueStatus.Failed)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-rose-500"))
      },
      test("board card for InProgress issue shows animate-pulse dot") {
        val issue = baseIssue.copy(status = IssueStatus.InProgress)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("animate-pulse"))
      },
      test("board card for Completed issue does not show animate-pulse dot") {
        val issue = baseIssue.copy(status = IssueStatus.Completed)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(!html.contains("animate-pulse"))
      },
      test("board card shows agent chip when assignedAgent is present") {
        val issue = baseIssue.copy(assignedAgent = Some("claude-3-5-sonnet"))
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("claude-3-5"))
      },
      test("board card uses evidenceBar (details element) instead of collapsed panel") {
        val report = IssueWorkReport.empty(issueId, now).copy(walkthrough = Some("Done."))
        val html   = IssuesView.boardCardFragment(baseIssue, Nil, workReport = Some(report))
        assertTrue(
          html.contains("<details"),
          !html.contains("data-pow-collapsed"),
        )
      },
      test("boardColumnsWithReports includes proof panels for issues that have reports") {
        val report  = IssueWorkReport.empty(issueId, now).copy(walkthrough = Some("Done."))
        val reports = Map(issueId -> report)
        val html    = IssuesView.boardColumnsFragment(
          issues = List(baseIssue),
          workspaces = Nil,
          workReports = reports,
        )
        assertTrue(html.contains("data-proof-of-work"))
      },
    )
