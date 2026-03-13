package shared.web

import project.entity.{ Project, ProjectSettings }
import scalatags.Text.all.*

object ProjectsView:

  def page(projects: List[Project]): String =
    Layout.page("Projects", "/projects")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Projects"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Group related workspaces under a shared project with reusable defaults."
          ),
        ),
        if projects.isEmpty then emptyState
        else
          div(cls := "grid gap-4 lg:grid-cols-2")(projects.map(projectCard)*),
      )
    )

  private def emptyState: Frag =
    div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
      p(cls := "text-slate-300")("No projects configured yet."),
      p(cls := "mt-1 text-sm text-slate-500")(
        "Project aggregates are available in the domain model and ready for a create/edit flow."
      ),
    )

  private def projectCard(project: Project): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "flex items-start justify-between gap-4")(
        div(
          h2(cls := "text-lg font-semibold text-white")(project.name),
          project.description.fold[Frag](frag())(desc => p(cls := "mt-1 text-sm text-slate-400")(desc)),
        ),
        span(
          cls := "rounded-full border border-cyan-400/30 bg-cyan-500/15 px-2 py-0.5 text-xs font-semibold text-cyan-200"
        )(
          s"${project.workspaceIds.size} workspace${if project.workspaceIds.size == 1 then "" else "s"}"
        ),
      ),
      div(cls := "mt-4 grid gap-3 text-xs text-slate-400 sm:grid-cols-2")(
        metaRow("Default agent", project.settings.defaultAgent.getOrElse("—")),
        metaRow("CI required", yesNo(project.settings.mergePolicy.requireCi)),
        metaRow("CI command", project.settings.mergePolicy.ciCommand.getOrElse("—")),
        metaRow("Analysis cadence", formatSchedule(project.settings)),
      ),
      if project.workspaceIds.nonEmpty then
        div(cls := "mt-4")(
          p(cls := "text-xs font-semibold uppercase tracking-wide text-slate-500")("Workspace IDs"),
          div(cls := "mt-2 flex flex-wrap gap-2")(
            project.workspaceIds.map(workspaceId =>
              span(
                cls := "rounded-md border border-white/10 bg-black/20 px-2 py-1 font-mono text-[11px] text-slate-300"
              )(workspaceId)
            )
          ),
        )
      else frag(),
      p(cls := "mt-4 text-[11px] text-slate-500")(
        s"Created ${timestamp(project.createdAt)} · Updated ${timestamp(project.updatedAt)}"
      ),
    )

  private def metaRow(label: String, value: String): Frag =
    div(
      p(cls := "font-semibold uppercase tracking-wide text-slate-500")(label),
      p(cls := "mt-1 text-sm text-slate-200")(value),
    )

  private def formatSchedule(settings: ProjectSettings): String =
    settings.analysisSchedule.fold("—")(_.toString)

  private def yesNo(value: Boolean): String =
    if value then "Yes" else "No"

  private def timestamp(instant: java.time.Instant): String =
    instant.toString.take(19).replace("T", " ")
