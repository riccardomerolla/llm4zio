package app.boundary

import zio.*
import zio.http.*

import _root_.config.boundary.AgentsController as ConfigAgentsController
import activity.boundary.ActivityController
import app.boundary.{ AgentMonitorController as AppAgentMonitorController, HealthController as AppHealthController }
import app.boundary.OnboardingController
import conversation.boundary.ChatController as ConversationChatController
import decision.boundary.DecisionsController
import issues.boundary.{
  IssueBulkController,
  IssueController as IssuesIssueController,
  IssueImportController,
  IssueTemplatesController,
}
import sdlc.boundary.SdlcDashboardController
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}

trait CoreRouteModule:
  def routes: Routes[Any, Response]

object CoreRouteModule:
  val live
    : ZLayer[
      TaskRunDashboardController &
        SdlcDashboardController &
        TaskRunTasksController &
        TaskRunReportsController &
        TaskRunGraphController &
        ConfigAgentsController &
        AppAgentMonitorController &
        ConversationChatController &
        IssuesIssueController &
        IssueTemplatesController &
        IssueBulkController &
        IssueImportController &
        ActivityController &
        DecisionsController &
        OnboardingController &
        AppHealthController &
        TaskRunLogsController,
      Nothing,
      CoreRouteModule,
    ] =
    ZLayer {
      for
        dashboard      <- ZIO.service[TaskRunDashboardController]
        sdlcDashboard  <- ZIO.service[SdlcDashboardController]
        tasks          <- ZIO.service[TaskRunTasksController]
        reports        <- ZIO.service[TaskRunReportsController]
        graph          <- ZIO.service[TaskRunGraphController]
        agents         <- ZIO.service[ConfigAgentsController]
        monitor        <- ZIO.service[AppAgentMonitorController]
        chat           <- ZIO.service[ConversationChatController]
        issues         <- ZIO.service[IssuesIssueController]
        issueTemplates <- ZIO.service[IssueTemplatesController]
        issueBulk      <- ZIO.service[IssueBulkController]
        issueImport    <- ZIO.service[IssueImportController]
        activity       <- ZIO.service[ActivityController]
        decisions      <- ZIO.service[DecisionsController]
        onboarding     <- ZIO.service[OnboardingController]
        health         <- ZIO.service[AppHealthController]
        logs           <- ZIO.service[TaskRunLogsController]
      yield new CoreRouteModule:
        override val routes: Routes[Any, Response] =
          dashboard.routes ++
            sdlcDashboard.routes ++
            tasks.routes ++
            reports.routes ++
            graph.routes ++
            agents.routes ++
            monitor.routes ++
            chat.routes ++
            issues.routes ++
            issueTemplates.routes ++
            issueBulk.routes ++
            issueImport.routes ++
            activity.routes ++
            decisions.routes ++
            onboarding.routes ++
            health.routes ++
            logs.routes
    }
