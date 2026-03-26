package shared.web

import java.time.Instant

import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import sdlc.control.SdlcDashboardService

object SdlcDashboardViewSpec extends ZIOSpecDefault:

  private val snapshot = SdlcDashboardService.Snapshot(
    generatedAt = Instant.parse("2026-03-26T12:00:00Z"),
    thresholds = SdlcDashboardService.Thresholds(6, 2, 24, 12, 8, 4),
    lifecycle = List(
      SdlcDashboardService.LifecycleStage("idea", "Idea", 1, "/specifications", "Draft specs"),
      SdlcDashboardService.LifecycleStage("done", "Done", 3, "/issues/board?status=done", "Closed items"),
    ),
    churnAlerts = List(
      SdlcDashboardService.ChurnAlert(
        "issue-1",
        "Flaky work item",
        8,
        3,
        "Rework",
        Instant.parse("2026-03-26T10:00:00Z"),
      )
    ),
    stoppages = List(
      SdlcDashboardService.StoppageAlert("Blocked", "issue-2", "Blocked task", "Todo", 14, List("issue-1"))
    ),
    escalations = List(
      SdlcDashboardService.EscalationIndicator("Decision", "decision-1", "Review issue", "High", 9, "Pending approval")
    ),
    agentPerformance = List(
      SdlcDashboardService.AgentPerformance("agent-a", 2, 0.75, 3.5, 1, 0.0123)
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
        )
      },
      test("fragment renders lifecycle, anomaly, escalation, and agent sections") {
        val html = SdlcDashboardView.fragment(snapshot)
        assertTrue(
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
