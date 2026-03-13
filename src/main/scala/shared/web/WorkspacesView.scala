package shared.web

import java.time.Instant

import analysis.control.{ WorkspaceAnalysisState, WorkspaceAnalysisStatus }
import analysis.entity.AnalysisType
import config.entity.AgentInfo
import issues.entity.api.AgentIssueView
import scalatags.Text.all.*
import workspace.entity.{ RunMode, RunSessionMode, RunStatus, Workspace, WorkspaceRun }

object WorkspacesView:

  val supportedCliTools: List[String] = List("claude", "gemini", "opencode", "codex", "copilot")

  def page(workspaces: List[Workspace], agents: List[AgentInfo]): String =
    page(workspaces, agents, Map.empty)

  def page(
    workspaces: List[Workspace],
    agents: List[AgentInfo],
    analysisStatusByWorkspaceId: Map[String, List[WorkspaceAnalysisStatus]],
  ): String =
    Layout.page("Workspaces", "/workspaces")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Workspaces"),
              p(cls := "mt-1 text-sm text-slate-300")(
                "Register local git repositories and assign issues to CLI agents"
              ),
            ),
            button(
              cls               := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
              attr("hx-get")    := "/api/workspaces/new",
              attr("hx-target") := "#modal-container",
              attr("hx-swap")   := "innerHTML",
            )("+ New Workspace"),
          )
        ),
        div(id := "modal-container"),
        if workspaces.isEmpty then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-400")("No workspaces configured yet."),
            p(cls := "mt-1 text-sm text-slate-500")(
              "Add a workspace to start assigning issues to CLI agents."
            ),
          )
        else
          div(cls := "space-y-4")(workspaces.map(ws =>
            workspaceCard(ws, agents, analysisStatusByWorkspaceId.getOrElse(ws.id, Nil))
          )*),
      )
    )

  def detailPage(
    workspace: Workspace,
    agents: List[AgentInfo],
    analysisStatuses: List[WorkspaceAnalysisStatus],
  ): String =
    Layout.page(workspace.name, s"/settings/workspaces/${workspace.id}")(
      div(cls := "space-y-6")(
        div(cls := "flex items-center gap-3")(
          a(
            href := "/settings/workspaces",
            cls  := "inline-flex rounded-md border border-white/15 px-3 py-1.5 text-sm text-slate-300 hover:bg-white/5",
          )("Back to workspaces"),
          h1(cls := "text-2xl font-bold text-white")(workspace.name),
        ),
        workspaceCard(workspace, agents, analysisStatuses, detailMode = true),
      )
    )

  def analysisStatusFragment(workspaceId: String, statuses: List[WorkspaceAnalysisStatus]): String =
    div(
      id                 := s"analysis-status-$workspaceId",
      cls                := "rounded-xl border border-white/10 bg-slate-950/60 p-4",
      attr("hx-get")     := s"/api/workspaces/$workspaceId/analysis-status",
      attr("hx-trigger") := "load, every 5s",
      attr("hx-swap")    := "outerHTML",
    )(
      div(cls := "flex items-center justify-between gap-3")(
        div(
          h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Analysis Status"),
          p(
            cls := "mt-1 text-xs text-slate-400"
          )("Auto-triggered on workspace create/update. Manual re-analysis bypasses cooldown."),
        ),
        button(
          cls               := "rounded-md border border-cyan-400/30 bg-cyan-500/15 px-3 py-1.5 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/25",
          attr("hx-post")   := s"/api/workspaces/$workspaceId/reanalyze",
          attr("hx-target") := s"#analysis-status-$workspaceId",
          attr("hx-swap")   := "outerHTML",
        )("Re-analyze"),
      ),
      div(cls := "mt-4 grid gap-3 md:grid-cols-3")(statuses.map(renderAnalysisStatusCard)*),
    ).render

  private def workspaceCard(
    ws: Workspace,
    agents: List[AgentInfo],
    analysisStatuses: List[WorkspaceAnalysisStatus],
    detailMode: Boolean = false,
  ): Frag =
    div(
      cls                := "rounded-xl border border-white/10 bg-slate-900/60 p-5",
      id                 := s"ws-${ws.id}",
      attr("data-ws-id") := ws.id,
    )(
      div(cls := "flex items-start justify-between gap-4")(
        div(
          div(cls := "flex items-center gap-2")(
            h2(cls := "text-lg font-semibold text-white")(ws.name),
            if ws.enabled then
              span(
                cls := "rounded-full border border-emerald-400/30 bg-emerald-500/20 px-2 py-0.5 text-xs font-semibold text-emerald-200"
              )(
                "enabled"
              )
            else
              span(
                cls := "rounded-full border border-slate-400/30 bg-slate-500/20 px-2 py-0.5 text-xs font-semibold text-slate-400"
              )(
                "disabled"
              ),
          ),
          p(cls := "mt-1 text-sm text-slate-400 font-mono")(ws.localPath),
          p(cls := "mt-1 text-xs text-slate-500")(s"CLI: ${ws.cliTool}"),
          p(cls := "mt-1 text-xs text-slate-500")(runModeLabel(ws.runMode)),
          ws.description.map(d =>
            p(cls := "mt-1 text-sm text-slate-400")(d)
          ).getOrElse(frag()),
        ),
        div(cls := "flex shrink-0 gap-2")(
          button(
            cls               := "rounded-md border border-slate-400/30 bg-slate-500/10 px-2 py-1 text-xs font-semibold text-slate-200 hover:bg-slate-500/20",
            attr("hx-get")    := s"/api/workspaces/${ws.id}/runs",
            attr("hx-target") := s"#runs-${ws.id}",
            attr("hx-swap")   := "innerHTML",
          )("Runs"),
          if !detailMode then
            a(
              href := s"/settings/workspaces/${ws.id}",
              cls  := "rounded-md border border-indigo-400/30 bg-indigo-500/15 px-2 py-1 text-xs font-semibold text-indigo-200 hover:bg-indigo-500/25",
            )("Details")
          else frag(),
          button(
            cls               := "rounded-md border border-cyan-400/30 bg-cyan-500/20 px-2 py-1 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/30",
            attr("hx-get")    := s"/api/workspaces/${ws.id}/edit",
            attr("hx-target") := "#modal-container",
            attr("hx-swap")   := "innerHTML",
          )("Edit"),
          button(
            cls                := "rounded-md border border-rose-400/30 bg-rose-500/10 px-2 py-1 text-xs font-semibold text-rose-200 hover:bg-rose-500/20",
            attr("hx-delete")  := s"/api/workspaces/${ws.id}",
            attr("hx-target")  := s"#ws-${ws.id}",
            attr("hx-swap")    := "outerHTML",
            attr("hx-confirm") := s"Delete workspace '${ws.name}'?",
          )("Delete"),
        ),
      ),
      if detailMode then raw(analysisStatusFragment(ws.id, normalizedStatuses(ws.id, analysisStatuses))) else frag(),
      div(cls := "mt-4 border-t border-white/10 pt-4")(
        assignForm(ws.id, ws.defaultAgent, agents)
      ),
      div(id := s"runs-${ws.id}", cls := "mt-3"),
    )

  private def normalizedStatuses(
    workspaceId: String,
    statuses: List[WorkspaceAnalysisStatus],
  ): List[WorkspaceAnalysisStatus] =
    val byType = statuses.map(status => status.analysisType -> status).toMap
    List(AnalysisType.CodeReview, AnalysisType.Architecture, AnalysisType.Security).map { analysisType =>
      byType.getOrElse(
        analysisType,
        WorkspaceAnalysisStatus(
          workspaceId = workspaceId,
          analysisType = analysisType,
          state = WorkspaceAnalysisState.Idle,
          lastUpdatedAt = Instant.EPOCH,
        ),
      )
    }

  private def renderAnalysisStatusCard(status: WorkspaceAnalysisStatus): Frag =
    val (label, classes) = status.state match
      case WorkspaceAnalysisState.Pending   => ("Pending", "border-amber-400/30 bg-amber-500/10 text-amber-200")
      case WorkspaceAnalysisState.Running   => ("Running", "border-cyan-400/30 bg-cyan-500/10 text-cyan-200")
      case WorkspaceAnalysisState.Completed => ("Completed", "border-emerald-400/30 bg-emerald-500/10 text-emerald-200")
      case WorkspaceAnalysisState.Failed    => ("Failed", "border-rose-400/30 bg-rose-500/10 text-rose-200")
      case WorkspaceAnalysisState.Idle      => ("Idle", "border-slate-400/30 bg-slate-500/10 text-slate-300")
    div(cls := "rounded-lg border border-white/10 bg-slate-900/70 p-4")(
      div(cls := "flex items-center justify-between gap-2")(
        div(cls := "text-sm font-semibold text-white")(analysisTypeLabel(status.analysisType)),
        span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold $classes")(label),
      ),
      status.startedAt
        .map(ts => p(cls := "mt-2 text-xs text-slate-400")(s"Started ${formatTimestamp(ts)}"))
        .getOrElse(p(cls := "mt-2 text-xs text-slate-500")("Not started yet")),
      status.completedAt
        .map(ts => p(cls := "mt-1 text-xs text-slate-400")(s"Completed ${formatTimestamp(ts)}"))
        .getOrElse(frag()),
    )

  private def analysisTypeLabel(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "Code Review"
      case AnalysisType.Architecture => "Architecture"
      case AnalysisType.Security     => "Security"
      case AnalysisType.Custom(name) => name

  private def formatTimestamp(instant: Instant): String =
    instant.toString.take(19).replace("T", " ")

  def newWorkspaceForm: String =
    modalForm(
      title = "New Workspace",
      formId = "ws-new-form",
      submitUrl = "/api/workspaces",
      method = "post",
      ws = None,
    )

  def editWorkspaceForm(ws: Workspace): String =
    modalForm(
      title = s"Edit — ${ws.name}",
      formId = s"ws-edit-form-${ws.id}",
      submitUrl = s"/api/workspaces/${ws.id}",
      method = "put",
      ws = Some(ws),
    )

  private def cliToolLabel(tool: String): String = tool match
    case "claude"   => "Claude (claude --print)"
    case "gemini"   => "Gemini CLI (gemini -p)"
    case "opencode" => "OpenCode (opencode run)"
    case "codex"    => "Codex (codex)"
    case "copilot"  => "GitHub Copilot (gh copilot)"
    case other      => other

  private def runModeLabel(runMode: RunMode): String =
    runMode match
      case RunMode.Host                   => "Run mode: Host"
      case RunMode.Docker(image, _, _, _) => s"Run mode: \uD83D\uDC33 Docker ($image)"

  private def modalForm(
    title: String,
    formId: String,
    submitUrl: String,
    method: String,
    ws: Option[Workspace],
  ): String =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/90 p-6 shadow-xl")(
      div(cls := "mb-4 flex items-center justify-between")(
        h2(cls := "text-lg font-semibold text-white")(title),
        button(
          `type`              := "button",
          cls                 := "text-slate-400 hover:text-white text-xl leading-none",
          attr("hx-get")      := "/settings/workspaces",
          attr("hx-target")   := "body",
          attr("hx-swap")     := "outerHTML",
          attr("hx-push-url") := "true",
        )("×"),
      ),
      tag("form")(
        id                   := formId,
        attr("hx-" + method) := submitUrl,
        attr("hx-target")    := "body",
        attr("hx-swap")      := "outerHTML",
        attr("hx-push-url")  := "/settings/workspaces",
        cls                  := "space-y-4",
      )(
        formField("name", "Name", ws.map(_.name).getOrElse(""), required = true),
        formField("localPath", "Local path", ws.flatMap(v => Some(v.localPath)).getOrElse(""), required = true),
        formField("description", "Description", ws.flatMap(_.description).getOrElse(""), required = false),
        cliToolSelectField(ws.map(_.cliTool).getOrElse("claude")),
        runModeField(ws.map(_.runMode).getOrElse(RunMode.Host), formId),
        div(cls := "flex gap-3 pt-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )("Save"),
          a(
            href := "/settings/workspaces",
            cls  := "rounded-md border border-white/20 px-4 py-2 text-sm font-semibold text-slate-300 hover:text-white",
          )("Cancel"),
        ),
      ),
    ).render

  private def runModeField(currentMode: RunMode, scopeId: String): Frag =
    val isDocker                                  = currentMode.isInstanceOf[RunMode.Docker]
    val (dockerImage, dockerNetwork, dockerMount) = currentMode match
      case RunMode.Docker(img, _, mount, net) => (img, net.getOrElse(""), mount)
      case RunMode.Host                       => ("", "", true)
    div(cls := "space-y-2")(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200")("Run mode"),
      div(cls := "flex gap-4")(
        label(cls := "flex items-center gap-1.5 text-sm text-slate-200")(
          input(
            `type`           := "radio",
            name             := "runModeType",
            value            := "host",
            if !isDocker then checked else (),
            attr("onchange") := s"document.getElementById('docker-fields-$scopeId').style.display='none'",
          ),
          "Host",
        ),
        label(cls := "flex items-center gap-1.5 text-sm text-slate-200")(
          input(
            `type`           := "radio",
            name             := "runModeType",
            value            := "docker",
            if isDocker then checked else (),
            attr("onchange") := s"document.getElementById('docker-fields-$scopeId').style.display='block'",
          ),
          "Docker",
        ),
      ),
      div(
        id    := s"docker-fields-$scopeId",
        style := s"display:${if isDocker then "block" else "none"}",
        cls   := "space-y-2 pl-2 border-l border-white/10",
      )(
        div(
          label(cls := "mb-1 block text-xs font-semibold text-slate-300", `for` := s"dockerImage-$scopeId")(
            "Docker image"
          ),
          input(
            `type`      := "text",
            id          := s"dockerImage-$scopeId",
            name        := "dockerImage",
            value       := dockerImage,
            placeholder := "e.g. ghcr.io/opencode-ai/opencode:latest",
            cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
          ),
        ),
        div(cls := "flex items-center gap-2")(
          input(
            `type` := "checkbox",
            id     := s"dockerMount-$scopeId",
            name   := "dockerMount",
            value  := "on",
            if dockerMount then checked else (),
          ),
          label(cls := "text-xs text-slate-300", `for` := s"dockerMount-$scopeId")(
            "Mount worktree at /workspace"
          ),
        ),
        div(
          label(cls := "mb-1 block text-xs font-semibold text-slate-300", `for` := s"dockerNetwork-$scopeId")(
            "Network (optional)"
          ),
          input(
            `type`      := "text",
            id          := s"dockerNetwork-$scopeId",
            name        := "dockerNetwork",
            value       := dockerNetwork,
            placeholder := "e.g. none",
            cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
          ),
        ),
      ),
    )

  private def cliToolSelectField(current: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := "cliTool")("CLI Tool"),
      tag("select")(
        id   := "cliTool",
        name := "cliTool",
        cls  := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none",
      )(
        supportedCliTools.map { tool =>
          if tool == current then option(value := tool, selected)(cliToolLabel(tool))
          else option(value := tool)(cliToolLabel(tool))
        }*
      ),
    )

  private def formField(fieldName: String, labelText: String, fieldValue: String, required: Boolean): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText),
      input(
        `type` := "text",
        id     := fieldName,
        name   := fieldName,
        value  := fieldValue,
        cls    := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
        if required then scalatags.Text.all.required else (),
      ),
    )

  def assignErrorFragment(message: String): String =
    div(cls := "mt-2 rounded-md border border-rose-400/30 bg-rose-500/10 px-3 py-2 text-sm text-rose-200")(
      span(cls := "font-semibold")("Run failed: "),
      message,
    ).render

  def runsFragment(runs: List[WorkspaceRun]): String =
    if runs.isEmpty then
      div(cls := "text-sm text-slate-500 py-2 text-center")("No runs yet.").render
    else
      div(
        div(cls := "overflow-hidden rounded-lg border border-white/10")(
          table(cls := "min-w-full divide-y divide-white/10")(
            thead(cls := "bg-white/5")(
              tr(
                th(cls := "py-2 pl-4 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Issue"
                ),
                th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Chain"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Agent"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Status"
                ),
                th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Actions"
                ),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(runs.map(runRow)*),
          )
        )
      ).render

  /** Standalone HTML fragment for a single run row — used by the HTMX poll endpoint. */
  def runRowFragment(run: WorkspaceRun): String =
    runRow(run).render

  private def runRow(run: WorkspaceRun): Frag =
    val isActive  = run.status match
      case RunStatus.Pending | RunStatus.Running(_) => true
      case _                                        => false
    val pollUrl   = s"/api/workspaces/${run.workspaceId}/runs/${run.id}/row"
    val baseAttrs = Seq(
      cls := "hover:bg-white/5",
      id  := s"run-row-${run.id}",
    )
    val pollAttrs = if isActive then
      Seq(
        attr("hx-get")     := pollUrl,
        attr("hx-trigger") := "every 3s",
        attr("hx-target")  := s"#run-row-${run.id}",
        attr("hx-swap")    := "outerHTML",
      )
    else Seq.empty
    tr((baseAttrs ++ pollAttrs)*)(
      td(cls := "py-2 pl-4 pr-3 text-sm font-medium text-white")(run.issueRef),
      td(cls := "px-3 py-2 text-xs text-slate-400 font-mono")(
        run.parentRunId.map(parent => s"$parent -> ${run.id}").getOrElse(run.id)
      ),
      td(cls := "px-3 py-2 text-sm text-slate-300")(run.agentName),
      td(cls := "px-3 py-2 text-sm")(
        if isActive then
          div(cls := "flex items-center gap-1.5")(
            div(cls := "h-1.5 w-1.5 rounded-full bg-blue-400 animate-pulse"),
            statusBadge(run.status),
          )
        else statusBadge(run.status)
      ),
      td(cls := "px-3 py-2 text-sm")(
        a(href := s"/chat/${run.conversationId}", cls := "text-indigo-400 hover:text-indigo-300 hover:underline mr-3")(
          "View Chat"
        ),
        if run.status match
            case RunStatus.Running(_) => true
            case _                    => false
        then
          button(
            cls                := "rounded px-2 py-0.5 text-xs font-semibold border border-rose-400/40 bg-rose-500/20 text-rose-300 hover:bg-rose-500/30",
            attr("hx-delete")  := s"/api/workspaces/${run.workspaceId}/runs/${run.id}",
            attr("hx-confirm") := "Stop this run?",
            attr("hx-target")  := s"#run-row-${run.id}",
            attr("hx-swap")    := "outerHTML",
          )("Stop")
        else if run.status == RunStatus.Completed || run.status == RunStatus.Failed then
          button(
            cls               := "rounded px-2 py-0.5 text-xs font-semibold border border-emerald-400/40 bg-emerald-500/20 text-emerald-200 hover:bg-emerald-500/30",
            attr("hx-post")   := s"/api/workspaces/${run.workspaceId}/runs/${run.id}/continue",
            attr("hx-target") := s"#runs-${run.workspaceId}",
            attr("hx-swap")   := "innerHTML",
            attr(
              "hx-vals"
            )                 := s"""js:{prompt: (window.prompt("Continue instructions for run ${run.id}") || "").trim()}""",
          )("Continue")
        else frag(),
      ),
    )

  private def statusBadge(status: RunStatus): Frag =
    val (label, colour) = status match
      case RunStatus.Pending                             => ("Pending", "border-slate-400/30 bg-slate-500/20 text-slate-300")
      case RunStatus.Running(RunSessionMode.Autonomous)  =>
        ("Running (Autonomous)", "border-blue-400/30 bg-blue-500/20 text-blue-200")
      case RunStatus.Running(RunSessionMode.Interactive) =>
        ("Running (Interactive)", "border-cyan-400/30 bg-cyan-500/20 text-cyan-200")
      case RunStatus.Running(RunSessionMode.Paused)      =>
        ("Paused", "border-amber-400/30 bg-amber-500/20 text-amber-200")
      case RunStatus.Completed                           => ("Completed", "border-emerald-400/30 bg-emerald-500/20 text-emerald-200")
      case RunStatus.Failed                              => ("Failed", "border-rose-400/30 bg-rose-500/20 text-rose-200")
      case RunStatus.Cancelled                           => ("Cancelled", "border-orange-400/30 bg-orange-500/20 text-orange-200")
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold $colour")(label)

  private def assignForm(workspaceId: String, defaultAgent: Option[String], agents: List[AgentInfo]): Frag =
    val searchId      = s"issue-search-$workspaceId"
    val resultsId     = s"issue-results-$workspaceId"
    val refId         = s"issue-ref-$workspaceId"
    val promptId      = s"issue-prompt-$workspaceId"
    val agentSelectId = s"agent-select-$workspaceId"
    div(cls := "space-y-3")(
      p(cls := "text-sm font-semibold text-slate-200")("Assign run to agent"),
      // Issue search row
      div(cls := "relative")(
        input(
          id                 := searchId,
          `type`             := "text",
          placeholder        := "Search open issues…",
          cls                := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500",
          attr("hx-get")     := "/api/workspaces/issues/search",
          attr("hx-trigger") := "input changed delay:300ms",
          attr("hx-target")  := s"#$resultsId",
          attr("hx-swap")    := "innerHTML",
          attr("hx-vals")    := s"""js:{q: document.getElementById('$searchId').value}""",
        ),
        div(
          id                 := resultsId,
          cls                := "absolute z-10 mt-1 w-full rounded-md border border-white/10 bg-slate-800 shadow-lg empty:hidden",
        ),
      ),
      // Hidden fields populated on issue selection
      input(`type` := "hidden", id := refId, name    := "issueRef", value := ""),
      input(`type` := "hidden", id := promptId, name := "prompt", value   := ""),
      // Agent select + submit row
      div(cls := "flex flex-wrap gap-2 items-center")(
        span(
          id  := s"selected-label-$workspaceId",
          cls := "text-xs text-slate-400 italic flex-1",
        )("No issue selected"),
        tag("select")(
          id   := agentSelectId,
          name := "agentName",
          cls  := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none",
        )(
          agents.map { a =>
            if defaultAgent.contains(a.name) then option(value := a.name, selected)(a.displayName)
            else option(value := a.name)(a.displayName)
          }*
        ),
        button(
          cls                := "rounded-md bg-indigo-500 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-400 disabled:opacity-40",
          attr("hx-post")    := s"/api/workspaces/$workspaceId/runs",
          attr("hx-include") := s"#$refId, #$promptId, #$agentSelectId",
          attr("hx-target")  := s"#runs-$workspaceId",
          attr("hx-swap")    := "innerHTML",
        )("Run"),
      ),
    )

  /** HTMX fragment: list of selectable issue rows for the assign-run search dropdown. */
  def issueSearchResults(issues: List[AgentIssueView]): String =
    if issues.isEmpty then
      div(cls := "px-3 py-2 text-sm text-slate-400")("No open issues found").render
    else
      div(
        issues.map { issue =>
          val issueId    = issue.id.getOrElse("")
          val issueTitle = issue.title
          val issueDesc  = issue.description.take(80) + (if issue.description.length > 80 then "…" else "")
          // On click: populate the hidden fields and update the label, then close the dropdown
          val onclick    =
            s"""(function(el){
               |  var card = el.closest('[data-ws-id]');
               |  var wsId = card ? card.dataset.wsId : '';
               |  document.getElementById('issue-ref-'+wsId).value='${escapeJs(issueId)}';
               |  document.getElementById('issue-prompt-'+wsId).value='${escapeJs(issueTitle)}';
               |  document.getElementById('selected-label-'+wsId).textContent='#${escapeJs(issueId)}: ${escapeJs(
                issueTitle
              )}';
               |  document.getElementById('issue-results-'+wsId).innerHTML='';
               |  document.getElementById('issue-search-'+wsId).value='';
               |})(this)""".stripMargin.replaceAll("\n", " ")
          button(
            `type`          := "button",
            cls             := "w-full text-left px-3 py-2 hover:bg-slate-700 border-b border-white/5 last:border-0",
            attr("onclick") := onclick,
          )(
            div(cls := "text-sm text-slate-100")(s"#$issueId  $issueTitle"),
            div(cls := "text-xs text-slate-400 mt-0.5")(issueDesc),
          )
        }*
      ).render

  private def escapeJs(s: String): String =
    s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

  def runsDashboardPage(
    runs: List[WorkspaceRun],
    workspaceNameById: Map[String, String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    statusFilter: Option[String],
    scopeFilter: String,
    sortBy: String,
    dateFrom: Option[String],
    dateTo: Option[String],
    limit: Int,
  ): String =
    val qs          = List(
      workspaceFilter.filter(_.nonEmpty).map(v => s"workspace=$v"),
      agentFilter.filter(_.nonEmpty).map(v => s"agent=$v"),
      statusFilter.filter(_.nonEmpty).map(v => s"status=$v"),
      Option(scopeFilter).filter(_.nonEmpty).map(v => s"scope=$v"),
      Option(sortBy).filter(_.nonEmpty).map(v => s"sort=$v"),
      dateFrom.filter(_.nonEmpty).map(v => s"from=$v"),
      dateTo.filter(_.nonEmpty).map(v => s"to=$v"),
      Some(s"limit=$limit"),
    ).flatten.mkString("&")
    val fragmentUrl = s"/runs/fragment?$qs"
    Layout.page("Runs Dashboard", "/runs")(
      div(cls := "space-y-4")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Run Status Dashboard"),
              p(cls := "mt-1 text-sm text-slate-300")("Unified view of runs across all workspaces"),
            ),
            div(cls := "text-xs text-slate-400")(s"${runs.size} runs"),
          )
        ),
        runsDashboardFilterBar(
          workspaceNameById,
          workspaceFilter,
          agentFilter,
          statusFilter,
          scopeFilter,
          sortBy,
          dateFrom,
          dateTo,
          limit,
        ),
        runsDashboardRoot(
          fragmentUrl = fragmentUrl,
          initialContent = raw(runsDashboardRowsFragment(runs, workspaceNameById)),
        ),
      ),
      JsResources.inlineModuleScript("/static/client/components/run-dashboard.js"),
    )

  def runsDashboardRoot(fragmentUrl: String, initialContent: Frag): Frag =
    div(
      id                        := "runs-dashboard-root",
      attr("data-fragment-url") := fragmentUrl,
      attr("hx-get")            := fragmentUrl,
      attr("hx-trigger")        := "load, every 10s",
      attr("hx-swap")           := "innerHTML",
    )(
      initialContent
    )

  def runsDashboardCollapsibleSection(fragmentUrl: String): Frag =
    tag("details")(
      cls := "rounded-lg border border-white/10 bg-slate-950/50"
    )(
      tag("summary")(
        cls := "cursor-pointer px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-300"
      )(
        "Show Runs Table"
      ),
      div(cls := "border-t border-white/10 p-3")(
        runsDashboardRoot(
          fragmentUrl = fragmentUrl,
          initialContent =
            div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4 text-sm text-slate-400")(
              "Loading runs..."
            ),
        )
      ),
    )

  def runsDashboardRowsFragment(runs: List[WorkspaceRun], workspaceNameById: Map[String, String]): String =
    if runs.isEmpty then
      div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-10 text-center text-slate-400")("No runs found.")
        .render
    else
      div(cls := "overflow-hidden rounded-xl border border-white/10 bg-slate-900/60")(
        table(cls := "min-w-full divide-y divide-white/10")(
          thead(cls := "bg-white/5")(
            tr(
              th(
                cls := "py-2 pl-4 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400"
              )("Workspace"),
              th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Issue"),
              th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Agent"),
              th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Status"),
              th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Duration"),
              th(
                cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400"
              )("Last Activity"),
              th(cls := "px-3 py-2 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Actions"),
            )
          ),
          tbody(cls := "divide-y divide-white/5")(runs.map(run => runsDashboardRow(run, workspaceNameById))*),
        )
      ).render

  private def runsDashboardRow(run: WorkspaceRun, workspaceNameById: Map[String, String]): Frag =
    val workspaceName = workspaceNameById.getOrElse(run.workspaceId, run.workspaceId)
    val running       = run.status match
      case RunStatus.Pending | RunStatus.Running(_) => true
      case _                                        => false
    val lastActivity  = run.updatedAt.toString.take(19).replace("T", " ")
    tr(
      id  := s"dashboard-run-${run.id}",
      cls := "hover:bg-white/5",
    )(
      td(cls := "py-2 pl-4 pr-3 text-sm text-slate-200")(workspaceName),
      td(cls := "px-3 py-2 text-sm text-white")(run.issueRef),
      td(cls := "px-3 py-2 text-sm text-slate-300")(run.agentName),
      td(cls := "px-3 py-2 text-sm")(statusBadge(run.status)),
      td(
        cls                           := "px-3 py-2 text-sm text-slate-300 font-mono",
        attr("data-role")             := "run-duration",
        attr("data-started-at")       := run.createdAt.toString,
        attr("data-finished-at")      := run.updatedAt.toString,
        attr("data-is-running")       := running.toString,
        attr("data-initial-duration") := formatDuration(
          run.createdAt,
          if running then java.time.Clock.systemUTC().instant() else run.updatedAt,
        ),
      )(
        formatDuration(run.createdAt, if running then java.time.Clock.systemUTC().instant() else run.updatedAt)
      ),
      td(cls := "px-3 py-2 text-sm text-slate-300")(lastActivity),
      td(cls := "px-3 py-2 text-sm")(
        a(
          href  := s"/chat/${run.conversationId}?attach=1&runId=${run.id}",
          cls   := "mr-2 inline-flex rounded border border-cyan-400/40 px-2 py-0.5 text-xs text-cyan-200 hover:bg-cyan-500/20",
          title := "Attach",
        )("↗"),
        button(
          cls                := "mr-2 inline-flex rounded border border-rose-400/40 px-2 py-0.5 text-xs text-rose-200 hover:bg-rose-500/20",
          attr("hx-delete")  := s"/api/workspaces/${run.workspaceId}/runs/${run.id}",
          attr("hx-confirm") := "Cancel this run?",
          attr("hx-target")  := "#runs-dashboard-root",
          attr("hx-swap")    := "innerHTML",
          title              := "Cancel",
        )("✕"),
        a(
          href  := s"/chat/${run.conversationId}",
          cls   := "mr-2 inline-flex rounded border border-indigo-400/40 px-2 py-0.5 text-xs text-indigo-200 hover:bg-indigo-500/20",
          title := "View Conversation",
        )("💬"),
        a(
          href  := s"/chat/${run.conversationId}#git-panel-${run.conversationId}",
          cls   := "inline-flex rounded border border-emerald-400/40 px-2 py-0.5 text-xs text-emerald-200 hover:bg-emerald-500/20",
          title := "View Changes",
        )("Δ"),
      ),
    )

  private def runsDashboardFilterBar(
    workspaceNameById: Map[String, String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    statusFilter: Option[String],
    scopeFilter: String,
    sortBy: String,
    dateFrom: Option[String],
    dateTo: Option[String],
    limit: Int,
  ): Frag =
    form(method := "get", action := "/runs", cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      div(cls := "grid grid-cols-1 gap-3 md:grid-cols-9")(
        select(
          name := "workspace",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          option(value := "")("Any workspace"),
          workspaceNameById.toList.sortBy(_._2.toLowerCase).map { (id, name) =>
            option(value := id, if workspaceFilter.contains(id) then selected := "selected" else ())(name)
          },
        ),
        input(
          `type`      := "text",
          name        := "agent",
          value       := agentFilter.getOrElse(""),
          placeholder := "Agent",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        ),
        select(
          name := "status",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          option(value := "")("Any status"),
          option(
            value := "pending",
            if statusFilter.contains("pending") then selected := "selected" else (),
          )("Pending"),
          option(
            value := "running",
            if statusFilter.contains("running") then selected := "selected" else (),
          )("Running"),
          option(
            value := "completed",
            if statusFilter.contains("completed") then selected := "selected" else (),
          )("Completed"),
          option(value := "failed", if statusFilter.contains("failed") then selected := "selected" else ())("Failed"),
          option(
            value := "cancelled",
            if statusFilter.contains("cancelled") then selected := "selected" else (),
          )("Cancelled"),
        ),
        select(
          name := "scope",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          option(value := "active", if scopeFilter == "active" then selected := "selected" else ())("Active"),
          option(value := "recent", if scopeFilter == "recent" then selected := "selected" else ())("Recent"),
          option(value := "all", if scopeFilter == "all" then selected := "selected" else ())("All"),
        ),
        select(
          name := "sort",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          option(value := "created", if sortBy == "created" then selected := "selected" else ())("Created"),
          option(
            value := "last_activity",
            if sortBy == "last_activity" then selected := "selected" else (),
          )("Last activity"),
          option(value := "duration", if sortBy == "duration" then selected := "selected" else ())("Duration"),
        ),
        input(
          `type`      := "date",
          name        := "from",
          value       := dateFrom.getOrElse(""),
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        ),
        input(
          `type`      := "date",
          name        := "to",
          value       := dateTo.getOrElse(""),
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        ),
        input(
          `type`      := "number",
          min         := "1",
          max         := "500",
          name        := "limit",
          value       := limit.toString,
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        ),
        div(cls := "flex gap-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )("Apply"),
          a(
            href := "/runs",
            cls  := "rounded-md border border-white/20 px-3 py-2 text-sm text-slate-200 hover:bg-white/5",
          )("Reset"),
        ),
      )
    )

  private def formatDuration(from: java.time.Instant, to: java.time.Instant): String =
    val totalSec = math.max(0L, java.time.Duration.between(from, to).getSeconds)
    val h        = totalSec / 3600
    val m        = (totalSec % 3600) / 60
    val s        = totalSec  % 60
    if h > 0 then f"$h%02d:$m%02d:$s%02d" else f"$m%02d:$s%02d"
