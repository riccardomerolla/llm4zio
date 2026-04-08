package shared.web

import java.time.Instant

import activity.entity.ActivityEvent
import config.entity.{ AgentInfo, ModelRegistryResponse, ProviderProbeStatus, WorkflowDefinition }
import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta }
import decision.entity.Decision
import gateway.entity.ChatSession
import issues.entity.IssueWorkReport
import issues.entity.api.{
  AgentIssueView,
  AnalysisContextDocView,
  DispatchStatusResponse,
  IssueTemplate,
  MergeHistoryEntryView,
}
import sdlc.entity.SdlcSnapshot
import shared.ids.Ids.IssueId
import taskrun.entity.{ TaskReportRow, TaskRunRow }
import workspace.entity.WorkspaceRun

object HtmlViews:

  def dashboard(summary: CommandCenterView.PipelineSummary, recentEvents: List[ActivityEvent]): String =
    CommandCenterView.page(summary, recentEvents)

  def sdlcDashboard(snapshot: SdlcSnapshot): String =
    SdlcDashboardView.page(snapshot)

  def sdlcDashboardFragment(snapshot: SdlcSnapshot): String =
    SdlcDashboardView.fragment(snapshot)

  def recentRunsFragment(runs: List[TaskRunRow]): String =
    CommandCenterView.recentRunsFragment(runs)

  def reportsList(taskId: Long, reports: List[TaskReportRow]): String =
    ReportsView.reportsList(taskId, reports)

  def reportsHome: String =
    ReportsView.reportsHome

  def reportDetail(report: TaskReportRow): String =
    ReportsView.reportDetail(report)

  def graphPage(taskId: Long, graphReports: List[TaskReportRow]): String =
    GraphView.page(taskId, graphReports)

  def graphHome: String =
    GraphView.home

  def settingsPage(settings: Map[String, String], flash: Option[String] = None): String =
    SettingsView.page(settings, flash)

  def settingsAiTab(
    settings: Map[String, String],
    registry: ModelRegistryResponse,
    statuses: List[ProviderProbeStatus],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    SettingsView.aiTab(settings, registry, statuses, flash, errors)

  def settingsChannelsTab(
    cards: List[ChannelCardData],
    nowMs: Long,
    flash: Option[String] = None,
  ): String =
    SettingsView.channelsTab(cards, nowMs, flash)

  def settingsGatewayTab(
    settings: Map[String, String],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    SettingsView.gatewayTab(settings, flash, errors)

  def settingsSystemTab: String = SettingsView.systemTab

  def settingsDemoTab(
    settings: Map[String, String],
    flash: Option[String] = None,
  ): String =
    DemoView.demoTab(settings, flash)

  def settingsAdvancedTab: String = SettingsView.advancedTab

  def settingsIssueTemplatesTab(
    templates: List[IssueTemplate],
    flash: Option[String] = None,
  ): String =
    SettingsView.issueTemplatesTab(templates, flash)

  def workflowsList(
    workflows: List[WorkflowDefinition],
    availableAgents: List[AgentInfo],
    flash: Option[String] = None,
  ): String =
    WorkflowsView.list(workflows, availableAgents, flash)

  def workflowForm(
    title: String,
    action: String,
    workflow: WorkflowDefinition,
    availableAgents: List[AgentInfo],
    flash: Option[String] = None,
  ): String =
    WorkflowsView.form(title, action, workflow, availableAgents, flash)

  def workflowDetail(workflow: WorkflowDefinition): String =
    WorkflowsView.detail(workflow)

  def agentsPage(cards: List[AgentsView.AgentCard], flash: Option[String] = None): String =
    AgentsView.list(cards, flash)

  def newAgentPage(
    values: Map[String, String] = Map.empty,
    flash: Option[String] = None,
  ): String =
    AgentsView.newAgentForm(values, flash)

  def editCustomAgentPage(
    name: String,
    values: Map[String, String],
    flash: Option[String] = None,
  ): String =
    AgentsView.editCustomAgentForm(name, values, flash)

  def agentRegistryFormPage(
    title: String,
    action: String,
    values: Map[String, String],
  ): String =
    AgentsView.registryForm(title, action, values)

  def agentDetailPage(
    registryAgent: agent.entity.Agent,
    metrics: agent.entity.api.AgentMetricsSummary,
    runs: List[agent.entity.api.AgentRunHistoryItem],
    activeRuns: List[agent.entity.api.AgentActiveRun],
    history: List[agent.entity.api.AgentMetricsHistoryPoint],
    flash: Option[String] = None,
  ): String =
    AgentsView.detail(registryAgent, metrics, runs, activeRuns, history, flash)

  def agentConfigPage(
    agent: AgentInfo,
    overrideSettings: Map[String, String],
    globalSettings: Map[String, String],
    flash: Option[String] = None,
  ): String =
    AgentsView.agentConfigPage(agent, overrideSettings, globalSettings, flash)

  def chatDashboard(
    conversations: List[ChatConversation],
    sessionMetaByConversation: Map[String, ConversationSessionMeta] = Map.empty,
    sessions: List[ChatSession] = Nil,
    workspaceFolders: List[ChatView.ChatWorkspaceFolder] = Nil,
    renderedAt: Instant = Instant.EPOCH,
  ): String =
    ChatView.dashboard(conversations, sessionMetaByConversation, sessions, workspaceFolders, renderedAt)

  def chatNew(
    workspaceFolders: List[ChatView.ChatWorkspaceFolder] = Nil,
    workspaces: List[(String, String)] = Nil,
    renderedAt: Instant = Instant.EPOCH,
  ): String =
    ChatView.newConversation(workspaceFolders, workspaces, renderedAt)

  def chatDetail(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta] = None,
    runSessionMeta: Option[RunSessionUiMeta] = None,
    workspaceFolders: List[ChatView.ChatWorkspaceFolder] = Nil,
    detailContext: ChatDetailContext = ChatDetailContext.empty,
    renderedAt: Instant = Instant.EPOCH,
  ): String =
    ChatView.detail(conversation, sessionMeta, runSessionMeta, workspaceFolders, detailContext, renderedAt)

  def chatMessagesFragment(messages: List[ConversationEntry]): String =
    ChatView.messagesFragment(messages)

  def issuesBoard(
    issues: List[AgentIssueView],
    workspaces: List[(String, String)],
    workReports: Map[IssueId, IssueWorkReport] = Map.empty,
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    tagFilter: Option[String],
    query: Option[String],
    statusFilter: Option[String] = None,
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatuses: Map[IssueId, DispatchStatusResponse] = Map.empty,
    autoDispatchEnabled: Boolean = false,
    syncStatus: IssuesView.SyncStatus = IssuesView.SyncStatus(None, 0, 0),
    agentUsage: Option[(Int, Int)] = None,
  ): String =
    IssuesView.board(
      issues = issues,
      workspaces = workspaces,
      workReports = workReports,
      workspaceFilter = workspaceFilter,
      agentFilter = agentFilter,
      priorityFilter = priorityFilter,
      tagFilter = tagFilter,
      query = query,
      statusFilter = statusFilter,
      availableAgents = availableAgents,
      dispatchStatuses = dispatchStatuses,
      autoDispatchEnabled = autoDispatchEnabled,
      syncStatus = syncStatus,
      agentUsage = agentUsage,
    )

  def issuesBoardColumns(
    issues: List[AgentIssueView],
    workspaces: List[(String, String)],
    workReports: Map[IssueId, IssueWorkReport] = Map.empty,
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatuses: Map[IssueId, DispatchStatusResponse] = Map.empty,
  ): String =
    IssuesView.boardColumnsFragment(
      issues = issues,
      workspaces = workspaces,
      workReports = workReports,
      availableAgents = availableAgents,
      dispatchStatuses = dispatchStatuses,
    )

  def issuesBoardList(
    issues: List[AgentIssueView],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
  ): String =
    IssuesView.boardListMode(
      issues = issues,
      statusFilter = statusFilter,
      query = query,
      tagFilter = tagFilter,
      workspaceFilter = workspaceFilter,
      agentFilter = agentFilter,
      priorityFilter = priorityFilter,
    )

  def issueCreateForm(
    runId: Option[String],
    workspaces: List[(String, String)],
    templates: List[IssueTemplate],
  ): String =
    IssuesView.newForm(runId, workspaces, templates)

  def issueDetail(
    issue: AgentIssueView,
    issueRuns: List[WorkspaceRun],
    availableAgents: List[AgentInfo],
    analysisDocs: List[AnalysisContextDocView],
    mergeHistory: List[MergeHistoryEntryView],
    workspaces: List[(String, String)],
    workReport: Option[IssueWorkReport] = None,
    decisions: List[Decision] = Nil,
    flash: Option[String] = None,
    checks: List[workspace.entity.RequirementCheck] = Nil,
  ): String =
    IssuesView.detail(
      issue,
      issueRuns,
      availableAgents,
      analysisDocs,
      mergeHistory,
      workspaces,
      workReport,
      decisions,
      flash,
      checks,
    )

  def issueEditForm(issue: AgentIssueView, workspaces: List[(String, String)]): String =
    IssuesView.editForm(issue, workspaces)
