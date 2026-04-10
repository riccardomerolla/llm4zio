package shared.web

import java.time.Instant

import zio.test.*

import issues.boundary.BoardStats
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }
import issues.entity.{ IssueWorkReport, TokenUsage }
import shared.ids.Ids.IssueId

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
          mkIssue("i3", IssueStatus.Backlog),
        )
        val stats  = BoardStats.compute(issues, Map.empty)
        assertTrue(stats.running == 2)
      },
      test("computeStats counts completed issues") {
        val issues = List(
          mkIssue("i1", IssueStatus.Done),
          mkIssue("i2", IssueStatus.Done),
          mkIssue("i3", IssueStatus.InProgress),
        )
        val stats  = BoardStats.compute(issues, Map.empty)
        assertTrue(stats.completed == 2)
      },
      test("computeStats sums token usage from work reports") {
        val id1     = IssueId("i1")
        val id2     = IssueId("i2")
        val issues  = List(mkIssue("i1", IssueStatus.Done), mkIssue("i2", IssueStatus.Done))
        val reports = Map(id1 -> mkReport(id1, 1000L), id2 -> mkReport(id2, 500L))
        val stats   = BoardStats.compute(issues, reports)
        assertTrue(stats.tokensTotal == 2250L) // 1500 + 750
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
      test("statsBar contains amber class for running tile") {
        val stats = BoardStats.Stats(running = 2, completed = 5, tokensTotal = 3000L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(html.contains("amber"))
      },
      test("statsBar contains emerald class for completed tile") {
        val stats = BoardStats.Stats(running = 0, completed = 4, tokensTotal = 0L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(html.contains("emerald"))
      },
      test("statsBar contains purple class for tokens tile") {
        val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 5000L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(html.contains("purple"))
      },
      test("statsBar formats tokens >= 1000 as Nk") {
        val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 12500L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(html.contains("12k"))
      },
      test("statsBar shows raw count when tokens < 1000") {
        val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 800L)
        val html  = BoardStats.statsBar(stats)
        assertTrue(html.contains("800"))
      },
    )
