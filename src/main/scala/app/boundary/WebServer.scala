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
import board.boundary.BoardController as BoardBoundaryController
import checkpoint.boundary.CheckpointsController
import checkpoint.control.CheckpointReviewService
import conversation.boundary.{
  ChatController as ConversationChatController,
  WebSocketController as ConversationWebSocketController,
}
import daemon.boundary.DaemonsController
import daemon.control.DaemonAgentScheduler
import decision.boundary.DecisionsController
import decision.control.DecisionInbox
import evolution.boundary.EvolutionController
import evolution.entity.EvolutionProposalRepository
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import governance.boundary.GovernanceController
import governance.entity.GovernancePolicyRepository
import issues.boundary.IssueController as IssuesIssueController
import issues.entity.IssueRepository
import knowledge.boundary.KnowledgeController
import mcp.McpService
import memory.boundary.MemoryController as MemoryBoundaryController
import plan.boundary.PlansController
import project.boundary.ProjectsController
import sdlc.boundary.SdlcDashboardController
import specification.boundary.SpecificationsController
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
import workspace.boundary.WorkspacesController
trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    TaskRunDashboardController & SdlcDashboardController & TaskRunTasksController & TaskRunReportsController & TaskRunGraphController & SettingsBoundaryController & ConfigBoundaryController & ConfigAgentsController & AppAgentMonitorController & ConversationChatController & IssuesIssueController & BoardBoundaryController & ConfigWorkflowsController & GatewayTelegramController & ActivityController & MemoryBoundaryController & GatewayChannelController & AppHealthController & TaskRunLogsController & ConversationWebSocketController & ProjectsController & SpecificationsController & PlansController & DecisionsController & CheckpointsController & KnowledgeController & WorkspacesController & IssueRepository & DecisionInbox & McpService & DaemonsController & DaemonAgentScheduler & CheckpointReviewService & GovernancePolicyRepository & EvolutionProposalRepository,
    Nothing,
    WebServer,
  ] = ZLayer {
    for
      dashboard            <- ZIO.service[TaskRunDashboardController]
      sdlcDashboard        <- ZIO.service[SdlcDashboardController]
      tasks                <- ZIO.service[TaskRunTasksController]
      reports              <- ZIO.service[TaskRunReportsController]
      graph                <- ZIO.service[TaskRunGraphController]
      settings             <- ZIO.service[SettingsBoundaryController]
      config               <- ZIO.service[ConfigBoundaryController]
      agents               <- ZIO.service[ConfigAgentsController]
      monitor              <- ZIO.service[AppAgentMonitorController]
      chat                 <- ZIO.service[ConversationChatController]
      issues               <- ZIO.service[IssuesIssueController]
      board                <- ZIO.service[BoardBoundaryController]
      workflows            <- ZIO.service[ConfigWorkflowsController]
      telegram             <- ZIO.service[GatewayTelegramController]
      activity             <- ZIO.service[ActivityController]
      memory               <- ZIO.service[MemoryBoundaryController]
      channels             <- ZIO.service[GatewayChannelController]
      health               <- ZIO.service[AppHealthController]
      logs                 <- ZIO.service[TaskRunLogsController]
      websocket            <- ZIO.service[ConversationWebSocketController]
      projects             <- ZIO.service[ProjectsController]
      specifications       <- ZIO.service[SpecificationsController]
      plans                <- ZIO.service[PlansController]
      decisions            <- ZIO.service[DecisionsController]
      checkpoints          <- ZIO.service[CheckpointsController]
      knowledge            <- ZIO.service[KnowledgeController]
      workspaces           <- ZIO.service[WorkspacesController]
      issueRepo            <- ZIO.service[IssueRepository]
      decisionInbox        <- ZIO.service[DecisionInbox]
      mcpSvc               <- ZIO.service[McpService]
      daemonsController    <- ZIO.service[DaemonsController]
      checkpointReview     <- ZIO.service[CheckpointReviewService]
      governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
      evolutionRepo        <- ZIO.service[EvolutionProposalRepository]
      staticRoutes          = Routes.serveResources(Path.empty / "static")
      devCatalogRoutes      = Routes(
                                Method.GET / "components" -> handler {
                                  Response
                                    .text(shared.web.ComponentsCatalogView.page())
                                    .contentType(MediaType.text.html)
                                }
                              )
    yield new WebServer {
      override val routes: Routes[Any, Response] =
        dashboard.routes ++ sdlcDashboard.routes ++ tasks.routes ++ reports.routes ++ graph.routes ++ settings.routes ++ config.routes ++ agents.routes ++ monitor.routes ++ chat.routes ++ issues.routes ++ board.routes ++ workflows.routes ++ telegram.routes ++ activity.routes ++ memory.routes ++ channels.routes ++ health.routes ++ logs.routes ++ websocket.routes ++ mcpSvc.controller.routes ++ projects.routes ++ specifications.routes ++ plans.routes ++ decisions.routes ++ checkpoints.routes ++ knowledge.routes ++ workspaces.routes ++ daemonsController.routes ++ GovernanceController.routes(
          governancePolicyRepo
        ) ++ EvolutionController.routes(
          evolutionRepo
        ) ++ SidebarStatusController.routes(
          decisionInbox,
          checkpointReview,
          issueRepo,
        ) ++ devCatalogRoutes ++ staticRoutes
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
