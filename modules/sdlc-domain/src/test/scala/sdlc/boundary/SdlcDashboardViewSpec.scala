package sdlc.boundary

import java.time.Instant

import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import sdlc.entity.*

object SdlcDashboardViewSpec extends ZIOSpecDefault:

  private val flatTrend = TrendIndicator(
    direction = TrendDirection.Flat,
    currentPeriodCount = 1,
    previousPeriodCount = 1,
    periodLabel = "7d",
  )

  private val snapshot = SdlcSnapshot(
    generatedAt = Instant.parse("2026-03-26T12:00:00Z"),
    thresholds = Thresholds(6, 2, 24, 12, 8, 4),
    lifecycle = List(
      LifecycleStage("idea", "Idea", 1, "/specifications", "Draft specs"),
      LifecycleStage("done", "Done", 3, "/issues/board?status=done", "Closed items"),
    ),
    churnAlerts = List(
      ChurnAlert(
        "issue-1",
        "Flaky work item",
        8,
        3,
        "Rework",
        Instant.parse("2026-03-26T10:00:00Z"),
      )
    ),
    stoppages = List(
      StoppageAlert("Blocked", "issue-2", "Blocked task", "Todo", 14, List("issue-1"))
    ),
    escalations = List(
      EscalationIndicator("Decision", "decision-1", "Review issue", "High", 9, "Pending approval")
    ),
    agentPerformance = List(
      AgentPerformance("agent-a", 2, 0.75, 3.5, 1, 0.0123)
    ),
    governance = GovernanceOverview(3, 1, 0.75, 2),
    daemonHealth = DaemonHealthOverview(4, 1, 1),
    evolution = EvolutionOverview(
      pendingProposalCount = 2,
      recentlyApplied = List(
        RecentEvolution(
          proposalId = "proposal-1",
          title = "Roll out daemon policy",
          status = "Applied",
          appliedAt = Instant.parse("2026-03-26T11:00:00Z"),
        )
      ),
    ),
    recentActivity = List(
      ActivityEvent(
        id = shared.ids.Ids.EventId("evt-1"),
        eventType = ActivityEventType.DecisionCreated,
        source = "decision-inbox",
        runId = None,
        conversationId = None,
        agentName = Some("agent-a"),
        summary = "Decision opened",
        payload = None,
        createdAt = Instant.parse("2026-03-26T11:59:00Z"),
      )
    ),
    specificationCount = 2,
    planCount = 1,
    issueCount = 5,
    pendingDecisionCount = 1,
    specificationTrend = flatTrend,
    planTrend = flatTrend.copy(direction = TrendDirection.Up, currentPeriodCount = 2),
    issueTrend = flatTrend.copy(direction = TrendDirection.Down, currentPeriodCount = 0),
    pendingDecisionTrend = flatTrend,
  )

  def spec: Spec[Any, Nothing] =
    suite("SdlcDashboardViewSpec")(
      test("page renders the SDLC dashboard shell and htmx refresh") {
        val html = SdlcDashboardView.page(snapshot)
        assertTrue(
          html.contains("SDLC Dashboard"),
          html.contains("/sdlc/fragment"),
          html.contains("load, every 10s"),
          html.contains("Pending Decisions"),
          html.contains("↑ up"),
          html.contains("↓ down"),
        )
      },
      test("fragment renders lifecycle, ADE panels, anomaly, escalation, and agent sections") {
        val html = SdlcDashboardView.fragment(snapshot)
        assertTrue(
          html.contains("Governance"),
          html.contains("Pass Rate"),
          html.contains("Daemon Health"),
          html.contains("Errored"),
          html.contains("Evolution"),
          html.contains("Pending Proposals"),
          html.contains("Roll out daemon policy"),
          html.contains("Lifecycle"),
          html.contains("Churn Detection"),
          html.contains("Stoppages"),
          html.contains("Escalations"),
          html.contains("Agent Performance"),
          html.contains("Flaky work item"),
          html.contains("Blocked task"),
          html.contains("Review issue"),
          html.contains("agent-a"),
        )
      },
    )
