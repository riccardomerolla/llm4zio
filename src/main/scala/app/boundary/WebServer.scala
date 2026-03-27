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
import analysis.control.WorkspaceAnalysisScheduler
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
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import issues.boundary.IssueController as IssuesIssueController
import issues.entity.IssueRepository
import knowledge.boundary.KnowledgeController
import knowledge.control.KnowledgeGraphService
import knowledge.entity.DecisionLogRepository
import mcp.McpService
import memory.boundary.MemoryController as MemoryBoundaryController
import memory.entity.MemoryRepository
import orchestration.control.AgentRegistry
import plan.boundary.PlansController
import plan.entity.PlanRepository
import project.boundary.ProjectsController
import project.entity.ProjectRepository
import sdlc.boundary.SdlcDashboardController
import specification.boundary.SpecificationsController
import specification.entity.SpecificationRepository
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
import governance.boundary.GovernanceController
import governance.entity.GovernancePolicyRepository
import workspace.boundary.WorkspacesController
import workspace.control.{ GitService, WorkspaceRunService }
import workspace.entity.WorkspaceRepository
trait WebServer:
  def routes: Routes[Any, Response]

object WebServer:

  val live: ZLayer[
    TaskRunDashboardController & SdlcDashboardController & TaskRunTasksController & TaskRunReportsController & TaskRunGraphController & SettingsBoundaryController & ConfigBoundaryController & ConfigAgentsController & AppAgentMonitorController & ConversationChatController & IssuesIssueController & BoardBoundaryController & ConfigWorkflowsController & GatewayTelegramController & ActivityController & MemoryBoundaryController & GatewayChannelController & AppHealthController & TaskRunLogsController & ConversationWebSocketController & WorkspaceRepository & WorkspaceRunService & GitService & AgentRegistry & IssueRepository & ProjectRepository & SpecificationRepository & PlanRepository & DecisionInbox & McpService & WorkspaceAnalysisScheduler & DecisionLogRepository & KnowledgeGraphService & MemoryRepository & DaemonsController & DaemonAgentScheduler & CheckpointReviewService & GovernancePolicyRepository,
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
      wsRepo               <- ZIO.service[WorkspaceRepository]
      wsRunSvc             <- ZIO.service[WorkspaceRunService]
      gitService           <- ZIO.service[GitService]
      agentReg             <- ZIO.service[AgentRegistry]
      issueRepo            <- ZIO.service[IssueRepository]
      projectRepo          <- ZIO.service[ProjectRepository]
      specificationRepo    <- ZIO.service[SpecificationRepository]
      planRepo             <- ZIO.service[PlanRepository]
      decisionInbox        <- ZIO.service[DecisionInbox]
      mcpSvc               <- ZIO.service[McpService]
      analysisScheduler    <- ZIO.service[WorkspaceAnalysisScheduler]
      decisionLogs         <- ZIO.service[DecisionLogRepository]
      knowledgeGraph       <- ZIO.service[KnowledgeGraphService]
      memoryRepo           <- ZIO.service[MemoryRepository]
      daemonsController    <- ZIO.service[DaemonsController]
      checkpointReview     <- ZIO.service[CheckpointReviewService]
      governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
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
        dashboard.routes ++ sdlcDashboard.routes ++ tasks.routes ++ reports.routes ++ graph.routes ++ settings.routes ++ config.routes ++ agents.routes ++ monitor.routes ++ chat.routes ++ issues.routes ++ board.routes ++ workflows.routes ++ telegram.routes ++ activity.routes ++ memory.routes ++ channels.routes ++ health.routes ++ logs.routes ++ websocket.routes ++ mcpSvc.controller.routes ++ ProjectsController.routes(
          projectRepo,
          wsRepo,
          issueRepo,
          agentReg,
          analysisScheduler,
        ) ++ SpecificationsController.routes(
          specificationRepo,
          issueRepo,
        ) ++ PlansController.routes(
          planRepo,
          specificationRepo,
          issueRepo,
        ) ++ DecisionsController.routes(
          decisionInbox
        ) ++ CheckpointsController.routes(
          checkpointReview
        ) ++ KnowledgeController.routes(
          decisionLogs,
          knowledgeGraph,
          memoryRepo,
        ) ++ WorkspacesController.routes(
          wsRepo,
          wsRunSvc,
          agentReg,
          issueRepo,
          gitService,
          analysisScheduler,
        ) ++ daemonsController.routes ++ GovernanceController.routes(
          governancePolicyRepo
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
