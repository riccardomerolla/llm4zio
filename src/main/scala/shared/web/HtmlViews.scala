package shared.web

import java.time.Instant

import activity.entity.ActivityEvent
import config.control.{ ModelRegistryResponse, ProviderProbeStatus }
import config.entity.{ AgentInfo, WorkflowDefinition }
import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta }
import db.{ TaskReportRow, TaskRunRow }
import gateway.entity.ChatSession
import issues.entity.api.{ AgentIssueView, DispatchStatusResponse, IssueTemplate }
import shared.ids.Ids.IssueId
import workspace.entity.WorkspaceRun

object HtmlViews:

  def dashboard(summary: CommandCenterView.PipelineSummary, recentEvents: List[ActivityEvent]): String =
    CommandCenterView.page(summary, recentEvents)

  def channelsPage(cards: List[ChannelCardData], nowMs: Long): String =
    ChannelView.page(cards, nowMs)

  def recentRunsFragment(runs: List[TaskRunRow]): String =
    CommandCenterView.recentRunsFragment(runs)

  def tasksList(
    tasks: List[TaskListItem],
    workflows: List[WorkflowDefinition],
    flash: Option[String] = None,
  ): String =
    TasksView.tasksList(tasks, workflows, flash)

  def taskDetail(task: TaskListItem): String =
    TasksView.taskDetail(task)

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

  def settingsAdvancedTab: String = SettingsView.advancedTab

  def settingsIssueTemplatesTab(
    templates: List[IssueTemplate],
    flash: Option[String] = None,
  ): String =
    SettingsView.issueTemplatesTab(templates, flash)

  def modelsPage(registry: ModelRegistryResponse, statuses: List[ProviderProbeStatus]): String =
    ModelsView.page(registry, statuses)

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

  def chatEmpty(
    workspaceFolders: List[ChatView.ChatWorkspaceFolder] = Nil,
    renderedAt: Instant = Instant.EPOCH,
  ): String =
    ChatView.emptyState(workspaceFolders, renderedAt)

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

  def issuesView(
    runId: Option[String],
    issues: List[AgentIssueView],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): String =
    IssuesView.list(runId, issues, statusFilter, query, tagFilter)

  def issuesBoard(
    issues: List[AgentIssueView],
    workspaces: List[(String, String)],
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
    hasProofFilter: Option[Boolean] = None,
  ): String =
    IssuesView.board(
      issues = issues,
      workspaces = workspaces,
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
      hasProofFilter = hasProofFilter,
    )

  def issuesBoardColumns(
    issues: List[AgentIssueView],
    workspaces: List[(String, String)],
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatuses: Map[IssueId, DispatchStatusResponse] = Map.empty,
    hasProofFilter: Option[Boolean] = None,
  ): String =
    IssuesView.boardColumnsFragment(
      issues = issues,
      workspaces = workspaces,
      workReports = Map.empty,
      availableAgents = availableAgents,
      dispatchStatuses = dispatchStatuses,
      hasProofFilter = hasProofFilter,
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
    workspaces: List[(String, String)],
  ): String =
    IssuesView.detail(issue, issueRuns, availableAgents, workspaces)

  def issueEditForm(issue: AgentIssueView, workspaces: List[(String, String)]): String =
    IssuesView.editForm(issue, workspaces)
