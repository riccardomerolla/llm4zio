package shared.web

import config.control.{ ModelRegistryResponse, ProviderProbeStatus }
import config.entity.{ AgentChannelBinding, AgentInfo, WorkflowDefinition }
import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta }
import db.{ TaskReportRow, TaskRunRow }
import issues.entity.api.{ AgentAssignment, AgentIssue }

object HtmlViews:

  def dashboard(runs: List[TaskRunRow], workflowCount: Int): String =
    DashboardView.dashboard(runs, workflowCount)

  def channelsPage(cards: List[ChannelCardData], nowMs: Long): String =
    ChannelView.page(cards, nowMs)

  def recentRunsFragment(runs: List[TaskRunRow]): String =
    DashboardView.recentRunsContent(runs).render

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

  def agentsPage(
    agents: List[AgentInfo],
    bindingsByAgent: Map[String, List[AgentChannelBinding]],
    flash: Option[String] = None,
  ): String =
    AgentsView.list(agents, bindingsByAgent, flash)

  def newCustomAgentPage(
    values: Map[String, String] = Map.empty,
    flash: Option[String] = None,
  ): String =
    AgentsView.newCustomAgentForm(values, flash)

  def editCustomAgentPage(
    name: String,
    values: Map[String, String],
    flash: Option[String] = None,
  ): String =
    AgentsView.editCustomAgentForm(name, values, flash)

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
  ): String =
    ChatView.dashboard(conversations, sessionMetaByConversation)

  def chatDetail(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta] = None,
  ): String =
    ChatView.detail(conversation, sessionMeta)

  def chatMessagesFragment(messages: List[ConversationEntry]): String =
    ChatView.messagesFragment(messages)

  def issuesView(
    runId: Option[String],
    issues: List[AgentIssue],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): String =
    IssuesView.list(runId, issues, statusFilter, query, tagFilter)

  def issueCreateForm(runId: Option[String]): String =
    IssuesView.newForm(runId)

  def issueDetail(
    issue: AgentIssue,
    assignments: List[AgentAssignment],
    availableAgents: List[AgentInfo],
  ): String =
    IssuesView.detail(issue, assignments, availableAgents)
