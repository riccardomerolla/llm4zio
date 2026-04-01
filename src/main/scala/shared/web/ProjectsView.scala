package shared.web

import java.time.Instant

import issues.entity.api.AgentIssueView
import project.entity.{ Project, ProjectFilter }
import scalatags.Text.all.*

final case class ProjectListItem(
  id: String,
  name: String,
  description: Option[String],
  workspaceCount: Int,
  activeIssueCount: Int,
  lastActivity: Instant,
)

final case class ProjectWorkspaceRow(
  workspaceId: String,
  workspaceName: String,
  description: Option[String],
  enabled: Boolean,
  defaultAgent: Option[String],
  healthLabel: String,
  healthTone: String,
  coverage: String,
  lastRunAt: Option[Instant],
)

final case class ProjectAnalysisRow(
  workspaceId: String,
  workspaceName: String,
  stateLabel: String,
  coverage: String,
  lastRunAt: Option[Instant],
)

final case class ProjectDetailPageData(
  project: Project,
  activeTab: String,
  assignedWorkspaces: List[ProjectWorkspaceRow],
  boardIssues: List[AgentIssueView],
  boardWorkspaces: List[(String, String)],
  analysisRows: List[ProjectAnalysisRow],
  availableAgents: List[(String, String)],
)

object ProjectsView:

  private val tabs = List(
    "workspaces" -> "Workspaces",
    "settings"   -> "Settings",
    "board"      -> "Board",
    "analysis"   -> "Analysis",
  )

  def page(projects: List[ProjectListItem]): String =
    Layout.page("Projects", "/projects")(
      div(cls := "space-y-4")(
        Components.pageHeader(
          "Projects",
          "Group workspaces into delivery streams with shared board, analysis, and agent defaults",
        ),
        // Inline Quick Create
        div(cls := "rounded-lg border border-white/10 bg-slate-900/60 px-4 py-3")(
          p(cls := "mb-2 text-[11px] font-semibold uppercase tracking-wide text-slate-500")("Quick Create"),
          form(action := "/projects", method := "post", cls := "flex flex-wrap items-center gap-2")(
            input(
              name        := "name",
              required    := "required",
              placeholder := "Project name",
              cls         := "flex-1 min-w-40 rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
            ),
            input(
              name        := "description",
              placeholder := "Description (optional)",
              cls         := "flex-[2] min-w-48 rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
            ),
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500 whitespace-nowrap flex-shrink-0",
            )("Create Project"),
          ),
        ),
        if projects.isEmpty then emptyState
        else
          div(cls := "rounded-lg border border-white/10 overflow-hidden")(
            tag("table")(cls := "w-full text-sm")(
              tag("thead")(
                tag("tr")(cls := "border-b border-white/10 bg-white/5")(
                  tag("th")(
                    cls := "px-4 py-2 text-left text-xs font-medium uppercase tracking-wide text-gray-400"
                  )("Project"),
                  tag("th")(
                    cls := "hidden px-4 py-2 text-right text-xs font-medium uppercase tracking-wide text-gray-400 sm:table-cell"
                  )("Workspaces"),
                  tag("th")(
                    cls := "hidden px-4 py-2 text-right text-xs font-medium uppercase tracking-wide text-gray-400 sm:table-cell"
                  )("Issues"),
                  tag("th")(
                    cls := "hidden px-4 py-2 text-left text-xs font-medium uppercase tracking-wide text-gray-400 sm:table-cell"
                  )("Last Activity"),
                  tag("th")(cls := "px-4 py-2")(),
                )
              ),
              tag("tbody")(cls := "divide-y divide-white/10")(
                projects.sortBy(_.name.toLowerCase).map(projectRow)
              ),
            )
          ),
      )
    )

  def filterOptionsFragment(projects: List[Project], currentFilter: ProjectFilter): String =
    val selectedId = currentFilter match
      case ProjectFilter.All              => "all"
      case ProjectFilter.Selected(projId) => projId.value
    div(
      cls := "flex-1",
    )(
      select(
        id       := "project-filter-select",
        cls      := "rounded border border-white/10 bg-transparent px-2 py-0.5 text-xs text-gray-300 hover:text-white cursor-pointer",
        onchange := "setProjectFilter(this.value)",
      )(
        option(value := "all", if selectedId == "all" then selected := "selected" else frag())("All Projects"),
        projects.sortBy(_.name.toLowerCase).map { p =>
          option(
            value := p.id.value,
            if selectedId == p.id.value then selected := "selected" else frag(),
          )(p.name)
        },
      )
    ).render

  def detailPage(data: ProjectDetailPageData): String =
    val project = data.project
    val tabName = tabs.find(_._1 == data.activeTab).map(_._2).getOrElse("Workspaces")
    Layout.page(project.name, s"/projects/${project.id.value}")(
      div(cls := "space-y-6")(
        div(cls := "flex items-center gap-3 text-sm")(
          a(href := "/projects", cls := "font-medium text-cyan-300 hover:text-cyan-200")("Projects"),
          span(cls := "text-slate-600")("→"),
          a(
            href := s"/projects/${project.id.value}",
            cls  := "font-medium text-slate-300 hover:text-white",
          )(project.name),
          span(cls := "text-slate-600")("→"),
          span(cls := "text-slate-400")(tabName),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-start justify-between gap-4")(
            div(
              h1(cls := "text-2xl font-bold text-white")(project.name),
              project.description.fold[Frag](frag())(desc => p(cls := "mt-1 text-sm text-slate-300")(desc)),
              p(cls := "mt-2 text-xs text-slate-500")(
                s"${data.assignedWorkspaces.size} workspace(s) · Updated ${timestamp(project.updatedAt)}"
              ),
            ),
            div(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-2 text-right text-xs text-slate-300")(
              p(cls := "font-semibold uppercase tracking-wide text-slate-500")("Defaults"),
              p(cls := "mt-1")(data.project.settings.defaultAgent.getOrElse("No default agent")),
            ),
          ),
          div(cls := "mt-4 border-b border-white/10")(
            tag("nav")(cls := "-mb-px flex flex-wrap gap-5", attr("aria-label") := "Project detail tabs")(
              tabs.map {
                case (key, label) =>
                  val active = key == data.activeTab
                  a(
                    href := s"/projects/${project.id.value}?tab=$key",
                    cls  := (if active then
                              "border-b-2 border-cyan-400 py-3 text-sm font-medium text-white"
                            else
                              "border-b-2 border-transparent py-3 text-sm font-medium text-slate-400 hover:border-white/20 hover:text-white"),
                  )(label)
              }
            )
          ),
        ),
        data.activeTab match
          case "settings" => settingsTab(data)
          case "board"    => boardTab(data)
          case "analysis" => analysisTab(data)
          case _          => workspacesTab(data),
      ),
      // Board tab requires the Fizzy board component scripts
      if data.activeTab == "board" then
        frag(
          JsResources.inlineModuleScript("/static/client/components/ab-board-column.js"),
          JsResources.inlineModuleScript("/static/client/components/ab-board-layout.js"),
          JsResources.inlineModuleScript("/static/client/components/issues-board-sync.js"),
          JsResources.inlineModuleScript("/static/client/components/ab-issues-board.js"),
        )
      else frag(),
    )

  private def emptyState: Frag =
    div(cls := "rounded-lg border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
      p(cls := "text-slate-300")("No projects yet."),
      p(cls := "mt-1 text-sm text-slate-500")(
        "Use Quick Create above to add your first project."
      ),
    )

  private def projectRow(project: ProjectListItem): Frag =
    tag("tr")(
      cls     := "hover:bg-white/5 cursor-pointer transition-colors",
      onclick := s"window.location='/projects/${project.id}'",
    )(
      tag("td")(cls := "px-4 py-3")(
        span(cls := "font-medium text-white")(project.name),
        project.description.fold[Frag](frag())(d =>
          p(cls := "mt-0.5 text-xs text-slate-400 truncate max-w-xs")(d)
        ),
      ),
      tag("td")(cls := "hidden px-4 py-3 text-right text-sm text-slate-300 sm:table-cell")(
        project.workspaceCount.toString
      ),
      tag("td")(cls := "hidden px-4 py-3 text-right sm:table-cell")(
        if project.activeIssueCount > 0 then
          span(
            cls := "rounded-full border border-amber-400/30 bg-amber-500/15 px-2 py-0.5 text-xs font-semibold text-amber-200"
          )(
            project.activeIssueCount.toString
          )
        else span(cls := "text-sm text-slate-500")("0")
      ),
      tag("td")(cls := "hidden px-4 py-3 text-sm text-slate-400 sm:table-cell")(
        timestamp(project.lastActivity)
      ),
      tag("td")(cls := "px-4 py-3 text-right")(
        a(
          href    := s"/projects/${project.id}",
          cls     := "text-xs font-semibold text-cyan-300 hover:text-cyan-200",
          onclick := "event.stopPropagation()",
        )("Open →")
      ),
    )

  private def workspacesTab(data: ProjectDetailPageData): Frag =
    div(cls := "grid gap-6 xl:grid-cols-[1.1fr,0.9fr]")(
      div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
        div(cls := "flex items-center justify-between gap-3")(
          div(
            h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Assigned Workspaces"),
            p(cls := "mt-1 text-xs text-slate-500")("Workspace health is derived from enablement and analysis state."),
          ),
          span(
            cls := "rounded-full bg-white/10 px-2 py-0.5 text-xs text-slate-300"
          )(data.assignedWorkspaces.size.toString),
        ),
        if data.assignedWorkspaces.isEmpty then
          div(cls := "mt-4 rounded-lg border border-dashed border-white/10 p-6 text-sm text-slate-400")(
            "No workspaces assigned to this project yet."
          )
        else
          div(cls := "mt-4 space-y-3")(data.assignedWorkspaces.map(workspaceRow)*),
      ),
      div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
        h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Create Workspace"),
        p(cls := "mt-1 text-xs text-slate-500")("Create a new workspace directly linked to this project."),
        form(
          action := s"/projects/${data.project.id.value}/workspaces/create",
          method := "post",
          cls    := "mt-4 space-y-3",
        )(
          labeledInput("Name", "name", ""),
          labeledInput("Local path", "localPath", ""),
          label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-slate-400")(
            "CLI Tool",
            select(
              name := "cliTool",
              cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
            )(
              option(value := "claude")("claude"),
              option(value := "codex")("codex"),
              option(value := "gemini")("gemini"),
            ),
          ),
          labeledInput("Default branch", "defaultBranch", "main"),
          div(cls := "flex justify-end")(
            button(
              `type` := "submit",
              cls    := "rounded bg-cyan-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-cyan-500",
            )("Create workspace")
          ),
        ),
      ),
    )

  private def settingsTab(data: ProjectDetailPageData): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Project Settings"),
      p(cls := "mt-1 text-xs text-slate-500")(
        "These defaults apply to the project as a whole and can guide issue generation and merge behavior."
      ),
      form(action := s"/projects/${data.project.id.value}/settings", method := "post", cls := "mt-5 space-y-5")(
        div(cls := "grid gap-4 lg:grid-cols-2")(
          labeledInput("Name", "name", data.project.name),
          labeledInput("Description", "description", data.project.description.getOrElse("")),
        ),
        div(cls := "grid gap-4 lg:grid-cols-2")(
          label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-slate-400")(
            "Default Agent",
            select(
              name := "default_agent",
              cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
            )(
              option(value := "")("No default"),
              data.availableAgents.map {
                case (agentValue, labelText) =>
                  option(
                    value    := agentValue,
                    selected := data.project.settings.defaultAgent.contains(agentValue),
                  )(labelText)
              },
            ),
          ),
          labeledInput(
            "Analysis Schedule (minutes)",
            "analysis_schedule_minutes",
            data.project.settings.analysisSchedule.map(_.toMinutes.toString).getOrElse(""),
          ),
        ),
        div(cls := "grid gap-4 lg:grid-cols-2")(
          label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-slate-400")(
            span("Merge Policy"),
            label(cls := "mt-3 flex items-center gap-2 text-sm font-normal normal-case tracking-normal text-slate-200")(
              input(
                `type`  := "checkbox",
                name    := "require_ci",
                checked := data.project.settings.mergePolicy.requireCi,
              ),
              span("Require CI before merge"),
            ),
          ),
          labeledInput("CI Command", "ci_command", data.project.settings.mergePolicy.ciCommand.getOrElse("")),
        ),
        label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-slate-400")(
          "Prompt Template Defaults",
          textarea(
            name := "prompt_template_defaults",
            rows := 8,
            cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 font-mono text-xs text-slate-100",
          )(data.project.settings.promptTemplateDefaults.toList.sortBy(
            _._1
          ).map { case (k, v) => s"$k=$v" }.mkString("\n")),
          p(cls := "mt-2 text-xs font-normal normal-case tracking-normal text-slate-500")(
            "One key=value entry per line. Useful for issue-type specific prompt defaults."
          ),
        ),
        div(cls := "flex justify-end")(
          button(
            `type` := "submit",
            cls    := "rounded bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-500",
          )("Save settings")
        ),
      ),
    )

  private def boardTab(data: ProjectDetailPageData): Frag =
    div(cls := "space-y-4")(
      div(cls := "rounded-xl border border-white/10 bg-slate-900/70 px-5 py-4")(
        h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Project Board"),
        p(cls := "mt-1 text-xs text-slate-500")(
          "Aggregate kanban view filtered to issues that belong to workspaces assigned to this project."
        ),
      ),
      if data.boardIssues.isEmpty then
        div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center text-slate-400")(
          "No project issues found for the assigned workspaces."
        )
      else
        div(cls := "rounded-xl border border-white/10 bg-slate-950/50 p-4")(
          raw(IssuesView.boardColumnsFragment(data.boardIssues, data.boardWorkspaces))
        ),
    )

  private def analysisTab(data: ProjectDetailPageData): Frag =
    div(cls := "space-y-4")(
      div(cls := "rounded-xl border border-white/10 bg-slate-900/70 px-5 py-4")(
        h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Analysis Coverage"),
        p(cls := "mt-1 text-xs text-slate-500")(
          "Latest analysis coverage across all workspaces assigned to this project."
        ),
      ),
      if data.analysisRows.isEmpty then
        div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center text-slate-400")(
          "Assign at least one workspace to see project analysis coverage."
        )
      else
        div(cls := "grid gap-4 lg:grid-cols-2")(data.analysisRows.map(analysisRow)*),
    )

  private def workspaceRow(row: ProjectWorkspaceRow): Frag =
    div(cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
      div(cls := "flex flex-wrap items-start justify-between gap-3")(
        div(
          div(cls := "flex items-center gap-2")(
            h3(cls := "text-base font-semibold text-white")(row.workspaceName),
            statusChip(row.healthLabel, row.healthTone),
          ),
          p(cls := "mt-1 text-xs font-mono text-slate-500")(row.workspaceId),
          row.description.fold[Frag](frag())(desc => p(cls := "mt-2 text-sm text-slate-400")(desc)),
        ),
      ),
      div(cls := "mt-4 grid gap-3 md:grid-cols-3")(
        infoCell("Default agent", row.defaultAgent.getOrElse("—")),
        infoCell("Coverage", row.coverage),
        infoCell("Last run", row.lastRunAt.map(timestamp).getOrElse("—")),
      ),
    )

  private def analysisRow(row: ProjectAnalysisRow): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h3(cls := "text-base font-semibold text-white")(row.workspaceName),
          p(cls := "mt-1 text-xs font-mono text-slate-500")(row.workspaceId),
        ),
        statusChip(row.stateLabel, toneForState(row.stateLabel)),
      ),
      div(cls := "mt-4 grid gap-3 sm:grid-cols-2")(
        infoCell("Coverage", row.coverage),
        infoCell("Last run", row.lastRunAt.map(timestamp).getOrElse("—")),
      ),
    )

  private def labeledInput(labelText: String, nameValue: String, valueText: String): Frag =
    label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-slate-400")(
      labelText,
      input(
        name  := nameValue,
        value := valueText,
        cls   := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
      ),
    )

  private def infoCell(label: String, value: String): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-950/60 px-3 py-2")(
      p(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-500")(label),
      p(cls := "mt-1 text-sm text-slate-200")(value),
    )

  private def statusChip(label: String, tone: String): Frag =
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold ${toneClasses(tone)}")(label)

  private def toneForState(stateLabel: String): String =
    stateLabel.toLowerCase match
      case "healthy"         => "emerald"
      case "analyzing"       => "cyan"
      case "queued"          => "amber"
      case "needs attention" => "rose"
      case _                 => "slate"

  private def toneClasses(tone: String): String =
    tone match
      case "emerald" => "border-emerald-400/30 bg-emerald-500/15 text-emerald-200"
      case "cyan"    => "border-cyan-400/30 bg-cyan-500/15 text-cyan-200"
      case "amber"   => "border-amber-400/30 bg-amber-500/15 text-amber-200"
      case "rose"    => "border-rose-400/30 bg-rose-500/15 text-rose-200"
      case _         => "border-slate-400/30 bg-slate-500/15 text-slate-300"

  private def timestamp(instant: Instant): String =
    instant.toString.take(19).replace("T", " ")
