package web.controllers

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

import app.boundary.HealthControllerLive
import app.control.*

object HealthControllerSpec extends ZIOSpecDefault:

  private val sample = HealthSnapshot(
    ts = 1L,
    gateway = GatewayHealth(10L, "1.0.0", 2, 10, 9, 1),
    agents = AgentHealthSummary(active = 2, idle = 1, failed = 1, total = 4),
    channels = List(ChannelHealth("websocket", ChannelStatus.Connected, 2)),
    resources = ResourceUsage(cpuPercent = 10.5, usedMemoryMb = 256, maxMemoryMb = 1024, tokenUsageEstimate = 120),
    errors = ErrorStats(errorRate = 0.1, recentFailures = List("Run #9 failed")),
  )

  private val monitor: HealthMonitor = new HealthMonitor:
    override def snapshot: UIO[HealthSnapshot]                                     = ZIO.succeed(sample)
    override def history(limit: Int): UIO[List[HealthSnapshot]]                    = ZIO.succeed(List(sample).take(limit.max(1)))
    override def stream(interval: Duration): ZStream[Any, Nothing, HealthSnapshot] = zio.stream.ZStream.empty

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HealthControllerSpec")(
    test("GET /health renders dashboard") {
      val controller = HealthControllerLive(monitor)
      for
        response <- controller.routes.runZIO(Request.get("/health"))
        body     <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.contains("System Health Dashboard"))
    },
    test("GET /api/health returns JSON snapshot") {
      val controller = HealthControllerLive(monitor)
      for
        response <- controller.routes.runZIO(Request.get("/api/health"))
        body     <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.contains("\"gateway\""), body.contains("\"resources\""))
    },
    test("GET /api/health/history returns JSON list") {
      val controller = HealthControllerLive(monitor)
      for
        response <- controller.routes.runZIO(Request.get(URL.decode("/api/health/history?limit=5").toOption.get))
        body     <- response.body.asString
      yield assertTrue(response.status == Status.Ok, body.startsWith("["))
    },
  )
