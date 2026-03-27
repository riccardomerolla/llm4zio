package sdlc.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import sdlc.control.SdlcDashboardService
import shared.errors.PersistenceError

object SdlcDashboardControllerSpec extends ZIOSpecDefault:

  private val snapshotData = SdlcDashboardService.Snapshot(
    generatedAt = Instant.parse("2026-03-26T12:00:00Z"),
    thresholds = SdlcDashboardService.Thresholds(6, 2, 24, 12, 8, 4),
    lifecycle = List(SdlcDashboardService.LifecycleStage("idea", "Idea", 1, "/specifications", "Draft specs")),
    churnAlerts = Nil,
    stoppages = Nil,
    escalations = Nil,
    agentPerformance = Nil,
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
  )

  private val stubService: SdlcDashboardService = new SdlcDashboardService:
    override def snapshot: IO[PersistenceError, SdlcDashboardService.Snapshot] = ZIO.succeed(snapshotData)

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
