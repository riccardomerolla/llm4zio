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
      test("board card with proof-of-work walkthrough renders collapsed panel") {
        val report = IssueWorkReport.empty(issueId, now).copy(walkthrough = Some("Auth refactored."))
        val html   = IssuesView.boardCardFragment(baseIssue, Nil, workReport = Some(report))
        assertTrue(
          html.contains("data-proof-of-work"),
          html.contains("data-pow-collapsed"),
          html.contains("Auth refactored."),
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
