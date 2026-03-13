package shared.web

import java.time.Instant

import zio.test.*

import issues.entity.api.{ AgentIssueView, DispatchStatusResponse, IssuePriority, IssueStatus }
import issues.entity.{ IssueDiffStats, IssueWorkReport }
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
            diffStats = Some(IssueDiffStats(3, 20, 5)),
          )
        val html   = IssuesView.detailWithProofOfWork(baseIssue, Nil, Nil, Nil, workReport = Some(report))
        assertTrue(
          html.contains("data-proof-of-work"),
          !html.contains("data-pow-collapsed"),
          html.contains("Changed 3 files."),
          html.contains("3"),
        )
      },
      test("board card for InProgress issue has amber left border class") {
        val issue = baseIssue.copy(status = IssueStatus.InProgress)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-amber-400"))
      },
      test("board card for Open issue has slate left border class") {
        val issue = baseIssue.copy(status = IssueStatus.Open)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-slate-400"))
      },
      test("board card for Failed issue has orange left border class") {
        val issue = baseIssue.copy(status = IssueStatus.Failed)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-l-orange-400"))
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
      test("board card renders short issue ID chip top-left") {
        val html = IssuesView.boardCardFragment(baseIssue, Nil, workReport = None)
        assertTrue(html.contains("#issue-po"))
      },
      test("todo board card renders ready-for-dispatch indicator") {
        val issue = baseIssue.copy(status = IssueStatus.Todo)
        val html  =
          IssuesView.boardCardFragment(
            issue,
            Nil,
            workReport = None,
            dispatchStatus = Some(DispatchStatusResponse(readyForDispatch = true)),
          )
        assertTrue(
          html.contains("✅"),
          html.contains("Ready for dispatch"),
        )
      },
      test("todo board card renders dependency-blocked indicator with blocked issue ids") {
        val issue = baseIssue.copy(status = IssueStatus.Todo)
        val html  = IssuesView.boardCardFragment(
          issue,
          Nil,
          workReport = None,
          dispatchStatus = Some(DispatchStatusResponse(dependencyBlocked = true, blockedByIds = List("12", "13"))),
        )
        assertTrue(
          html.contains("🟡"),
          html.contains("#12"),
          html.contains("#13"),
        )
      },
      test("board card renders status dot for Open issue (ring style)") {
        val issue = baseIssue.copy(status = IssueStatus.Open)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("border-slate-400"))
      },
      test("board card renders status dot for InProgress issue (filled pulse)") {
        val issue = baseIssue.copy(status = IssueStatus.InProgress)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(
          html.contains("bg-amber-400"),
          html.contains("animate-pulse"),
        )
      },
      test("board card renders status dot for Failed issue") {
        val issue = baseIssue.copy(status = IssueStatus.Failed)
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(html.contains("bg-orange-400"))
      },
      test("board card does not render description excerpt") {
        val issue = baseIssue.copy(description = "This should not appear on card")
        val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        assertTrue(!html.contains("This should not appear on card"))
      },
      test("board card always shows updated date") {
        val html = IssuesView.boardCardFragment(baseIssue, Nil, workReport = None)
        assertTrue(html.contains("Updated "))
      },
      test("board column header renders colored status dot") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        // InProgress column should have amber dot, Backlog should have slate dot
        assertTrue(
          html.contains("bg-amber-400"),
          html.contains("bg-slate-400"),
        )
      },
      test("board column header renders quick-add toggle button for each status") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        assertTrue(
          html.contains("data-quick-add-toggle=\"backlog\""),
          html.contains("data-quick-add-toggle=\"in_progress\""),
        )
      },
      test("board column has hidden quick-add form with title input and priority select") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        assertTrue(
          html.contains("data-quick-add-form"),
          html.contains("data-quick-add-title"),
          html.contains("data-quick-add-priority"),
          html.contains("data-quick-add-submit"),
          html.contains("data-quick-add-cancel"),
        )
      },
      test("quick-add form priority select has Critical/High/Medium/Low options") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        assertTrue(
          html.contains("Critical"),
          html.contains("High"),
          html.contains("Medium"),
          html.contains("Low"),
        )
      },
      test("board column header renders collapse toggle button") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        assertTrue(html.contains("data-collapse-toggle"))
      },
      test("board column cards area has data-column-cards attribute") {
        val html = IssuesView.boardColumnsFragment(List(baseIssue), Nil, Map.empty)
        assertTrue(html.contains("data-column-cards"))
      },
      test("board card agent chip appears after updated label in bottom row") {
        val issue      = baseIssue.copy(assignedAgent = Some("claude-3-5-sonnet"))
        val html       = IssuesView.boardCardFragment(issue, Nil, workReport = None)
        // Updated label and agent chip are both in the bottom flex row;
        // use lastIndexOf for agent chip text to skip the data-attr occurrence
        val updatedIdx = html.indexOf("Updated ")
        val agentIdx   = html.lastIndexOf("claude-3-5")
        assertTrue(
          updatedIdx >= 0,
          agentIdx >= 0,
          updatedIdx < agentIdx,
        )
      },
    )
