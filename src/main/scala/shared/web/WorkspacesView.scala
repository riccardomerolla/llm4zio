package shared.web

import scalatags.Text.all.*
import workspace.entity.{ RunMode, RunStatus, Workspace, WorkspaceRun }

object WorkspacesView:

  def page(workspaces: List[Workspace]): String =
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
          div(cls := "space-y-4")(workspaces.map(workspaceCard)*),
      )
    )

  private def workspaceCard(ws: Workspace): Frag =
    div(
      cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5",
      id  := s"ws-${ws.id}",
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
          ws.defaultAgent.map(a =>
            p(cls := "mt-1 text-xs text-slate-500")(s"Default agent: $a")
          ).getOrElse(frag()),
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
      div(id := s"runs-${ws.id}", cls := "mt-4"),
    )

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

  private def runModeLabel(runMode: RunMode): String =
    runMode match
      case RunMode.Host                     => "Run mode: Host"
      case RunMode.Docker(image, _, _, _)   => s"Run mode: \uD83D\uDC33 Docker ($image)"

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
        formField("defaultAgent", "Default agent", ws.flatMap(_.defaultAgent).getOrElse(""), required = false),
        formField("description", "Description", ws.flatMap(_.description).getOrElse(""), required = false),
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
    val isDocker = currentMode.isInstanceOf[RunMode.Docker]
    val (dockerImage, dockerNetwork, dockerMount) = currentMode match
      case RunMode.Docker(img, _, mount, net) => (img, net.getOrElse(""), mount)
      case RunMode.Host                       => ("", "", true)
    div(cls := "space-y-2")(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200")("Run mode"),
      div(cls := "flex gap-4")(
        label(cls := "flex items-center gap-1.5 text-sm text-slate-200")(
          input(
            `type` := "radio",
            name   := s"runModeType-$scopeId",
            value  := "host",
            if !isDocker then checked else (),
            attr("onchange") := s"document.getElementById('docker-fields-$scopeId').style.display='none'",
          ),
          "Host",
        ),
        label(cls := "flex items-center gap-1.5 text-sm text-slate-200")(
          input(
            `type` := "radio",
            name   := s"runModeType-$scopeId",
            value  := "docker",
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
            `type`       := "text",
            id           := s"dockerImage-$scopeId",
            name         := s"dockerImage-$scopeId",
            value        := dockerImage,
            placeholder  := "e.g. ghcr.io/opencode-ai/opencode:latest",
            cls          := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
          ),
        ),
        div(cls := "flex items-center gap-2")(
          input(
            `type` := "checkbox",
            id     := s"dockerMount-$scopeId",
            name   := s"dockerMount-$scopeId",
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
            name        := s"dockerNetwork-$scopeId",
            value       := dockerNetwork,
            placeholder := "e.g. none",
            cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
          ),
        ),
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
        ),
        assignForm(runs.headOption.map(_.workspaceId).getOrElse("")),
      ).render

  private def runRow(run: WorkspaceRun): Frag =
    tr(cls := "hover:bg-white/5")(
      td(cls := "py-2 pl-4 pr-3 text-sm font-medium text-white")(run.issueRef),
      td(cls := "px-3 py-2 text-sm text-slate-300")(run.agentName),
      td(cls := "px-3 py-2 text-sm")(statusBadge(run.status)),
      td(cls := "px-3 py-2 text-sm")(
        a(href := s"/chat/${run.conversationId}", cls := "text-indigo-400 hover:text-indigo-300 hover:underline")(
          "View Chat"
        )
      ),
    )

  private def statusBadge(status: RunStatus): Frag =
    val (label, colour) = status match
      case RunStatus.Pending   => ("Pending", "border-slate-400/30 bg-slate-500/20 text-slate-300")
      case RunStatus.Running   => ("Running", "border-blue-400/30 bg-blue-500/20 text-blue-200")
      case RunStatus.Completed => ("Completed", "border-emerald-400/30 bg-emerald-500/20 text-emerald-200")
      case RunStatus.Failed    => ("Failed", "border-rose-400/30 bg-rose-500/20 text-rose-200")
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold $colour")(label)

  private def assignForm(workspaceId: String): Frag =
    div(cls := "mt-4 border-t border-white/10 pt-4")(
      p(cls := "text-sm font-semibold text-slate-200 mb-2")("Assign new run"),
      div(cls := "flex flex-wrap gap-2")(
        input(
          `type`      := "text",
          name        := "issueRef",
          placeholder := "Issue ref (#42)",
          cls         := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 w-28",
        ),
        input(
          `type`      := "text",
          name        := "prompt",
          placeholder := "Task prompt",
          cls         := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 flex-1",
        ),
        input(
          `type`      := "text",
          name        := "agentName",
          placeholder := "Agent (gemini-cli)",
          cls         := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 w-36",
        ),
        button(
          cls                := "rounded-md bg-indigo-500 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-400",
          attr("hx-post")    := s"/api/workspaces/$workspaceId/runs",
          attr("hx-include") := "closest div",
          attr("hx-target")  := "closest div[id^='runs-']",
          attr("hx-swap")    := "innerHTML",
        )("Assign"),
      ),
    )
