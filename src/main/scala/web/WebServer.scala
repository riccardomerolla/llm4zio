package web

import zio.*
import zio.http.*

import web.controllers.*

trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    RunsController & AnalysisController & GraphController & DashboardController & SettingsController & AgentsController & ChatController,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      runs        <- ZIO.service[RunsController]
      analysis    <- ZIO.service[AnalysisController]
      graph       <- ZIO.service[GraphController]
      dashboard   <- ZIO.service[DashboardController]
      settings    <- ZIO.service[SettingsController]
      agents      <- ZIO.service[AgentsController]
      chat        <- ZIO.service[ChatController]
      staticRoutes = Routes.serveResources(Path.empty / "static")
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        dashboard.routes ++ runs.routes ++ analysis.routes ++ graph.routes ++ settings.routes ++ agents.routes ++ chat.routes ++ staticRoutes
    }
  }
  private val defaultShutdownTimeout = java.time.Duration.ofSeconds(3L)

  def start(port: Int): ZIO[WebServer, Throwable, Nothing] =
    start(host = "0.0.0.0", port = port)

  def start(host: String, port: Int): ZIO[WebServer, Throwable, Nothing] =
    val config =
      Server.Config.default
        .binding(host, port)
        .gracefulShutdownTimeout(defaultShutdownTimeout)

    ZIO.serviceWithZIO[WebServer](server => Server.serve(server.routes).provide(Server.defaultWith(_ => config)))
