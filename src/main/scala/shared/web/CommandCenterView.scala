package shared.web

import db.TaskRunRow
import scalatags.Text.all.*

object CommandCenterView:

  final case class PipelineSummary(
    open: Int,
    claimed: Int,
    running: Int,
    completed: Int,
    failed: Int,
  ):
    val total: Int = open + claimed + running + completed + failed

  def page(summary: PipelineSummary): String =
    Layout.page("Command Center", "/")(
      div(cls := "space-y-4")(
        pipelineSummaryCard(summary),
        activeRunsCard(),
        liveAgentOpsCard(),
        recentActivityCard(),
      ),
      JsResources.inlineModuleScript("/static/client/components/run-dashboard.js"),
    )

  def recentRunsFragment(runs: List[TaskRunRow]): String =
    if runs.isEmpty then
      div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4 text-sm text-slate-400")(
        "No runs available."
      ).render
    else
      div(cls := "rounded-lg border border-white/10 bg-slate-950/60")(
        ul(cls := "divide-y divide-white/10")(
          runs.map { run =>
            li(cls := "flex items-center justify-between px-4 py-2 text-sm")(
              a(href := s"/tasks/${run.id}", cls := "font-medium text-indigo-300 hover:text-indigo-200")(s"#${run.id}"),
              span(cls := "text-slate-300")(run.status.toString),
            )
          }
        )
      ).render

  private def pipelineSummaryCard(summary: PipelineSummary): Frag =
    val segments = List(
      ("Open", summary.open, "bg-sky-400/90"),
      ("Claimed", summary.claimed, "bg-violet-400/90"),
      ("Running", summary.running, "bg-amber-400/90"),
      ("Completed", summary.completed, "bg-emerald-400/90"),
      ("Failed", summary.failed, "bg-rose-400/90"),
    )
    val total    = summary.total.max(1)
    panel("Pipeline Summary", "Issue flow: Open/Claimed/Running/Completed/Failed")(
      div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4")(
        div(cls := "flex h-3 overflow-hidden rounded-full ring-1 ring-white/10")(
          segments.map { (_, count, color) =>
            div(
              cls   := color,
              style := f"width: ${count.toDouble / total.toDouble * 100.0}%.2f%%;",
            )
          }
        ),
        div(cls := "mt-4 grid grid-cols-2 gap-2 sm:grid-cols-5")(
          segments.map { (label, count, color) =>
            div(cls := "rounded-md border border-white/10 bg-slate-900/70 px-2 py-2")(
              div(cls := "flex items-center gap-2")(
                span(cls := s"inline-block h-2.5 w-2.5 rounded-full $color"),
                span(cls := "text-xs text-slate-400")(label),
              ),
              div(cls := "mt-1 text-lg font-semibold text-white")(count.toString),
            )
          }
        ),
      )
    )

  private def liveAgentOpsCard(): Frag =
    panel("Live Agent Ops", "Embedded Agent Monitor stream")(
      div(
        cls                 := "space-y-2",
        attr("hx-ext")      := "sse",
        attr("sse-connect") := "/agent-monitor/stream",
      )(
        div(
          id               := "agent-stats-container",
          attr("sse-swap") := "agent-stats",
        )(
          AgentMonitorView.statsHeaderFragment(AgentMonitorView.AgentGlobalStats.empty)
        ),
        tag("details")(
          cls := "rounded-lg border border-white/10 bg-slate-950/50"
        )(
          tag("summary")(
            cls := "cursor-pointer px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-300"
          )(
            "Show Agent Table"
          ),
          div(
            cls              := "max-h-64 overflow-auto border-t border-white/10",
            id               := "agent-table-container",
            attr("sse-swap") := "agent-table",
          )(
            AgentMonitorView.tableFragment(Nil)
          ),
        ),
      )
    )

  private def activeRunsCard(): Frag =
    val fragmentUrl = "/runs/fragment?scope=active&sort=last_activity&limit=12"
    panel("Active Runs", "Embedded runs dashboard rows")(
      WorkspacesView.runsDashboardCollapsibleSection(fragmentUrl)
    )

  private def recentActivityCard(): Frag                                  =
    panel("Recent Activity", "Last 5 events")(
      div(
        attr("hx-get")     := "/api/activity/events?limit=5",
        attr("hx-swap")    := "innerHTML",
        attr("hx-trigger") := "load, every 10s",
        cls                := "max-h-[36rem] overflow-auto",
      )(
        div(
          cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4 text-sm text-slate-400"
        )("Loading activity...")
      )
    )
  private def panel(title: String, subtitle: String)(content: Frag): Frag =
    tag("section")(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
      div(cls := "mb-3")(
        h2(cls := "text-base font-semibold text-white")(title),
        p(cls := "mt-1 text-xs text-slate-400")(subtitle),
      ),
      content,
    )
