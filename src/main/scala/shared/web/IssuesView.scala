package shared.web

import java.time.Instant

import zio.json.*

import config.entity.AgentInfo
import issues.entity.IssueWorkReport
import issues.entity.api.{ AgentIssueView, DispatchStatusResponse, IssueStatus, IssueTemplate }
import scalatags.Text.all.*
import shared.ids.Ids.IssueId
import workspace.entity.{ RunSessionMode, RunStatus, WorkspaceRun }

object IssuesView:
  final case class SyncStatus(
    lastSyncAt: Option[String],
    syncedCount: Int,
    errorCount: Int,
  )

  private val boardStatuses: List[(IssueStatus, String)] = List(
    IssueStatus.Backlog     -> "Backlog",
    IssueStatus.Todo        -> "Todo",
    IssueStatus.InProgress  -> "In Progress",
    IssueStatus.HumanReview -> "Human Review",
    IssueStatus.Rework      -> "Rework",
    IssueStatus.Merging     -> "Merging",
    IssueStatus.Done        -> "Done",
    IssueStatus.Canceled    -> "Canceled",
    IssueStatus.Duplicated  -> "Duplicated",
  )

  private def columnStatusDotCls(status: IssueStatus): String = status match
    case IssueStatus.Backlog     => "bg-slate-400"
    case IssueStatus.Todo        => "bg-blue-400"
    case IssueStatus.InProgress  => "bg-amber-400"
    case IssueStatus.HumanReview => "bg-purple-400"
    case IssueStatus.Rework      => "bg-orange-400"
    case IssueStatus.Merging     => "bg-teal-400"
    case IssueStatus.Done        => "bg-emerald-400"
    case IssueStatus.Canceled    => "bg-rose-500"
    case IssueStatus.Duplicated  => "bg-slate-500"
    case _                       => "bg-slate-500"

  private def hideableBoardColumn(status: IssueStatus): Boolean = status match
    case IssueStatus.HumanReview | IssueStatus.Rework | IssueStatus.Merging | IssueStatus.Done | IssueStatus.Canceled |
         IssueStatus.Duplicated =>
      true
    case _ => false

  def list(
    runId: Option[String],
    issues: List[AgentIssueView],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): String =
    val pageTitle = runId match
      case Some(id) => s"Issues for Run #$id"
      case None     => "Issues"

    val openCount = issues.count(i => i.status == IssueStatus.Backlog || i.status == IssueStatus.Todo)

    Layout.page("Issues", "/issues")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")(pageTitle),
              p(cls := "mt-1 text-sm text-slate-300")("Track and assign AI execution tasks"),
            ),
            div(cls := "flex items-center gap-2")(
              a(
                href := "/issues/new",
                cls  := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
              )("New issue")
            ),
          ),
          div(cls := "mt-3 flex items-center gap-2 text-sm")(
            span(cls := "rounded-full border border-emerald-400/30 bg-emerald-500/10 px-3 py-1 text-emerald-200")(
              s"$openCount open"
            ),
            span(cls := "text-slate-400")(s"${issues.size} total"),
          ),
        ),
        bulkToolbar("list"),
        importPanel("list"),
        filterBar(runId, statusFilter, query, tagFilter),
        if issues.isEmpty then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/60 px-6 py-16 text-center")(
            p(cls := "text-base text-slate-300")("No matching issues found")
          )
        else
          div(cls := "overflow-hidden rounded-xl border border-white/10 bg-slate-900/60")(
            div(
              cls := "flex items-center gap-2 border-b border-white/10 bg-slate-950/40 px-4 py-2 text-xs text-slate-300"
            )(
              input(
                `type`                       := "checkbox",
                id                           := "issues-select-all-list",
                attr("data-bulk-select-all") := "list",
                cls                          := "h-4 w-4 rounded border-white/30 bg-slate-800 text-indigo-500 focus:ring-indigo-400",
              ),
              label(`for` := "issues-select-all-list", cls := "cursor-pointer")("Select all visible"),
            ),
            issues.sortBy(i =>
              try i.updatedAt
              catch case _: Throwable => Instant.EPOCH
            ).reverse.map(issueRow),
          ),
      ),
      JsResources.inlineModuleScript("/static/client/components/issues-bulk-actions.js"),
    )

  def board(
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
    syncStatus: SyncStatus = SyncStatus(None, 0, 0),
    agentUsage: Option[(Int, Int)] = None,
    workReports: Map[IssueId, IssueWorkReport] = Map.empty,
    hasProofFilter: Option[Boolean] = None,
  ): String =
    val filteredIssues              = hasProofFilter match
      case Some(true) => BoardStats.hasProofFilter(issues, workReports)
      case _          => issues
    val stats                       = BoardStats.compute(issues, workReports)
    val queryParts                  = List(
      workspaceFilter.filter(_.nonEmpty).map(v => s"workspace=${urlEncode(v)}"),
      agentFilter.filter(_.nonEmpty).map(v => s"agent=${urlEncode(v)}"),
      priorityFilter.filter(_.nonEmpty).map(v => s"priority=${urlEncode(v)}"),
      tagFilter.filter(_.nonEmpty).map(v => s"tag=${urlEncode(v)}"),
      query.filter(_.nonEmpty).map(v => s"q=${urlEncode(v)}"),
      hasProofFilter.filter(identity).map(_ => "hasProof=true"),
    ).flatten
    val fragmentUrl                 =
      if queryParts.isEmpty then "/board/fragment"
      else s"/board/fragment?${queryParts.mkString("&")}"
    val pipelineCounts              = boardStatuses.map { (status, label) =>
      val token = issueStatusToken(status)
      val count = filteredIssues.count(_.status == status)
      (token, label, count)
    }
    val throughputPct               =
      if filteredIssues.isEmpty then 0
      else
        ((filteredIssues.count(i =>
          i.status == IssueStatus.Done || i.status == IssueStatus.Completed
        ).toDouble / filteredIssues.size.toDouble) * 100).toInt
    val (activeAgents, totalAgents) = agentUsage.getOrElse(0 -> math.max(availableAgents.size, 1))
    val syncStateCls                =
      if syncStatus.errorCount > 0 then "bg-rose-400"
      else if syncStatus.lastSyncAt.isEmpty then "bg-amber-400"
      else "bg-emerald-400"

    Layout.page("Issue Board", "/board")(
      div(cls := "space-y-4")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Issue Board"),
              p(cls := "mt-1 text-sm text-slate-300")("Kanban view for issue workflow"),
            ),
            div(cls := "flex items-center gap-3")(
              modeToggle(
                "board",
                workspaceFilter,
                agentFilter,
                priorityFilter,
                tagFilter,
                query,
                statusFilter,
                hasProofFilter,
              ),
              form(method := "post", action := "/board/auto-dispatch", cls := "flex items-center gap-2")(
                input(
                  `type` := "hidden",
                  name   := "returnTo",
                  value  := currentBoardUrl(
                    mode = "board",
                    workspaceFilter = workspaceFilter,
                    agentFilter = agentFilter,
                    priorityFilter = priorityFilter,
                    tagFilter = tagFilter,
                    query = query,
                    statusFilter = statusFilter,
                    hasProofFilter = hasProofFilter,
                  ),
                ),
                label(cls := "flex cursor-pointer items-center gap-1.5 text-xs text-slate-300")(
                  input(
                    `type`   := "checkbox",
                    name     := "enabled",
                    cls      := "h-4 w-4 rounded border-white/20 bg-slate-900 text-indigo-500 focus:ring-indigo-400",
                    if autoDispatchEnabled then checked := "checked" else (),
                    onchange := "this.form.requestSubmit();",
                  ),
                  span(if autoDispatchEnabled then "Auto dispatch on" else "Auto dispatch off"),
                ),
              ),
              label(cls := "flex items-center gap-2 text-xs text-slate-300")(
                input(
                  `type`                       := "checkbox",
                  id                           := "issues-select-all-board",
                  attr("data-bulk-select-all") := "board",
                  cls                          := "h-4 w-4 rounded border-white/30 bg-slate-800 text-indigo-500 focus:ring-indigo-400",
                ),
                span("Select all visible"),
              ),
            ),
          ),
          div(cls := "mt-3 flex flex-wrap items-center gap-2 text-xs")(
            pipelineCounts.map { (token, label, count) =>
              a(
                href := currentBoardUrl(
                  mode = "board",
                  workspaceFilter = workspaceFilter,
                  agentFilter = agentFilter,
                  priorityFilter = priorityFilter,
                  tagFilter = tagFilter,
                  query = query,
                  statusFilter = Some(token),
                  hasProofFilter = hasProofFilter,
                ),
                cls  := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-slate-200 hover:border-indigo-300/50",
              )(s"$label: $count")
            },
            span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-slate-200")(
              s"Throughput: $throughputPct%"
            ),
            span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-slate-200")(
              s"Agents: $activeAgents/$totalAgents"
            ),
            span(
              cls := "inline-flex items-center gap-1 rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-slate-200"
            )(
              span(cls := s"h-2 w-2 rounded-full $syncStateCls"),
              s"Sync ${syncStatus.syncedCount}/${syncStatus.errorCount}",
            ),
          ),
        ),
        bulkToolbar("board"),
        raw(BoardStats.statsBar(stats)),
        boardFilterBar(workspaces, workspaceFilter, agentFilter, priorityFilter, tagFilter, query, hasProofFilter),
        div(
          id                           := "issues-board-root",
          attr("data-fragment-url")    := fragmentUrl,
          attr("data-status-endpoint") := "/api/issues",
          attr("data-ws-topic")        := "activity:feed",
          attr("hx-get")               := fragmentUrl,
          attr("hx-trigger")           := "load, every 10s",
          attr("hx-swap")              := "innerHTML",
          cls                          := "min-h-[32rem]",
          attr("data-bulk-scope")      := "board",
        )(
          raw(boardColumnsFragment(filteredIssues, workspaces, workReports, availableAgents, dispatchStatuses))
        ),
      ),
      JsResources.inlineModuleScript("/static/client/components/issues-board.js"),
      JsResources.inlineModuleScript("/static/client/components/issues-bulk-actions.js"),
    )

  def boardListMode(
    issues: List[AgentIssueView],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
  ): String =
    Layout.page("Issue Board", "/board")(
      div(cls := "space-y-4")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Issue Board"),
              p(cls := "mt-1 text-sm text-slate-300")("Table view of issue workflow"),
            ),
            modeToggle("list", workspaceFilter, agentFilter, priorityFilter, tagFilter, query, statusFilter, None),
          )
        ),
        boardListFilterBar(statusFilter, query, tagFilter, workspaceFilter, agentFilter, priorityFilter),
        if issues.isEmpty then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/60 px-6 py-16 text-center")(
            p(cls := "text-base text-slate-300")("No matching issues found")
          )
        else
          div(cls := "overflow-hidden rounded-xl border border-white/10 bg-slate-900/60")(
            issues.sortBy(i =>
              try i.updatedAt
              catch case _: Throwable => Instant.EPOCH
            ).reverse.map(issueRow)
          ),
      ),
      JsResources.inlineModuleScript("/static/client/components/issues-bulk-actions.js"),
    )

  def boardColumnsFragment(issues: List[AgentIssueView], workspaces: List[(String, String)]): String =
    boardColumnsFragment(issues, workspaces, Map.empty, Nil)

  def boardColumnsFragment(
    issues: List[AgentIssueView],
    workspaces: List[(String, String)],
    workReports: Map[IssueId, IssueWorkReport],
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatuses: Map[IssueId, DispatchStatusResponse] = Map.empty,
    hasProofFilter: Option[Boolean] = None,
  ): String =
    val filteredIssues = hasProofFilter match
      case Some(true) => BoardStats.hasProofFilter(issues, workReports)
      case _          => issues
    div(
      cls := "flex gap-3 overflow-x-auto pb-2 snap-x snap-mandatory"
    )(
      boardStatuses.map { (status, label) =>
        val columnIssues = filteredIssues
          .filter(_.status == status)
          .sortBy(i =>
            try i.updatedAt
            catch case _: Throwable => Instant.EPOCH
          )
          .reverse
        val statusToken  = issueStatusToken(status)
        div(
          cls                         := "min-w-0 flex-shrink-0 rounded-xl border border-white/10 bg-slate-900/70 p-3 snap-start",
          attr("data-board-column")   := "true",
          attr("data-drop-status")    := statusToken,
          attr("data-column-status")  := statusToken,
          attr("data-column-label")   := label,
          attr("data-drop-highlight") := "false",
        )(
          div(cls := "mb-2 flex items-center justify-between gap-1")(
            div(
              cls := "flex min-w-0 flex-1 items-center gap-1.5"
            )(
              span(cls := s"inline-block h-2 w-2 flex-shrink-0 rounded-full ${columnStatusDotCls(status)}"),
              h3(cls := "text-sm font-semibold text-slate-100 truncate")(label),
              span(
                cls                       := "rounded-full bg-white/10 px-2 py-0.5 text-xs text-slate-300",
                attr("data-column-count") := statusToken,
              )(columnIssues.size.toString),
            ),
            div(cls := "flex items-center gap-1")(
              if hideableBoardColumn(status) then
                button(
                  `type`                       := "button",
                  cls                          := "flex-shrink-0 rounded p-0.5 text-slate-400 hover:bg-white/10 hover:text-slate-100",
                  title                        := s"Hide $label column",
                  attr("data-collapse-toggle") := statusToken,
                )("−")
              else (),
              button(
                `type`                        := "button",
                cls                           := "flex-shrink-0 rounded p-0.5 text-slate-400 hover:bg-white/10 hover:text-slate-100",
                title                         := s"Quick-add $label issue",
                attr("data-quick-add-toggle") := statusToken,
              )("+"),
            ),
          ),
          div(
            cls                         := "mb-2 hidden rounded-lg border border-white/10 bg-slate-800/60 p-2",
            attr("data-quick-add-form") := statusToken,
          )(
            input(
              `type`                       := "text",
              cls                          := "w-full rounded border border-white/20 bg-slate-900/80 px-2 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 outline-none focus:border-indigo-400",
              placeholder                  := "Issue title…",
              attr("data-quick-add-title") := statusToken,
              attr("autocomplete")         := "off",
            ),
            div(cls := "mt-2 flex items-center gap-1")(
              select(
                cls                             := "min-w-0 w-24 rounded border border-white/15 bg-slate-900/80 px-1.5 py-1 text-xs text-slate-100 focus:outline-none",
                attr("data-quick-add-priority") := statusToken,
              )(
                option(value := "Critical")("Critical"),
                option(value := "High")("High"),
                option(value := "Medium", selected := "selected")("Medium"),
                option(value := "Low")("Low"),
              ),
              button(
                `type`                        := "button",
                cls                           := "flex-1 rounded border border-emerald-400/30 bg-emerald-500/20 py-1 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/30",
                attr("data-quick-add-submit") := statusToken,
              )("Add"),
              button(
                `type`                        := "button",
                cls                           := "rounded border border-white/15 bg-slate-700/40 px-1.5 py-1 text-xs text-slate-400 hover:text-slate-200",
                title                         := "Cancel",
                attr("data-quick-add-cancel") := statusToken,
              )("✕"),
            ),
          ),
          div(
            cls                       := "space-y-2 max-h-[65vh] overflow-y-auto",
            attr("data-role")         := "column-cards",
            attr("data-column-cards") := statusToken,
          )(
            if columnIssues.isEmpty then
              p(cls := "rounded border border-dashed border-white/10 px-2 py-3 text-xs text-slate-500")("No issues")
            else
              columnIssues.map { issue =>
                val report = issue.id.flatMap(id => workReports.get(IssueId(id)))
                val status = issue.id.flatMap(id => dispatchStatuses.get(IssueId(id)))
                boardCard(issue, workspaces, report, availableAgents, status)
              }
          ),
        )
      },
      div(
        cls                                := "min-w-0 flex-shrink-0 rounded-xl border border-white/10 bg-slate-900/70 p-3 snap-start",
        attr("data-board-column")          := "true",
        attr("data-hidden-columns-column") := "true",
      )(
        div(cls := "mb-2 flex items-center justify-between gap-1")(
          h3(cls := "text-sm font-semibold text-slate-100")("Hidden Columns"),
          span(
            cls                               := "rounded-full bg-white/10 px-2 py-0.5 text-xs text-slate-300",
            attr("data-hidden-columns-count") := "true",
          )("0"),
        ),
        div(
          cls                              := "space-y-1 max-h-[65vh] overflow-y-auto",
          attr("data-hidden-columns-list") := "true",
        )(
          p(cls := "rounded border border-dashed border-white/10 px-2 py-3 text-xs text-slate-500")("No hidden columns")
        ),
      ),
    ).render

  /** Public entry point for rendering a single board card (used in tests and fragment endpoints). */
  def boardCardFragment(
    issue: AgentIssueView,
    workspaces: List[(String, String)],
    workReport: Option[IssueWorkReport],
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatus: Option[DispatchStatusResponse] = None,
  ): String =
    boardCard(issue, workspaces, workReport, availableAgents, dispatchStatus).render

  /** Render the issue detail page with an optional proof-of-work panel. */
  def detailWithProofOfWork(
    issue: AgentIssueView,
    issueRuns: List[WorkspaceRun],
    availableAgents: List[AgentInfo],
    workspaces: List[(String, String)],
    workReport: Option[IssueWorkReport],
  ): String =
    detailPage(issue, issueRuns, availableAgents, workspaces, workReport)

  def newForm(defaultRunId: Option[String], workspaces: List[(String, String)], templates: List[IssueTemplate])
    : String =
    val orderedTemplates = templates.sortBy(t => (!t.isBuiltin, t.name.toLowerCase))
    Layout.page("New Issue", "/issues")(
      div(cls := "-mt-6 mx-auto max-w-4xl")(
        div(cls := "mb-5")(
          h1(cls := "text-2xl font-bold text-white")("Create issue"),
          p(cls := "mt-1 text-sm text-slate-300")("Write a markdown task and optional execution metadata"),
        ),
        form(method := "post", action := "/issues", cls := "space-y-5")(
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            div(cls := "mb-4 rounded-lg border border-indigo-400/20 bg-indigo-500/10 p-4")(
              label(cls := "mb-2 block text-sm font-semibold text-indigo-100", `for` := "issueTemplateId")("Template"),
              select(
                id   := "issueTemplateId",
                name := "issueTemplateId",
                cls  := "w-full rounded-lg border border-indigo-300/30 bg-slate-900/80 px-3 py-2 text-sm text-slate-100 focus:border-indigo-300 focus:outline-none",
              )(
                option(value := "")("No template"),
                orderedTemplates.map { t =>
                  option(value := t.id)(if t.isBuiltin then s"${t.name} (built-in)" else t.name)
                },
              ),
              p(
                cls := "mt-2 text-xs text-slate-300"
              )("Selecting a template auto-fills title, description, type, priority, and tags."),
              div(id := "issue-template-variables", cls := "mt-3 grid grid-cols-1 gap-3 md:grid-cols-2"),
            ),
            div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(
              textField("title", "Title", "Describe the task", required = true),
              textField("issueType", "Type", "task", required = true),
              textField("runId", "Run ID (optional)", defaultRunId.map(_.toString).getOrElse("")),
              textField("priority", "Priority", "medium"),
              textField("tags", "Tags (comma separated)", "bug,build,analysis"),
              capabilityEditor("requiredCapabilities", "Required Capabilities", ""),
              textField("preferredAgent", "Preferred AI Agent", "gemini-cli"),
              workspaceSelect("workspaceId", "Linked Workspace", workspaces, None),
              textField("contextPath", "Context Path", "/path/to/context"),
              textField("sourceFolder", "Source Folder", "/path/to/source"),
            ),
            div(cls := "mt-4")(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "description")("Markdown task"),
              textarea(
                id          := "description",
                name        := "description",
                rows        := 14,
                cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
                placeholder := "# Task\nExplain what the agent should do, acceptance criteria, and constraints.",
                required,
              ),
            ),
          ),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Create issue"),
            a(href := "/issues", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
          ),
        ),
        script(id := "issue-create-template-data", `type` := "application/json")(raw(orderedTemplates.toJson)),
        JsResources.inlineModuleScript("/static/client/components/issue-template-form.js"),
        JsResources.inlineModuleScript("/static/client/components/capability-tag-editor.js"),
      )
    )

  def editForm(issue: AgentIssueView, workspaces: List[(String, String)]): String =
    val issueIdStr = safe(issue.id, "-")
    Layout.page(s"Edit Issue #$issueIdStr", "/issues")(
      div(cls := "-mt-6 mx-auto max-w-4xl")(
        div(cls := "mb-5 flex items-center gap-3")(
          a(
            href := s"/issues/$issueIdStr",
            cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
          )("← Back"),
          h1(cls := "text-2xl font-bold text-white")(s"Edit issue #$issueIdStr"),
        ),
        form(method := "post", action := s"/issues/$issueIdStr/edit", cls := "space-y-5")(
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(
              textField("title", "Title", safeStr(issue.title), required = true),
              textField("issueType", "Type", safeStr(issue.issueType, "task"), required = true),
              textField("priority", "Priority", safeStr(issue.priority.toString, "medium")),
              textField("tags", "Tags (comma separated)", safe(issue.tags)),
              capabilityEditor("requiredCapabilities", "Required Capabilities", safe(issue.requiredCapabilities)),
              textField("contextPath", "Context Path", safe(issue.contextPath)),
              textField("sourceFolder", "Source Folder", safe(issue.sourceFolder)),
              workspaceSelect(
                fieldName = "workspaceId",
                labelText = "Linked Workspace",
                workspaces = workspaces,
                selectedWorkspaceId = issue.workspaceId.filter(_.nonEmpty),
              ),
            ),
            div(cls := "mt-4")(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "description")("Description"),
              textarea(
                id   := "description",
                name := "description",
                rows := 14,
                cls  := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
              )(safeStr(issue.description)),
            ),
          ),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Save changes"),
            a(href := s"/issues/$issueIdStr", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
          ),
        ),
        JsResources.inlineModuleScript("/static/client/components/capability-tag-editor.js"),
      )
    )

  // ---------------------------------------------------------------------------
  // Null-safe helpers — EclipseStore's lazy deserialization can leave Option
  // fields as Some(null) or even corrupt None instances.  Guard every access.
  // ---------------------------------------------------------------------------

  /** Safely extract a String from an Option that may be corrupted. */
  private def safe(opt: => Option[String], fallback: String = ""): String =
    try
      opt.flatMap(Option(_)).filter(_.nonEmpty).getOrElse(fallback)
    catch case _: Throwable => fallback

  /** Safely map an Option[String] through a transform, returning fallback on any failure. */
  private def safeMap(opt: => Option[String], f: String => String, fallback: String = ""): String =
    try
      opt.flatMap(Option(_)).map(f).getOrElse(fallback)
    catch case _: Throwable => fallback

  /** Safely extract a list of tags from an Option[String]. */
  private def safeTags(opt: => Option[String]): List[String] =
    try
      opt.flatMap(Option(_)).filter(_.nonEmpty).toList.flatMap(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
    catch case _: Throwable => Nil

  /** Safely get a string representation of a field, catching any deserialization exception. */
  private def safeStr(thunk: => String, fallback: String = ""): String =
    try
      Option(thunk).getOrElse(fallback)
    catch case _: Throwable => fallback

  def detail(
    issue: AgentIssueView,
    issueRuns: List[WorkspaceRun],
    availableAgents: List[AgentInfo],
    workspaces: List[(String, String)],
  ): String =
    detailPage(issue, issueRuns, availableAgents, workspaces, workReport = None)

  private def detailPage(
    issue: AgentIssueView,
    issueRuns: List[WorkspaceRun],
    availableAgents: List[AgentInfo],
    workspaces: List[(String, String)],
    workReport: Option[IssueWorkReport],
  ): String =
    val issueIdStr    = safe(issue.id, "-")
    val selectedAgent = safe(issue.preferredAgent).match
      case "" => safe(issue.assignedAgent)
      case v  => v
    val requiredCaps  = safeTags(issue.requiredCapabilities)
    val convId        = safe(issue.conversationId)
    val workspaceId   = safe(issue.workspaceId)
    val workspaceName = workspaceNameOf(workspaces, workspaceId).getOrElse(workspaceId)
    val sortedRuns    = issueRuns.sortBy(_.createdAt).reverse
    val sidebarConvId = if convId.nonEmpty then convId else sortedRuns.headOption.map(_.conversationId).getOrElse("")
    val isRunning     = issue.status == IssueStatus.InProgress
    val statusToken   = issueStatusToken(issue.status)

    Layout.page(s"Issue #$issueIdStr", "/issues")(
      div(cls := "mt-2 mx-auto max-w-6xl space-y-4")(
        // ── breadcrumb ──────────────────────────────────────────────────────
        div(cls := "flex items-center gap-3")(
          a(href := "/issues", cls := "text-sm font-medium text-indigo-300 hover:text-indigo-200")("← Issues"),
          span(cls := "text-slate-600")("/"),
          span(cls := "text-sm text-slate-400")(s"#$issueIdStr"),
        ),
        // ── main two-column layout ───────────────────────────────────────
        div(cls := "flex flex-col gap-4 lg:flex-row lg:items-start")(
          // ── LEFT: title + content ──────────────────────────────────────
          div(cls := "min-w-0 flex-1 space-y-4")(
            // title card
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
              div(cls := "flex flex-wrap items-start justify-between gap-3")(
                div(cls := "min-w-0 flex-1")(
                  h1(cls := "text-2xl font-bold leading-tight text-white")(safeStr(issue.title, "Untitled")),
                  div(cls := "mt-2 flex flex-wrap items-center gap-2")(
                    statusBadge(statusToken),
                    priorityBadge(safeStr(issue.priority.toString, "medium")),
                    safeTags(issue.tags).map(tagBadge),
                  ),
                ),
                // edit button
                a(
                  href := s"/issues/$issueIdStr/edit",
                  cls  := "shrink-0 rounded-md border border-white/20 px-3 py-1.5 text-sm font-medium text-slate-300 hover:border-white/30 hover:text-white",
                )("Edit"),
              )
            ),
            // task description
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
              p(cls := "mb-3 text-sm font-semibold text-slate-300")("Description"),
              div(cls := "prose prose-invert prose-sm max-w-none text-slate-100")(
                markdownFragment(safeStr(issue.description))
              ),
            ),
            // proof-of-work (when available)
            workReport
              .map(r => ProofOfWorkView.panel(r, collapsed = false))
              .filter(_.nonEmpty)
              .map(html => div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-6")(raw(html)))
              .getOrElse(()),
            // execution history
            div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-6")(
              h2(cls := "mb-3 text-base font-semibold text-white")("Execution history"),
              if sortedRuns.isEmpty then
                p(cls := "text-sm text-slate-400")("No runs yet.")
              else
                div(cls := "space-y-3")(
                  sortedRuns.map { run =>
                    div(cls := "rounded-lg border border-white/10 bg-slate-800/70 p-4")(
                      div(cls := "flex flex-wrap items-center justify-between gap-2")(
                        span(cls := "text-sm font-semibold text-slate-100")(run.agentName),
                        span(cls := s"rounded-full px-2 py-0.5 text-xs ${runStatusBadge(run.status)}")(
                          runStatusLabel(run.status)
                        ),
                      ),
                      div(cls := "mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-400")(
                        span(cls := "font-mono text-slate-500")(s"Run ${run.id.take(8)}"),
                        span(s"Started ${prettyRelativeTime(run.createdAt)}"),
                        span(s"Duration ${formatRunDuration(run)}"),
                      ),
                      div(cls := "mt-3 flex flex-wrap items-center gap-3 text-xs")(
                        a(
                          href := s"/chat/${run.conversationId}",
                          cls  := "font-medium text-indigo-300 hover:text-indigo-200",
                        )("Open chat →"),
                        span(cls := "text-slate-500")(s"Branch ${run.branchName}"),
                      ),
                    )
                  }
                ),
            ),
          ),
          // ── RIGHT: sidebar ─────────────────────────────────────────────
          div(cls := "w-full shrink-0 space-y-4 lg:w-72")(
            // ── Run card ─────────────────────────────────────────────────
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
              p(cls := "mb-3 text-xs font-semibold uppercase tracking-wide text-slate-400")("Run with agent"),
              form(
                method                       := "post",
                action                       := s"/issues/$issueIdStr/assign",
                cls                          := "space-y-3",
                attr("data-assignment-form") := "true",
                attr("data-issue-id")        := issueIdStr,
                onsubmit                     := "const b=this.querySelector('button[type=submit]'); if(b){b.disabled=true;b.classList.add('opacity-60','cursor-not-allowed'); b.textContent='Starting...';}",
              )(
                // workspace row
                if workspaceId.nonEmpty then
                  frag(
                    input(`type` := "hidden", name := "workspaceId", value := workspaceId),
                    div(cls := "flex items-center gap-2 text-xs text-slate-400")(
                      span("Workspace:"),
                      span(cls := "font-medium text-cyan-300")(workspaceName),
                    ),
                  )
                else
                  div(
                    label(cls := "mb-1 block text-xs text-slate-400")("Workspace"),
                    workspaceSelect(
                      fieldName = "workspaceId",
                      labelText = "",
                      workspaces = workspaces,
                      selectedWorkspaceId = None,
                      includeLabel = false,
                      compact = true,
                    ),
                  )
                ,
                // agent select
                div(
                  label(cls := "mb-1 block text-xs text-slate-400")("Agent"),
                  agentSelect("agentName", selectedAgent, availableAgents),
                ),
                // hidden suggestions anchor (JS still resolves suggestions but we don't show the noisy text)
                div(
                  attr("data-assignment-suggestions") := "true",
                  attr("data-required-capabilities")  := requiredCaps.mkString(","),
                  cls                                 := "hidden",
                )(),
                // action buttons
                div(cls := "flex gap-2")(
                  button(
                    `type` := "submit",
                    cls    := "flex-1 rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
                  )(if isRunning then "Re-run" else "Run"),
                  button(
                    `type`                       := "button",
                    cls                          := "rounded-md border border-slate-600 bg-slate-800 px-3 py-2 text-sm font-medium text-slate-300 hover:bg-slate-700",
                    attr("data-auto-assign-btn") := "true",
                    attr("title")                := "Let the system pick the best agent based on required capabilities",
                  )("Auto"),
                ),
              ),
              // open chat link (if conversation exists)
              if sidebarConvId.nonEmpty then
                div(cls := "mt-3 border-t border-white/10 pt-3")(
                  a(
                    href := s"/chat/$sidebarConvId",
                    cls  := "block text-center text-sm font-medium text-indigo-300 hover:text-indigo-200",
                  )("Open agent chat →")
                )
              else (),
            ),
            // ── Status card ───────────────────────────────────────────────
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
              p(cls := "mb-3 text-xs font-semibold uppercase tracking-wide text-slate-400")("Status"),
              form(method := "post", action := s"/issues/$issueIdStr/status")(
                div(cls := "flex items-center gap-2")(
                  select(
                    name := "status",
                    cls  := "flex-1 rounded-md border border-white/15 bg-slate-800/80 px-2 py-1.5 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none",
                  )(
                    statusOption("backlog", "Backlog", Some(statusToken)),
                    statusOption("todo", "Todo", Some(statusToken)),
                    statusOption("in_progress", "In Progress", Some(statusToken)),
                    statusOption("human_review", "Human Review", Some(statusToken)),
                    statusOption("rework", "Rework", Some(statusToken)),
                    statusOption("merging", "Merging", Some(statusToken)),
                    statusOption("done", "Done", Some(statusToken)),
                    statusOption("canceled", "Canceled", Some(statusToken)),
                    statusOption("duplicated", "Duplicated", Some(statusToken)),
                  ),
                  button(
                    `type` := "submit",
                    cls    := "rounded-md border border-white/20 bg-slate-800 px-3 py-1.5 text-sm text-slate-200 hover:bg-slate-700",
                  )("Set"),
                )
              ),
            ),
            // ── Metadata card ─────────────────────────────────────────────
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4 space-y-3")(
              p(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")("Details"),
              sidebarMeta("Workspace", if workspaceName.nonEmpty then workspaceName else "—"),
              sidebarMeta("Assigned agent", safe(issue.assignedAgent, "—")),
              sidebarMeta("Preferred agent", safe(issue.preferredAgent, "—")),
              if safe(issue.externalRef).nonEmpty then
                div(cls := "flex items-baseline justify-between gap-2")(
                  span(cls := "shrink-0 text-xs text-slate-400")("External"),
                  a(
                    href   := safe(issue.externalUrl, "#"),
                    target := "_blank",
                    rel    := "noopener noreferrer",
                    cls    := "truncate text-right text-xs font-medium text-indigo-300 hover:text-indigo-200",
                  )(safe(issue.externalRef)),
                )
              else (),
              sidebarMeta("Run", safeMap(issue.runId, identity, "—")),
              if requiredCaps.nonEmpty then sidebarMeta("Capabilities", requiredCaps.mkString(", ")) else (),
              if safe(issue.contextPath).nonEmpty then sidebarMeta("Context path", safe(issue.contextPath)) else (),
              if safe(issue.sourceFolder).nonEmpty then sidebarMeta("Source folder", safe(issue.sourceFolder)) else (),
              sidebarMeta("Updated", safeStr(issue.updatedAt.toString.take(19).replace('T', ' '), "—")),
            ),
            // ── Pipeline (collapsed) ─────────────────────────────────────
            tag("details")(
              cls                              := "rounded-xl border border-white/10 bg-slate-900/70",
              attr("data-issue-pipeline-root") := "true",
              attr("data-issue-id")            := issueIdStr,
              attr("data-workspace-id")        := workspaceId,
            )(
              tag("summary")(
                cls := "cursor-pointer select-none px-4 py-3 text-xs font-semibold uppercase tracking-wide text-slate-400 hover:text-slate-300"
              )("Multi-agent pipeline"),
              div(cls := "space-y-3 px-4 pb-4 pt-2")(
                div(cls := "grid grid-cols-1 gap-2")(
                  div(
                    label(cls := "mb-1 block text-xs text-slate-400")("Pipeline"),
                    select(
                      cls                          := "w-full rounded border border-white/15 bg-slate-800/80 px-2 py-1.5 text-xs text-slate-100",
                      attr("data-pipeline-select") := "true",
                    )(),
                  ),
                  div(
                    label(cls := "mb-1 block text-xs text-slate-400")("Mode"),
                    select(
                      cls                        := "w-full rounded border border-white/15 bg-slate-800/80 px-2 py-1.5 text-xs text-slate-100",
                      attr("data-pipeline-mode") := "true",
                    )(
                      option(value := "Sequential", selected := "selected")("Sequential"),
                      option(value := "Parallel")("Parallel"),
                    ),
                  ),
                  div(
                    label(cls := "mb-1 block text-xs text-slate-400")("Workspace"),
                    input(
                      `type`                          := "text",
                      value                           := workspaceId,
                      cls                             := "w-full rounded border border-white/15 bg-slate-800/80 px-2 py-1.5 text-xs text-slate-100",
                      attr("data-pipeline-workspace") := "true",
                      attr("placeholder")             := "workspace id",
                    ),
                  ),
                ),
                button(
                  `type`                    := "button",
                  cls                       := "rounded border border-indigo-400/30 bg-indigo-500/20 px-3 py-1.5 text-xs font-semibold text-indigo-200 hover:bg-indigo-500/30",
                  attr("data-run-pipeline") := "true",
                )("Run Pipeline"),
                tag("details")(cls := "rounded border border-white/10")(
                  tag("summary")(cls := "cursor-pointer px-3 py-2 text-xs text-slate-400 hover:text-slate-300")(
                    "Builder"
                  ),
                  div(cls := "space-y-2 p-3")(
                    input(
                      `type`                     := "text",
                      cls                        := "w-full rounded border border-white/15 bg-slate-800/80 px-2 py-1.5 text-xs text-slate-100",
                      attr("data-pipeline-name") := "true",
                      attr("placeholder")        := "Pipeline name",
                    ),
                    textarea(
                      rows                        := 4,
                      cls                         := "w-full rounded border border-white/15 bg-slate-800/80 px-2 py-1.5 text-xs text-slate-100",
                      attr("data-pipeline-steps") := "true",
                      attr("placeholder")         := "agent-id|prompt override|continueOnFailure\nreview-agent||true",
                    )(),
                    button(
                      `type`                       := "button",
                      cls                          := "rounded border border-emerald-400/30 bg-emerald-500/20 px-3 py-1.5 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/30",
                      attr("data-create-pipeline") := "true",
                    )("Create Pipeline"),
                  ),
                ),
                pre(
                  cls                          := "max-h-40 overflow-auto rounded border border-white/10 bg-black/30 p-2 text-[11px] text-slate-300",
                  attr("data-pipeline-output") := "true",
                )(""),
              ),
            ),
          ),
        ),
        JsResources.inlineModuleScript("/static/client/components/issue-pipeline.js"),
        JsResources.inlineModuleScript("/static/client/components/issue-assignment-suggestions.js"),
      )
    )

  private def filterBar(
    runId: Option[String],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): Frag =
    form(method := "get", action := "/issues", cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      runId.map(id => input(`type` := "hidden", name := "run_id", value := id.toString)),
      div(cls := "grid grid-cols-1 gap-3 md:grid-cols-4")(
        input(
          `type`      := "text",
          name        := "q",
          value       := query.getOrElse(""),
          placeholder := "Search title or markdown",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        input(
          `type`      := "text",
          name        := "tag",
          value       := tagFilter.getOrElse(""),
          placeholder := "Filter by tag",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        select(
          name := "status",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          statusOption("", "Any status", statusFilter),
          statusOption("backlog", "Backlog", statusFilter),
          statusOption("todo", "Todo", statusFilter),
          statusOption("in_progress", "In progress", statusFilter),
          statusOption("human_review", "Human review", statusFilter),
          statusOption("rework", "Rework", statusFilter),
          statusOption("merging", "Merging", statusFilter),
          statusOption("done", "Done", statusFilter),
          statusOption("canceled", "Canceled", statusFilter),
          statusOption("duplicated", "Duplicated", statusFilter),
        ),
        div(cls := "flex gap-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )(
            "Filter"
          ),
          a(
            href := "/issues",
            cls  := "rounded-md border border-white/20 px-3 py-2 text-sm text-slate-200 hover:bg-white/5",
          )(
            "Reset"
          ),
        ),
      ),
    )

  private def boardFilterBar(
    workspaces: List[(String, String)],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    tagFilter: Option[String],
    query: Option[String],
    hasProofFilter: Option[Boolean] = None,
  ): Frag =
    val activeInput =
      "rounded-full border border-indigo-400/40 bg-slate-800/70 px-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none"
    val idleInput   =
      "rounded-full border border-white/15 bg-slate-800/70 px-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none"
    form(
      method := "get",
      action := "/board",
      cls    := "rounded-xl border border-white/10 bg-slate-900/60 px-4 py-3",
    )(
      div(cls := "flex flex-wrap items-center gap-2")(
        input(
          `type`      := "text",
          name        := "q",
          value       := query.getOrElse(""),
          placeholder := "Search",
          cls         := (if query.exists(_.nonEmpty) then activeInput else idleInput),
        ),
        select(
          name := "workspace",
          cls  := (if workspaceFilter.exists(_.nonEmpty) then s"$activeInput border-indigo-400"
                  else idleInput),
        )(
          option(value := "")("Any workspace"),
          workspaces.sortBy(_._2.toLowerCase).map { (id, name) =>
            option(value := id, if workspaceFilter.contains(id) then selected := "selected" else ())(name)
          },
        ),
        input(
          `type`      := "text",
          name        := "agent",
          value       := agentFilter.getOrElse(""),
          placeholder := "Agent",
          cls         := (if agentFilter.exists(_.nonEmpty) then activeInput else idleInput),
        ),
        select(
          name := "priority",
          cls  := (if priorityFilter.exists(_.nonEmpty) then activeInput else idleInput),
        )(
          option(value := "")("Any priority"),
          option(value := "critical", if priorityFilter.contains("critical") then selected := "selected" else ())(
            "Critical"
          ),
          option(value := "high", if priorityFilter.contains("high") then selected := "selected" else ())("High"),
          option(value := "medium", if priorityFilter.contains("medium") then selected := "selected" else ())("Medium"),
          option(value := "low", if priorityFilter.contains("low") then selected := "selected" else ())("Low"),
        ),
        input(
          `type`      := "text",
          name        := "tag",
          value       := tagFilter.getOrElse(""),
          placeholder := "Tag",
          cls         := (if tagFilter.exists(_.nonEmpty) then activeInput else idleInput),
        ),
        label(
          cls := s"flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs ${
              if hasProofFilter.contains(true) then "border-indigo-400/60 bg-indigo-500/20 text-indigo-200"
              else "border-white/15 bg-slate-800/70 text-slate-300"
            }"
        )(
          input(
            `type` := "checkbox",
            name   := "hasProof",
            value  := "true",
            cls    := "sr-only",
            if hasProofFilter.contains(true) then checked := "checked" else (),
          ),
          span("Has proof"),
        ),
        button(
          `type` := "submit",
          cls    := "rounded-full bg-indigo-500 px-4 py-1.5 text-xs font-semibold text-white hover:bg-indigo-400",
        )("Apply"),
        a(
          href := "/board",
          cls  := "rounded-full border border-white/20 px-3 py-1.5 text-xs text-slate-300 hover:bg-white/5",
        )("Reset"),
      )
    )

  private def boardListFilterBar(
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
  ): Frag =
    form(method := "get", action := "/board", cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      input(`type` := "hidden", name := "mode", value := "list"),
      div(cls := "grid grid-cols-1 gap-3 md:grid-cols-3 lg:grid-cols-6")(
        input(
          `type`      := "text",
          name        := "q",
          value       := query.getOrElse(""),
          placeholder := "Search",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        input(
          `type`      := "text",
          name        := "tag",
          value       := tagFilter.getOrElse(""),
          placeholder := "Tag",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        input(
          `type`      := "text",
          name        := "workspace",
          value       := workspaceFilter.getOrElse(""),
          placeholder := "Workspace",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        input(
          `type`      := "text",
          name        := "agent",
          value       := agentFilter.getOrElse(""),
          placeholder := "Agent",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        select(
          name := "priority",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          statusOption("", "Any priority", priorityFilter),
          statusOption("critical", "Critical", priorityFilter),
          statusOption("high", "High", priorityFilter),
          statusOption("medium", "Medium", priorityFilter),
          statusOption("low", "Low", priorityFilter),
        ),
        select(
          name := "status",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          statusOption("", "Any status", statusFilter),
          statusOption("backlog", "Backlog", statusFilter),
          statusOption("todo", "Todo", statusFilter),
          statusOption("in_progress", "In progress", statusFilter),
          statusOption("human_review", "Human review", statusFilter),
          statusOption("rework", "Rework", statusFilter),
          statusOption("merging", "Merging", statusFilter),
          statusOption("done", "Done", statusFilter),
          statusOption("canceled", "Canceled", statusFilter),
          statusOption("duplicated", "Duplicated", statusFilter),
        ),
      ),
      div(cls := "mt-3 flex items-center gap-2")(
        button(
          `type` := "submit",
          cls    := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
        )("Apply"),
        a(
          href := "/board?mode=list",
          cls  := "rounded-md border border-white/20 px-3 py-2 text-sm text-slate-200 hover:bg-white/5",
        )("Reset"),
      ),
    )

  private def issueRow(issue: AgentIssueView): Frag =
    val issueIdStr = safe(issue.id, "-")
    val workspace  = safe(issue.workspaceId)
    div(id := s"issue-row-$issueIdStr", cls := "border-b border-white/10 px-4 py-4 last:border-b-0 group")(
      div(cls := "flex items-start gap-3")(
        input(
          `type`                   := "checkbox",
          cls                      := "mt-1 h-4 w-4 rounded border-white/30 bg-slate-800 text-indigo-500 focus:ring-indigo-400",
          attr("data-bulk-select") := "list",
          attr("data-issue-id")    := issueIdStr,
          attr("aria-label")       := s"Select issue $issueIdStr",
        ),
        span(cls                   := "mt-1 inline-block h-3 w-3 rounded-full bg-emerald-400 flex-shrink-0"),
        div(cls := "min-w-0 flex-1")(
          div(cls := "flex flex-wrap items-center gap-2")(
            a(
              href := s"/issues/$issueIdStr",
              cls  := "text-base font-semibold text-slate-100 hover:text-indigo-300",
            )(safeStr(issue.title, "Untitled")),
            statusBadge(safeStr(issue.status.toString, "open")),
            priorityBadge(safeStr(issue.priority.toString, "medium")),
            if safe(issue.externalRef).nonEmpty then externalBadge(safe(issue.externalRef), safe(issue.externalUrl))
            else (),
            if workspace.nonEmpty then workspaceBadge(workspace) else (),
            safeTags(issue.tags).map(tagBadge),
          ),
          p(cls := "mt-1 line-clamp-2 text-sm text-slate-300")(safeStr(issue.description)),
          div(cls := "mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-400")(
            span(s"#$issueIdStr"),
            span(s"updated ${safeStr(issue.updatedAt.toString.take(19).replace('T', ' '), "unknown")}"),
            safe(issue.runId).match
              case v if v.nonEmpty => span(s"run:$v")
              case _               => ()
            ,
            safe(issue.preferredAgent).match
              case v if v.nonEmpty => span(s"agent:$v")
              case _               => ()
            ,
            workspace.match
              case v if v.nonEmpty => span(s"workspace:$v")
              case _               => ()
            ,
            safe(issue.sourceFolder).match
              case v if v.nonEmpty => span(s"source:$v")
              case _               => (),
          ),
        ),
        button(
          cls                          := "invisible group-hover:visible flex-shrink-0 self-center flex items-center justify-center w-8 h-8 rounded-md bg-red-600/20 hover:bg-red-500 text-red-400 hover:text-white transition-colors",
          attr("hx-delete")            := s"/api/issues/$issueIdStr",
          attr("hx-confirm")           := s"Delete issue #$issueIdStr '${safeStr(issue.title, "Untitled")}'?",
          attr("hx-on::after-request") := "if(event.detail.successful){window.location.reload();}",
          attr("title")                := "Delete issue",
        )(
          raw(
            """<svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd"/></svg>"""
          )
        ),
      )
    )

  private def boardCard(
    issue: AgentIssueView,
    workspaces: List[(String, String)],
    workReport: Option[IssueWorkReport] = None,
    availableAgents: List[AgentInfo] = Nil,
    dispatchStatus: Option[DispatchStatusResponse] = None,
  ): Frag =
    val issueId       = safe(issue.id, "-")
    val workspaceId   = safe(issue.workspaceId)
    val workspaceName = workspaceNameOf(workspaces, workspaceId).getOrElse(workspaceId)
    val titleText     = safeStr(issue.title, "Untitled")
    val updatedLabel  = prettyRelativeTime(issue.updatedAt)
    val agentName     =
      safe(issue.assignedAgent) match
        case v if v.nonEmpty => v
        case _               => safe(issue.preferredAgent)
    val borderCls     = issue.status match
      case IssueStatus.Backlog     => "border-l-4 border-l-slate-400"
      case IssueStatus.Todo        => "border-l-4 border-l-blue-400"
      case IssueStatus.InProgress  => "border-l-4 border-l-amber-400"
      case IssueStatus.HumanReview => "border-l-4 border-l-purple-400"
      case IssueStatus.Rework      => "border-l-4 border-l-orange-400"
      case IssueStatus.Merging     => "border-l-4 border-l-teal-400"
      case IssueStatus.Done        => "border-l-4 border-l-emerald-400"
      case IssueStatus.Canceled    => "border-l-4 border-l-rose-500"
      case IssueStatus.Duplicated  => "border-l-4 border-l-slate-500"
      case IssueStatus.Open        => "border-l-4 border-l-slate-400"
      case IssueStatus.Assigned    => "border-l-4 border-l-blue-400"
      case IssueStatus.Completed   => "border-l-4 border-l-emerald-400"
      case IssueStatus.Failed      => "border-l-4 border-l-orange-400"
      case IssueStatus.Skipped     => "border-l-4 border-l-rose-500"
    val statusDotCls  = issue.status match
      case IssueStatus.Backlog     => "rounded-full border-2 border-slate-400 bg-transparent"
      case IssueStatus.Todo        => "rounded-full border-2 border-blue-400 bg-transparent"
      case IssueStatus.InProgress  => "rounded-full bg-amber-400 animate-pulse"
      case IssueStatus.HumanReview => "rounded-full bg-purple-400"
      case IssueStatus.Rework      => "rounded-full bg-orange-400"
      case IssueStatus.Merging     => "rounded-full bg-teal-400"
      case IssueStatus.Done        => "rounded-full bg-emerald-400"
      case IssueStatus.Canceled    => "rounded-full bg-rose-500"
      case IssueStatus.Duplicated  => "rounded-full bg-slate-500"
      case IssueStatus.Open        => "rounded-full border-2 border-slate-400 bg-transparent"
      case IssueStatus.Assigned    => "rounded-full border-2 border-blue-400 bg-transparent"
      case IssueStatus.Completed   => "rounded-full bg-emerald-400"
      case IssueStatus.Failed      => "rounded-full bg-orange-400"
      case IssueStatus.Skipped     => "rounded-full bg-rose-500"
    val shortId       = s"#${issueId.take(8)}"
    val powHtml       = workReport.map(r => ProofOfWorkView.evidenceBar(r)).getOrElse("")
    val requiredCaps  = safeTags(issue.requiredCapabilities)
    val quickAgents   = eligibleAgents(availableAgents, requiredCaps)
    val showBlocked   = issue.status == IssueStatus.Todo && requiredCaps.nonEmpty && quickAgents.isEmpty
    val todoDispatch  = dispatchStatus.filter(_ => issue.status == IssueStatus.Todo)
    val externalRef   = safe(issue.externalRef)
    val externalUrl   = safe(issue.externalUrl)
    div(
      cls                         := s"block rounded-lg border border-white/10 bg-slate-800/80 p-3 hover:border-indigo-400/40 hover:bg-slate-800 $borderCls",
      attr("draggable")           := "true",
      attr("data-issue-id")       := issueId,
      attr("data-bulk-card")      := "true",
      attr("data-issue-status")   := issueStatusToken(issue.status),
      attr("data-assigned-agent") := safe(issue.assignedAgent),
      attr("data-priority")       := safeStr(issue.priority.toString).toLowerCase,
      attr("data-tags")           := safe(issue.tags),
      attr("data-workspace-id")   := workspaceId,
      attr("data-required-caps")  := requiredCaps.mkString(","),
    )(
      a(href := s"/issues/$issueId", cls := "block")(
        div(cls := "mb-1.5 flex items-center gap-1.5")(
          span(cls := s"inline-block h-2.5 w-2.5 flex-shrink-0 $statusDotCls"),
          span(cls := "text-[10px] font-mono text-slate-500")(shortId),
          todoDispatch.map(dispatchStatusBadge),
          if externalRef.nonEmpty then
            externalBadge(externalRef, externalUrl)
          else (),
        ),
        p(cls := "mb-2 text-sm font-semibold text-slate-100 line-clamp-2")(titleText),
        div(cls := "flex flex-wrap items-center gap-1")(
          priorityBadge(safeStr(issue.priority.toString, "medium")),
          safeTags(issue.tags).take(2).map(tagBadge),
          if workspaceName.nonEmpty then workspaceBadge(workspaceName) else (),
        ),
        div(cls := "mt-2 flex items-center justify-between")(
          p(cls := "text-[11px] text-slate-500")(s"Updated $updatedLabel"),
          if agentName.nonEmpty then
            span(cls := "rounded-full bg-white/10 px-2 py-0.5 text-[10px] text-slate-300")(
              agentName.take(12)
            )
          else span(),
        ),
      ),
      if quickAgents.nonEmpty then
        div(cls := "mt-2 flex items-center gap-1.5")(
          select(
            cls                             := "min-w-0 flex-1 rounded border border-white/15 bg-slate-900/80 px-1.5 py-1 text-[11px] text-slate-100",
            attr("data-quick-assign-agent") := issueId,
          )(
            quickAgents.map { agent =>
              option(value := agent.name)(agent.displayName)
            }
          ),
          button(
            `type`                           := "button",
            cls                              := "rounded border border-emerald-400/30 bg-emerald-500/20 px-2 py-1 text-[11px] font-semibold text-emerald-200 hover:bg-emerald-500/30",
            attr("data-quick-assign-action") := issueId,
          )("Assign"),
        )
      else (),
      if showBlocked then
        div(cls := "mt-2")(
          span(
            cls := "rounded-full border border-orange-400/40 bg-orange-500/20 px-2 py-0.5 text-[10px] font-semibold text-orange-200"
          )(
            "Blocked: no matching agent"
          )
        )
      else (),
      if powHtml.nonEmpty then raw(powHtml) else (),
    )

  private def dispatchStatusBadge(status: DispatchStatusResponse): Frag =
    val (symbol, badgeCls, message) =
      if status.waitingForAgent then
        ("🕐", "border-sky-400/40 bg-sky-500/15 text-sky-200", "Waiting for an available agent slot")
      else if status.capabilityMismatch then
        (
          "🔴",
          "border-rose-400/40 bg-rose-500/15 text-rose-200",
          "No registered agent matches the required capabilities",
        )
      else if status.dependencyBlocked then
        val suffix =
          if status.blockedByIds.isEmpty then ""
          else s": ${status.blockedByIds.map(id => s"#$id").mkString(", ")}"
        ("🟡", "border-amber-400/40 bg-amber-500/15 text-amber-200", s"Blocked by unresolved dependencies$suffix")
      else if status.readyForDispatch then
        ("✅", "border-emerald-400/40 bg-emerald-500/15 text-emerald-200", "Ready for dispatch on the next poll")
      else
        ("", "", "")
    if symbol.isEmpty then span()
    else
      span(
        cls   := s"inline-flex items-center rounded-full border px-1.5 py-0.5 text-[10px] leading-none $badgeCls",
        title := message,
      )(symbol)

  private def bulkToolbar(scope: String): Frag =
    div(
      cls                              := "hidden rounded-xl border border-indigo-300/25 bg-indigo-500/10 p-4",
      id                               := s"issues-bulk-toolbar-$scope",
      attr("data-bulk-toolbar")        := scope,
      attr("data-bulk-selected-count") := "0",
      attr("data-bulk-scope")          := scope,
    )(
      div(cls := "flex flex-wrap items-center gap-3")(
        p(cls := "text-sm font-semibold text-indigo-100")(
          span(attr("data-bulk-count-label") := scope)("0"),
          " selected",
        ),
        select(
          cls                     := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-bulk-agent") := scope,
        )(
          option(value := "gemini-cli")("gemini-cli"),
          option(value := "code-agent")("code-agent"),
          option(value := "chat-agent")("chat-agent"),
        ),
        input(
          `type`                        := "text",
          attr("placeholder")           := "workspace id",
          cls                           := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-bulk-workspace")   := scope,
        ),
        button(
          `type`                   := "button",
          cls                      := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500",
          attr("data-bulk-action") := "assign",
          attr("data-bulk-scope")  := scope,
        )("Bulk Assign"),
        select(
          cls                      := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-bulk-status") := scope,
        )(
          option(value := "Backlog")("Backlog"),
          option(value := "Todo")("Todo"),
          option(value := "InProgress")("In Progress"),
          option(value := "HumanReview")("Human Review"),
          option(value := "Rework")("Rework"),
          option(value := "Merging")("Merging"),
          option(value := "Done")("Done"),
          option(value := "Canceled")("Canceled"),
          option(value := "Duplicated")("Duplicated"),
        ),
        button(
          `type`                   := "button",
          cls                      := "rounded-md border border-emerald-300/40 px-3 py-1.5 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/20",
          attr("data-bulk-action") := "status",
          attr("data-bulk-scope")  := scope,
        )("Bulk Status"),
        input(
          `type`                        := "text",
          attr("placeholder")           := "add tags: tag1,tag2",
          cls                           := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-bulk-add-tags")    := scope,
        ),
        input(
          `type`                        := "text",
          attr("placeholder")           := "remove tags: tag3",
          cls                           := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-bulk-remove-tags") := scope,
        ),
        button(
          `type`                   := "button",
          cls                      := "rounded-md border border-cyan-300/40 px-3 py-1.5 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/20",
          attr("data-bulk-action") := "tags",
          attr("data-bulk-scope")  := scope,
        )("Bulk Tags"),
        button(
          `type`                   := "button",
          cls                      := "rounded-md border border-red-400/40 px-3 py-1.5 text-xs font-semibold text-red-200 hover:bg-red-500/20",
          attr("data-bulk-action") := "delete",
          attr("data-bulk-scope")  := scope,
        )("Bulk Delete"),
      ),
      p(cls := "mt-2 text-xs text-indigo-100/80", attr("data-bulk-progress") := scope)(""),
    )

  private def importPanel(scope: String): Frag =
    div(cls := "mt-3 rounded border border-white/10 bg-slate-900/60 p-3")(
      p(cls := "text-xs font-semibold text-slate-200")("Import Preview"),
      div(cls := "mt-2 flex flex-wrap items-center gap-2")(
        input(
          `type`                     := "text",
          cls                        := "min-w-[16rem] rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-import-folder") := scope,
          attr("placeholder")        := "/path/to/issues-folder",
        ),
        button(
          `type`                     := "button",
          cls                        := "rounded-md border border-indigo-300/40 px-2 py-1 text-xs text-indigo-200 hover:bg-indigo-500/20",
          attr("data-import-action") := "folder-preview",
          attr("data-bulk-scope")    := scope,
        )("Preview Folder"),
        button(
          `type`                     := "button",
          cls                        := "rounded-md border border-emerald-300/40 px-2 py-1 text-xs text-emerald-200 hover:bg-emerald-500/20",
          attr("data-import-action") := "folder-import",
          attr("data-bulk-scope")    := scope,
        )("Import Folder"),
        input(
          `type`                     := "text",
          cls                        := "rounded-md border border-white/20 bg-slate-900 px-2 py-1 text-xs text-slate-100",
          attr("data-import-repo")   := scope,
          attr("placeholder")        := "owner/repo",
        ),
        button(
          `type`                     := "button",
          cls                        := "rounded-md border border-cyan-300/40 px-2 py-1 text-xs text-cyan-200 hover:bg-cyan-500/20",
          attr("data-import-action") := "github-preview",
          attr("data-bulk-scope")    := scope,
        )("Preview GitHub"),
        button(
          `type`                     := "button",
          cls                        := "rounded-md border border-cyan-300/40 px-2 py-1 text-xs text-cyan-200 hover:bg-cyan-500/20",
          attr("data-import-action") := "github-import",
          attr("data-bulk-scope")    := scope,
        )("Import GitHub"),
      ),
      pre(
        cls                         := "mt-2 max-h-48 overflow-auto rounded border border-white/10 bg-black/30 p-2 text-[11px] text-slate-300",
        attr("data-import-preview") := scope,
      )(""),
    )

  private def textField(fieldName: String, labelText: String, fieldValue: String, required: Boolean = false): Frag =
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText),
      input(
        `type` := "text",
        id     := fieldName,
        name   := fieldName,
        value  := fieldValue,
        cls    := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
        if required then scalatags.Text.all.required else (),
      ),
    )

  private def capabilityEditor(fieldName: String, labelText: String, fieldValue: String): Frag =
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := s"${fieldName}EditorInput")(labelText),
      div(
        cls                            := "rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2",
        attr("data-capability-editor") := "true",
      )(
        input(`type`                    := "hidden", id                                               := fieldName, name := fieldName, value := fieldValue),
        div(cls                         := "mb-2 flex flex-wrap gap-2", attr("data-capability-chips") := "true"),
        input(
          `type`                        := "text",
          id                            := s"${fieldName}EditorInput",
          cls                           := "w-full rounded border border-white/10 bg-slate-900/80 px-2 py-1.5 text-sm text-slate-100",
          attr("data-capability-input") := "true",
          attr("placeholder")           := "Type capability and press Enter",
        ),
      ),
    )

  private def workspaceSelect(
    fieldName: String,
    labelText: String,
    workspaces: List[(String, String)],
    selectedWorkspaceId: Option[String],
    includeLabel: Boolean = true,
    compact: Boolean = false,
  ): Frag =
    val ordered = workspaces.sortBy(_._2.toLowerCase)
    val clsBase =
      if compact then
        "w-64 rounded-md border border-white/15 bg-slate-800/80 px-2 py-1.5 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none"
      else
        "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none"
    div(
      if includeLabel then
        label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText)
      else (),
      select(id := fieldName, name := fieldName, cls := clsBase)(
        option(value := "")("No workspace linked"),
        ordered.map { (id, name) =>
          option(
            value := id,
            if selectedWorkspaceId.contains(id) then selected := "selected" else (),
          )(s"$name ($id)")
        },
      ),
    )

  private def agentSelect(fieldName: String, selectedAgent: String, agents: List[AgentInfo]): Frag =
    val options      = agents
      .filter(_.usesAI)
      .sortBy(_.displayName.toLowerCase)
    val knownNames   = options.map(_.name.toLowerCase).toSet
    val hasSelection = selectedAgent.trim.nonEmpty
    val unknown      = selectedAgent.trim
    select(
      name := fieldName,
      cls  := "w-64 rounded-md border border-white/15 bg-slate-800/80 px-2 py-1.5 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none",
    )(
      if !hasSelection then option(value := "", selected := "selected")("Select agent") else (),
      options.map { agent =>
        option(
          value := agent.name,
          if agent.name.equalsIgnoreCase(selectedAgent) then selected := "selected" else (),
        )(s"${agent.displayName} (${agent.name})")
      },
      if hasSelection && !knownNames.contains(unknown.toLowerCase) then
        option(
          value    := unknown,
          selected := "selected",
        )(s"$unknown (unknown)")
      else (),
    )

  private def sidebarMeta(labelText: String, value: String): Frag =
    div(cls := "flex items-baseline justify-between gap-2")(
      span(cls := "shrink-0 text-xs text-slate-400")(labelText),
      span(cls := "truncate text-right text-xs font-medium text-slate-200")(value),
    )

  private def statusOption(value: String, labelText: String, current: Option[String]): Frag =
    option(
      attr("value") := value,
      if current.contains(value) then attr("selected") := "selected" else (),
    )(labelText)

  private def statusBadge(status: String): Frag =
    span(cls := s"rounded-full px-2 py-0.5 text-xs font-semibold ${statusBadgeClass(status)}")(status)

  private def priorityBadge(priority: String): Frag =
    span(cls := s"rounded-full px-2 py-0.5 text-xs font-semibold ${priorityBadgeClass(priority)}")(priority)

  private def workspaceBadge(workspace: String): Frag =
    span(
      cls := "rounded-full border border-cyan-400/30 bg-cyan-500/20 px-2 py-0.5 text-xs font-semibold text-cyan-200"
    )(
      s"workspace:$workspace"
    )

  private def tagBadge(tag: String): Frag =
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold ${tagBadgeClass(tag)}")(tag)

  private def priorityBadgeClass(priority: String): String =
    priority.toLowerCase match
      case "critical" => "bg-red-500/20 text-red-200"
      case "high"     => "bg-orange-500/20 text-orange-200"
      case "medium"   => "bg-yellow-500/20 text-yellow-200"
      case _          => "bg-blue-500/20 text-blue-200"

  private def statusBadgeClass(status: String): String =
    status.toLowerCase match
      case "backlog"      => "bg-slate-500/20 text-slate-200"
      case "todo"         => "bg-blue-500/20 text-blue-200"
      case "in_progress"  => "bg-amber-500/20 text-amber-200"
      case "human_review" => "bg-purple-500/20 text-purple-200"
      case "rework"       => "bg-orange-500/20 text-orange-200"
      case "merging"      => "bg-teal-500/20 text-teal-200"
      case "done"         => "bg-emerald-500/20 text-emerald-200"
      case "canceled"     => "bg-rose-500/20 text-rose-200"
      case "duplicated"   => "bg-slate-500/20 text-slate-200"
      case "open"         => "bg-slate-500/20 text-slate-200"
      case "assigned"     => "bg-blue-500/20 text-blue-200"
      case "completed"    => "bg-emerald-500/20 text-emerald-200"
      case "failed"       => "bg-orange-500/20 text-orange-200"
      case _              => "bg-slate-500/20 text-slate-200"

  private def runStatusLabel(status: RunStatus): String = status match
    case RunStatus.Pending                             => "Pending"
    case RunStatus.Running(RunSessionMode.Autonomous)  => "Running (Autonomous)"
    case RunStatus.Running(RunSessionMode.Interactive) =>
      "Running (Interactive)"
    case RunStatus.Running(RunSessionMode.Paused)      => "Paused"
    case RunStatus.Completed                           => "Completed"
    case RunStatus.Failed                              => "Failed"
    case RunStatus.Cancelled                           => "Cancelled"

  private def runStatusBadge(status: RunStatus): String = status match
    case RunStatus.Pending                             => "bg-slate-500/20 text-slate-200"
    case RunStatus.Running(RunSessionMode.Autonomous)  => "bg-blue-500/20 text-blue-200"
    case RunStatus.Running(RunSessionMode.Interactive) =>
      "bg-emerald-500/20 text-emerald-200"
    case RunStatus.Running(RunSessionMode.Paused)      => "bg-amber-500/20 text-amber-200"
    case RunStatus.Completed                           => "bg-emerald-500/20 text-emerald-200"
    case RunStatus.Failed                              => "bg-rose-500/20 text-rose-200"
    case RunStatus.Cancelled                           => "bg-orange-500/20 text-orange-200"

  private def formatRunDuration(run: WorkspaceRun): String =
    val seconds = java.time.Duration.between(run.createdAt, run.updatedAt).getSeconds.max(0L)
    if seconds < 60 then s"${seconds}s"
    else
      val minutes = seconds / 60
      val rest    = seconds % 60
      if minutes < 60 then s"${minutes}m ${rest}s"
      else
        val hours = minutes / 60
        val mins  = minutes % 60
        s"${hours}h ${mins}m"

  private def issueStatusToken(status: IssueStatus): String =
    status match
      case IssueStatus.Backlog     => "backlog"
      case IssueStatus.Todo        => "todo"
      case IssueStatus.Open        => "open"
      case IssueStatus.Assigned    => "assigned"
      case IssueStatus.InProgress  => "in_progress"
      case IssueStatus.HumanReview => "human_review"
      case IssueStatus.Rework      => "rework"
      case IssueStatus.Merging     => "merging"
      case IssueStatus.Done        => "done"
      case IssueStatus.Canceled    => "canceled"
      case IssueStatus.Duplicated  => "duplicated"
      case IssueStatus.Completed   => "completed"
      case IssueStatus.Failed      => "failed"
      case IssueStatus.Skipped     => "skipped"

  private def modeToggle(
    currentMode: String,
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    tagFilter: Option[String],
    query: Option[String],
    statusFilter: Option[String],
    hasProofFilter: Option[Boolean],
  ): Frag =
    div(cls := "inline-flex items-center rounded-lg border border-white/10 bg-slate-900/60 p-1")(
      a(
        href := currentBoardUrl(
          mode = "board",
          workspaceFilter = workspaceFilter,
          agentFilter = agentFilter,
          priorityFilter = priorityFilter,
          tagFilter = tagFilter,
          query = query,
          statusFilter = statusFilter,
          hasProofFilter = hasProofFilter,
        ),
        cls  := (if currentMode == "board" then
                  "rounded-md bg-indigo-500/30 px-2.5 py-1 text-xs font-semibold text-indigo-100"
                else
                  "rounded-md px-2.5 py-1 text-xs text-slate-300 hover:text-white"),
      )("Board"),
      a(
        href := currentBoardUrl(
          mode = "list",
          workspaceFilter = workspaceFilter,
          agentFilter = agentFilter,
          priorityFilter = priorityFilter,
          tagFilter = tagFilter,
          query = query,
          statusFilter = statusFilter,
          hasProofFilter = None,
        ),
        cls  := (if currentMode == "list" then
                  "rounded-md bg-indigo-500/30 px-2.5 py-1 text-xs font-semibold text-indigo-100"
                else
                  "rounded-md px-2.5 py-1 text-xs text-slate-300 hover:text-white"),
      )("List"),
    )

  private def currentBoardUrl(
    mode: String,
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    tagFilter: Option[String],
    query: Option[String],
    statusFilter: Option[String],
    hasProofFilter: Option[Boolean],
  ): String =
    val params = List(
      Some("mode" -> mode),
      workspaceFilter.filter(_.nonEmpty).map("workspace" -> _),
      agentFilter.filter(_.nonEmpty).map("agent" -> _),
      priorityFilter.filter(_.nonEmpty).map("priority" -> _),
      tagFilter.filter(_.nonEmpty).map("tag" -> _),
      query.filter(_.nonEmpty).map("q" -> _),
      statusFilter.filter(_.nonEmpty).map("status" -> _),
      hasProofFilter.filter(identity).map(_ => "hasProof" -> "true"),
    ).flatten
    if params.isEmpty then "/board"
    else
      "/board?" + params.map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")

  private def eligibleAgents(agents: List[AgentInfo], requiredCaps: List[String]): List[AgentInfo] =
    val required = requiredCaps.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct
    val active   = agents.filter(_.usesAI)
    if required.isEmpty then active.sortBy(_.displayName.toLowerCase)
    else
      active.filter { agent =>
        val tags   = agent.tags.map(_.trim.toLowerCase).filter(_.nonEmpty)
        val skills = agent.skills.map(_.skill.trim.toLowerCase).filter(_.nonEmpty)
        required.forall(cap => tags.contains(cap) || skills.contains(cap))
      }.sortBy(_.displayName.toLowerCase)

  private def externalBadge(externalRef: String, externalUrl: String): Frag =
    val kind = externalRef.takeWhile(_ != ':').trim.toUpperCase match
      case "GH" | "GITHUB" => "GH"
      case "LINEAR"        => "LN"
      case "JIRA"          => "JR"
      case _               => "EXT"
    if externalUrl.nonEmpty then
      a(
        href   := externalUrl,
        target := "_blank",
        rel    := "noopener noreferrer",
        cls    := "rounded-full border border-cyan-400/30 bg-cyan-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-cyan-200 hover:bg-cyan-500/30",
      )(kind)
    else
      span(
        cls := "rounded-full border border-cyan-400/30 bg-cyan-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-cyan-200"
      )(kind)

  private def prettyRelativeTime(ts: Instant): String =
    val now         = java.time.Clock.systemUTC().instant()
    val deltaSecs   = math.max(0L, java.time.Duration.between(ts, now).getSeconds)
    val deltaMinute = deltaSecs / 60
    val deltaHour   = deltaMinute / 60
    val deltaDay    = deltaHour / 24
    if deltaSecs < 60 then s"${deltaSecs}s ago"
    else if deltaMinute < 60 then s"${deltaMinute}m ago"
    else if deltaHour < 24 then s"${deltaHour}h ago"
    else s"${deltaDay}d ago"

  private def urlEncode(raw: String): String =
    java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8)

  private def workspaceNameOf(workspaces: List[(String, String)], workspaceId: String): Option[String] =
    workspaces.collectFirst { case (id, name) if id == workspaceId => name }

  private def tagBadgeClass(tag: String): String =
    val palette = Vector(
      "border-rose-400/30 bg-rose-500/20 text-rose-200",
      "border-amber-400/30 bg-amber-500/20 text-amber-200",
      "border-emerald-400/30 bg-emerald-500/20 text-emerald-200",
      "border-cyan-400/30 bg-cyan-500/20 text-cyan-200",
      "border-indigo-400/30 bg-indigo-500/20 text-indigo-200",
      "border-fuchsia-400/30 bg-fuchsia-500/20 text-fuchsia-200",
    )
    val idx     = math.abs(tag.toLowerCase.hashCode) % palette.size
    palette(idx)

  def markdownFragment(markdown: String): Frag =
    val normalized = markdown.replace("\r\n", "\n")
    val lines      = normalized.split("\n", -1).toList
    val lineCount  = lines.length

    def collectWhile[A](start: Int)(f: String => Option[A]): (List[A], Int) =
      if start >= lineCount then (Nil, start)
      else
        f(lines(start)) match
          case Some(value) =>
            val (tail, next) = collectWhile(start + 1)(f)
            (value :: tail, next)
          case None        =>
            (Nil, start)

    def collectUntil(start: Int, stop: String => Boolean): (List[String], Int) =
      if start >= lineCount || stop(lines(start)) then (Nil, start)
      else
        val (tail, next) = collectUntil(start + 1, stop)
        (lines(start) :: tail, next)

    def headingFrag(level: Int, textValue: String): Frag =
      val headingCls = "mt-5 mb-2 font-semibold text-slate-50"
      level match
        case 1 => h1(cls := s"$headingCls text-2xl")(renderInline(textValue))
        case 2 => h2(cls := s"$headingCls text-xl")(renderInline(textValue))
        case 3 => h3(cls := s"$headingCls text-lg")(renderInline(textValue))
        case 4 => h4(cls := s"$headingCls text-base")(renderInline(textValue))
        case _ => h5(cls := s"$headingCls text-sm")(renderInline(textValue))

    def isMarkdownTableHeader(idx: Int): Boolean =
      if idx + 1 >= lineCount then false
      else
        val header = parseMarkdownTableRow(lines(idx))
        val sep    = parseMarkdownTableRow(lines(idx + 1))
        header.nonEmpty && isMarkdownTableDivider(sep, header.length)

    def parseAt(idx: Int): List[Frag] =
      if idx >= lineCount then Nil
      else
        val line = lines(idx)
        if line.trim.isEmpty then parseAt(idx + 1)
        else if line.trim.startsWith("```") then
          val lang                  = line.trim.stripPrefix("```").trim
          val (codeLines, fenceIdx) = collectUntil(idx + 1, l => l.trim.startsWith("```"))
          val next                  = if fenceIdx < lineCount then fenceIdx + 1 else fenceIdx
          val block                 = div(cls := "my-4 rounded-lg border border-white/20 bg-slate-800 p-0")(
            if lang.nonEmpty then
              div(cls := "border-b border-white/15 px-3 py-1 text-xs uppercase tracking-wide text-slate-400")(lang)
            else (),
            pre(cls := "overflow-auto px-3 py-3 text-sm leading-6 text-slate-100")(codeLines.mkString("\n")),
          )
          block :: parseAt(next)
        else if isMarkdownTableHeader(idx) then
          val (tableLines, next) =
            collectWhile(idx)(current => Option.when(current.trim.nonEmpty && current.contains("|"))(current))
          markdownTableFrag(tableLines) :: parseAt(next)
        else
          headingLevel(line) match
            case Some((level, textValue)) =>
              headingFrag(level, textValue) :: parseAt(idx + 1)
            case None                     =>
              if line.trim.startsWith(">") then
                val (quoteLines, next) = collectWhile(idx)(current =>
                  Option.when(current.trim.startsWith(">"))(current.trim.stripPrefix(">").trim)
                )
                blockquote(cls := "my-3 border-l-4 border-indigo-400/40 pl-3 text-slate-300")(
                  paragraphWithBreaks(quoteLines)
                ) :: parseAt(next)
              else
                unorderedItem(line) match
                  case Some(_) =>
                    val (items, next) =
                      collectWhile(idx)(current => unorderedItem(current))
                    ul(cls := "my-3 list-disc space-y-1 pl-6 text-slate-100")(
                      items.map(item => li(renderInline(item)))
                    ) :: parseAt(next)
                  case None    =>
                    orderedItem(line) match
                      case Some(_) =>
                        val (items, next) =
                          collectWhile(idx)(current => orderedItem(current))
                        ol(cls := "my-3 list-decimal space-y-1 pl-6 text-slate-100")(
                          items.map(item => li(renderInline(item)))
                        ) :: parseAt(next)
                      case None    =>
                        val (paragraphLines, next) = collectWhile(idx)(current =>
                          Option.when(current.trim.nonEmpty && !startsBlock(current))(current)
                        )
                        p(cls := "my-3 whitespace-normal text-sm leading-7 text-slate-100")(
                          paragraphWithBreaks(paragraphLines)
                        ) :: parseAt(next)

    div(parseAt(0))

  private def paragraphWithBreaks(lines: List[String]): Seq[Frag] =
    lines.zipWithIndex.flatMap {
      case (line, idx) =>
        val parts = renderInline(line)
        if idx < lines.length - 1 then parts :+ br()
        else parts
    }

  private def renderInline(text: String): Seq[Frag] =
    def flush(buffer: String, acc: List[Frag]): List[Frag] =
      if buffer.nonEmpty then buffer :: acc else acc

    def loop(i: Int, buffer: String, acc: List[Frag]): List[Frag] =
      if i >= text.length then flush(buffer, acc).reverse
      else if text.startsWith("**", i) then
        val end = text.indexOf("**", i + 2)
        if end > i + 1 then
          val updated = strong(renderInline(text.substring(i + 2, end))) :: flush(buffer, acc)
          loop(end + 2, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '*' then
        val end = text.indexOf('*', i + 1)
        if end > i then
          val updated = em(renderInline(text.substring(i + 1, end))) :: flush(buffer, acc)
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '`' then
        val end = text.indexOf('`', i + 1)
        if end > i then
          val updated =
            code(cls := "rounded bg-black/30 px-1 py-0.5 text-slate-100")(text.substring(i + 1, end)) :: flush(
              buffer,
              acc,
            )
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '[' then
        val closeBracket = text.indexOf(']', i + 1)
        val openParen    = if closeBracket >= 0 then text.indexOf('(', closeBracket + 1) else -1
        val closeParen   = if openParen >= 0 then text.indexOf(')', openParen + 1) else -1
        if closeBracket > i && openParen == closeBracket + 1 && closeParen > openParen then
          val label          = text.substring(i + 1, closeBracket)
          val urlCandidate   = text.substring(openParen + 1, closeParen)
          val linkFrag: Frag = safeHref(urlCandidate) match
            case Some(urlHref) =>
              a(
                href := urlHref,
                cls  := "text-indigo-300 underline decoration-indigo-400/60 underline-offset-2 hover:text-indigo-200",
              )(label)
            case None          =>
              span(s"[$label]($urlCandidate)")
          loop(closeParen + 1, "", linkFrag :: flush(buffer, acc))
        else loop(i + 1, buffer + text.charAt(i), acc)
      else loop(i + 1, buffer + text.charAt(i), acc)

    loop(0, "", Nil)

  private def safeHref(href: String): Option[String] =
    val normalized = href.trim
    val lower      = normalized.toLowerCase
    if lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/") || lower.startsWith("#")
    then
      Some(normalized)
    else None

  private def headingLevel(line: String): Option[(Int, String)] =
    val trimmed = line.trim
    val hashes  = trimmed.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= 6 && trimmed.drop(hashes).startsWith(" ") then
      Some((hashes, trimmed.drop(hashes).trim))
    else None

  private def unorderedItem(line: String): Option[String] =
    val trimmed = line.trim
    if trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") then Some(trimmed.drop(2).trim)
    else None

  private def orderedItem(line: String): Option[String] =
    val trimmed = line.trim
    val dotIdx  = trimmed.indexOf('.')
    if dotIdx > 0 && trimmed.take(dotIdx).forall(_.isDigit) && trimmed.lift(dotIdx + 1).contains(' ') then
      Some(trimmed.drop(dotIdx + 2).trim)
    else None

  private def startsBlock(line: String): Boolean =
    line.trim.startsWith("```") ||
    headingLevel(line).isDefined ||
    line.trim.startsWith(">") ||
    unorderedItem(line).isDefined ||
    orderedItem(line).isDefined

  private def markdownTableFrag(lines: List[String]): Frag =
    val parsedRows = lines.map(parseMarkdownTableRow).filter(_.nonEmpty)
    parsedRows match
      case header :: divider :: tail if isMarkdownTableDivider(divider, header.length) =>
        val bodyRows = tail.map(normalizeRow(_, header.length))
        div(cls := "my-4 overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10 rounded-lg border border-white/10 bg-black/20 text-sm")(
            thead(
              tr(header.map(col =>
                th(cls := "px-3 py-2 text-left font-semibold text-slate-100")(inlineWithBreaks(col))
              ))
            ),
            tbody(
              bodyRows.map { row =>
                tr(
                  cls := "odd:bg-white/0 even:bg-white/5",
                  row.map(col => td(cls := "px-3 py-2 text-slate-200")(inlineWithBreaks(col))),
                )
              }
            ),
          )
        )
      case _                                                                           =>
        p(cls := "my-3 whitespace-normal text-sm leading-7 text-slate-100")(lines.mkString("\n"))

  private def parseMarkdownTableRow(line: String): List[String] =
    val trimmed  = line.trim
    val stripped = trimmed.stripPrefix("|").stripSuffix("|")
    stripped.split("\\|", -1).toList.map(_.trim)

  private def isMarkdownTableDivider(row: List[String], expectedCols: Int): Boolean =
    row.length == expectedCols && row.forall(cell => cell.matches("^:?-{3,}:?$"))

  private def normalizeRow(row: List[String], size: Int): List[String] =
    if row.length >= size then row.take(size)
    else row ++ List.fill(size - row.length)("")

  private def inlineWithBreaks(text: String): Seq[Frag] =
    val parts = text.split("(?i)<br\\s*/?>", -1).toList
    parts.zipWithIndex.flatMap {
      case (part, idx) =>
        val rendered = renderInline(part)
        if idx < parts.length - 1 then rendered :+ br()
        else rendered
    }
