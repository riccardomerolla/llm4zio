package web

import zio.*
import zio.http.*

import web.controllers.*

trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    RunsController & AnalysisController & GraphController & DashboardController,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      runs        <- ZIO.service[RunsController]
      analysis    <- ZIO.service[AnalysisController]
      graph       <- ZIO.service[GraphController]
      dashboard   <- ZIO.service[DashboardController]
      staticRoutes = Routes.serveResources(Path.empty / "static")
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        dashboard.routes ++ runs.routes ++ analysis.routes ++ graph.routes ++ staticRoutes
    }
  }

  def start(port: Int): ZIO[WebServer, Throwable, Nothing] =
    ZIO.serviceWithZIO[WebServer](server => Server.serve(server.routes).provide(Server.defaultWithPort(port)))
