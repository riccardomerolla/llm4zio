package shared.web

import java.time.Instant

import zio.test.*

import issues.entity.IssueWorkReport
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }
import orchestration.entity.DiffStats
import shared.ids.Ids.IssueId
import taskrun.entity.TokenUsage

object BoardStatsSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-05T10:00:00Z")

  private def mkIssue(id: String, status: IssueStatus): AgentIssueView =
    AgentIssueView(
      id = Some(id),
      title = s"Issue $id",
      description = "",
      issueType = "task",
      priority = IssuePriority.Medium,
      status = status,
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

  private def mkReport(issueId: IssueId, tokens: Long): IssueWorkReport =
    IssueWorkReport.empty(issueId, now).copy(tokenUsage = Some(TokenUsage(tokens, tokens / 2, tokens + tokens / 2)))

  def spec: Spec[Any, Nothing] =
    suite("BoardStats")(
      test("computeStats returns zeros for empty inputs") {
        val stats = BoardStats.compute(Nil, Map.empty)
        assertTrue(
          stats.running == 0,
          stats.completed == 0,
          stats.tokensTotal == 0L,
        )
      },
      test("computeStats counts running issues") {
        val issues = List(
          mkIssue("i1", IssueStatus.InProgress),
          mkIssue("i2", IssueStatus.InProgress),
          mkIssue("i3", IssueStatus.Open),
        )
        val stats  = BoardStats.compute(issues, Map.empty)
        assertTrue(stats.running == 2)
      },
      test("computeStats counts completed issues") {
        val issues = List(
          mkIssue("i1", IssueStatus.Completed),
          mkIssue("i2", IssueStatus.Completed),
          mkIssue("i3", IssueStatus.InProgress),
        )
        val stats  = BoardStats.compute(issues, Map.empty)
        assertTrue(stats.completed == 2)
      },
      test("computeStats sums token usage from work reports") {
        val id1     = IssueId("i1")
        val id2     = IssueId("i2")
        val issues  = List(mkIssue("i1", IssueStatus.Completed), mkIssue("i2", IssueStatus.Completed))
        val reports = Map(id1 -> mkReport(id1, 1000L), id2 -> mkReport(id2, 500L))
        val stats   = BoardStats.compute(issues, reports)
        assertTrue(stats.tokensTotal == 2250L) // 1500 + 750
      },
      test("hasProofFilter keeps only issues with a proof-of-work entry") {
        val id1     = IssueId("i1")
        val issues  = List(mkIssue("i1", IssueStatus.Completed), mkIssue("i2", IssueStatus.Open))
        val report  = IssueWorkReport.empty(id1, now).copy(walkthrough = Some("Done."))
        val reports = Map(id1 -> report)
        val result  = BoardStats.hasProofFilter(issues, reports)
        assertTrue(result.map(_.id) == List(Some("i1")))
      },
      test("hasProofFilter returns empty list when no reports have proof signals") {
        val issues = List(mkIssue("i1", IssueStatus.Open), mkIssue("i2", IssueStatus.Open))
        assertTrue(BoardStats.hasProofFilter(issues, Map.empty).isEmpty)
      },
      test("boardStatsBar renders running and completed counts") {
        val stats = BoardStats.Stats(running = 3, completed = 7, tokensTotal = 12000L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(
          html.contains("3"),
          html.contains("7"),
          html.contains("12"),
        )
      },
    )
