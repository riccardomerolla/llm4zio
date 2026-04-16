package sdlc.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import sdlc.control.SdlcDashboardService
import sdlc.entity.*
import shared.errors.PersistenceError

object SdlcDashboardControllerSpec extends ZIOSpecDefault:

  private val flatTrend = TrendIndicator(
    direction = TrendDirection.Flat,
    currentPeriodCount = 0,
    previousPeriodCount = 0,
    periodLabel = "7d",
  )

  private val snapshotData = SdlcSnapshot(
    generatedAt = Instant.parse("2026-03-26T12:00:00Z"),
    thresholds = Thresholds(6, 2, 24, 12, 8, 4),
    lifecycle = List(LifecycleStage("idea", "Idea", 1, "/specifications", "Draft specs")),
    churnAlerts = Nil,
    stoppages = Nil,
    escalations = Nil,
    agentPerformance = Nil,
    governance = GovernanceOverview(0, 0, 0.0, 1),
    daemonHealth = DaemonHealthOverview(0, 0, 0),
    evolution = EvolutionOverview(0, Nil),
    recentActivity = List(
      ActivityEvent(
        id = shared.ids.Ids.EventId("evt-1"),
        eventType = ActivityEventType.DecisionCreated,
        source = "decision-inbox",
        summary = "Decision opened",
        payload = None,
        createdAt = Instant.parse("2026-03-26T11:59:00Z"),
      )
    ),
    specificationCount = 1,
    planCount = 1,
    issueCount = 0,
    pendingDecisionCount = 0,
    specificationTrend = flatTrend,
    planTrend = flatTrend,
    issueTrend = flatTrend,
    pendingDecisionTrend = flatTrend,
  )

  private val stubService: SdlcDashboardService = new SdlcDashboardService:
    override def snapshot: IO[PersistenceError, SdlcSnapshot] = ZIO.succeed(snapshotData)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SdlcDashboardControllerSpec")(
      test("GET /sdlc renders the page") {
        val controller = SdlcDashboardControllerLive(stubService)
        for
          response <- controller.routes.runZIO(Request.get(URL(Path.decode("/sdlc"))))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("SDLC Dashboard"),
          body.contains("/sdlc/fragment"),
        )
      },
      test("GET /sdlc/fragment renders the fragment") {
        val controller = SdlcDashboardControllerLive(stubService)
        for
          response <- controller.routes.runZIO(Request.get(URL(Path.decode("/sdlc/fragment"))))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Lifecycle"),
          body.contains("Idea"),
        )
      },
    )
