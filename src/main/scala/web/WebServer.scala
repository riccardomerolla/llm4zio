package web

import zio.*
import zio.http.*

import web.controllers.*

trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    DashboardController & TasksController & ReportsController & GraphController & SettingsController & ConfigController & AgentsController & AgentMonitorController & ChatController & WorkflowsController & TelegramController & ActivityController & HealthController & LogsController,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      dashboard   <- ZIO.service[DashboardController]
      tasks       <- ZIO.service[TasksController]
      reports     <- ZIO.service[ReportsController]
      graph       <- ZIO.service[GraphController]
      settings    <- ZIO.service[SettingsController]
      config      <- ZIO.service[ConfigController]
      agents      <- ZIO.service[AgentsController]
      monitor     <- ZIO.service[AgentMonitorController]
      chat        <- ZIO.service[ChatController]
      workflows   <- ZIO.service[WorkflowsController]
      telegram    <- ZIO.service[TelegramController]
      activity    <- ZIO.service[ActivityController]
      health      <- ZIO.service[HealthController]
      logs        <- ZIO.service[LogsController]
      staticRoutes = Routes.serveResources(Path.empty / "static")
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        dashboard.routes ++ tasks.routes ++ reports.routes ++ graph.routes ++ settings.routes ++ config.routes ++ agents.routes ++ monitor.routes ++ chat.routes ++ workflows.routes ++ telegram.routes ++ activity.routes ++ health.routes ++ logs.routes ++ staticRoutes
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
