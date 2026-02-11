package web.views

import db.{ CobolAnalysisRow, CobolFileRow, DependencyRow, MigrationRunRow, PhaseProgressRow }
import models.{ AgentAssignment, AgentInfo, AgentIssue, ChatConversation, ConversationMessage }

object HtmlViews:

  def dashboard(runs: List[MigrationRunRow]): String =
    DashboardView.dashboard(runs)

  def runsList(runs: List[MigrationRunRow], pageNumber: Int, pageSize: Int): String =
    RunsView.list(runs, pageNumber, pageSize)

  def runDetail(run: MigrationRunRow, phases: List[PhaseProgressRow]): String =
    RunsView.detail(run, phases)

  def runForm: String =
    RunsView.form

  def recentRunsFragment(runs: List[MigrationRunRow]): String =
    RunsView.recentRunsFragment(runs)

  def analysisList(runId: Long, files: List[CobolFileRow], analyses: List[CobolAnalysisRow]): String =
    AnalysisView.list(runId, files, analyses)

  def analysisDetail(file: CobolFileRow, analysis: CobolAnalysisRow): String =
    AnalysisView.detail(file, analysis)

  def analysisSearchFragment(files: List[CobolFileRow]): String =
    AnalysisView.searchFragment(files)

  def graphPage(runId: Long, deps: List[DependencyRow]): String =
    GraphView.page(runId, deps)

  def settingsPage(settings: Map[String, String], flash: Option[String] = None): String =
    SettingsView.page(settings, flash)

  def agentsPage(agents: List[AgentInfo]): String =
    AgentsView.list(agents)

  def agentConfigPage(
    agent: AgentInfo,
    overrideSettings: Map[String, String],
    globalSettings: Map[String, String],
    flash: Option[String] = None,
  ): String =
    AgentsView.agentConfigPage(agent, overrideSettings, globalSettings, flash)

  def chatDashboard(conversations: List[ChatConversation]): String =
    ChatView.dashboard(conversations)

  def chatDetail(conversation: ChatConversation): String =
    ChatView.detail(conversation)

  def chatMessagesFragment(messages: List[ConversationMessage]): String =
    ChatView.messagesFragment(messages)

  def issuesView(
    runId: Option[Long],
    issues: List[AgentIssue],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): String =
    IssuesView.list(runId, issues, statusFilter, query, tagFilter)

  def issueCreateForm(defaultRunId: Option[Long]): String =
    IssuesView.newForm(defaultRunId)

  def issueDetail(issue: AgentIssue, assignments: List[AgentAssignment]): String =
    IssuesView.detail(issue, assignments)
