package app.boundary

import zio.*
import zio.http.*

import _root_.config.boundary.{
  AgentsController as ConfigAgentsController,
  ConfigController as ConfigBoundaryController,
  SettingsController as SettingsBoundaryController,
  WorkflowsController as ConfigWorkflowsController,
}
import activity.boundary.ActivityController
import app.boundary.{ AgentMonitorController as AppAgentMonitorController, HealthController as AppHealthController }
import conversation.boundary.{
  ChatController as ConversationChatController,
  WebSocketController as ConversationWebSocketController,
}
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import issues.boundary.IssueController as IssuesIssueController
import memory.boundary.MemoryController as MemoryBoundaryController
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    TaskRunDashboardController & TaskRunTasksController & TaskRunReportsController & TaskRunGraphController & SettingsBoundaryController & ConfigBoundaryController & ConfigAgentsController & AppAgentMonitorController & ConversationChatController & IssuesIssueController & ConfigWorkflowsController & GatewayTelegramController & ActivityController & MemoryBoundaryController & GatewayChannelController & AppHealthController & TaskRunLogsController & ConversationWebSocketController,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      dashboard   <- ZIO.service[TaskRunDashboardController]
      tasks       <- ZIO.service[TaskRunTasksController]
      reports     <- ZIO.service[TaskRunReportsController]
      graph       <- ZIO.service[TaskRunGraphController]
      settings    <- ZIO.service[SettingsBoundaryController]
      config      <- ZIO.service[ConfigBoundaryController]
      agents      <- ZIO.service[ConfigAgentsController]
      monitor     <- ZIO.service[AppAgentMonitorController]
      chat        <- ZIO.service[ConversationChatController]
      issues      <- ZIO.service[IssuesIssueController]
      workflows   <- ZIO.service[ConfigWorkflowsController]
      telegram    <- ZIO.service[GatewayTelegramController]
      activity    <- ZIO.service[ActivityController]
      memory      <- ZIO.service[MemoryBoundaryController]
      channels    <- ZIO.service[GatewayChannelController]
      health      <- ZIO.service[AppHealthController]
      logs        <- ZIO.service[TaskRunLogsController]
      websocket   <- ZIO.service[ConversationWebSocketController]
      staticRoutes = Routes.serveResources(Path.empty / "static")
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        dashboard.routes ++ tasks.routes ++ reports.routes ++ graph.routes ++ settings.routes ++ config.routes ++ agents.routes ++ monitor.routes ++ chat.routes ++ issues.routes ++ workflows.routes ++ telegram.routes ++ activity.routes ++ memory.routes ++ channels.routes ++ health.routes ++ logs.routes ++ websocket.routes ++ staticRoutes
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
