package shared.web

import activity.entity.ActivityEvent
import db.TaskRunRow
import scalatags.Text.all.*

object CommandCenterView:

  final case class PipelineSummary(
    open: Int,
    claimed: Int,
    running: Int,
    completed: Int,
    failed: Int,
    throughputPerDay: Double,
  ):
    val total: Int = open + claimed + running + completed + failed

  def page(summary: PipelineSummary, recentEvents: List[ActivityEvent]): String =
    Layout.page("Command Center", "/")(
      div(cls := "space-y-6")(
        Components.pageHeader("Command Center", "Platform activity and pipeline health"),
        pipelineStrip(summary),
        liveSection(),
        activeRunsSection(),
        recentActivitySection(recentEvents),
      ),
      JsResources.inlineModuleScript("/static/client/components/ab-run-dashboard.js"),
    )

  def recentRunsFragment(runs: List[TaskRunRow]): String =
    if runs.isEmpty then
      div(cls := "text-sm text-gray-500 py-2")("No active runs.").render
    else
      ul(cls := "divide-y divide-white/10")(
        runs.map { run =>
          li(cls := "flex items-center justify-between py-2 text-sm")(
            a(href := s"/tasks/${run.id}", cls := "font-medium text-indigo-300 hover:text-indigo-200")(s"#${run.id}"),
            span(cls := "text-gray-400")(run.status.toString),
          )
        }
      ).render

  // ── Pipeline strip ────────────────────────────────────────────────────────

  private def pipelineStrip(summary: PipelineSummary): Frag =
    val segments = List(
      ("Open", "open", summary.open, "bg-sky-400/90", "text-sky-300"),
      ("Assigned", "assigned", summary.claimed, "bg-violet-400/90", "text-violet-300"),
      ("In Progress", "in_progress", summary.running, "bg-amber-400/90", "text-amber-300"),
      ("Completed", "completed", summary.completed, "bg-emerald-400/90", "text-emerald-300"),
      ("Failed", "failed", summary.failed, "bg-rose-400/90", "text-rose-300"),
    )
    val total    = summary.total.max(1)

    div(cls := "rounded-lg border border-white/10 bg-white/5 px-4 py-3")(
      // progress bar
      div(cls := "mb-3 flex h-2 overflow-hidden rounded-full ring-1 ring-white/10")(
        segments.map { (_, statusToken, count, color, _) =>
          a(
            href  := s"/issues/board?status=$statusToken",
            cls   := s"$color transition-all",
            style := f"width: ${count.toDouble / total.toDouble * 100.0}%.2f%%;",
            title := s"View $statusToken issues",
          )()
        }
      ),
      // counts row
      div(cls := "flex items-center justify-between gap-4")(
        div(cls := "flex flex-wrap items-center gap-x-5 gap-y-1")(
          segments.map { (label, statusToken, count, _, textCls) =>
            a(
              href := s"/issues/board?status=$statusToken",
              cls  := s"flex items-center gap-1.5 text-sm hover:underline",
            )(
              span(cls := s"text-lg font-semibold tabular-nums $textCls")(count.toString),
              span(cls := "text-xs text-gray-500")(label),
            )
          }
        ),
        span(
          cls := "flex-shrink-0 rounded-md border border-emerald-400/20 bg-emerald-500/10 px-2 py-1 text-xs font-medium text-emerald-300"
        )(s"${formatThroughput(summary.throughputPerDay)} issues/day"),
      ),
    )

  private def formatThroughput(rate: Double): String = f"$rate%.1f"

  // ── Live section (left column) ────────────────────────────────────────────

  private def liveSection(): Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/5 px-4 py-3 flex items-center justify-between gap-4")(
      span(cls := "text-xs font-medium uppercase tracking-wide text-gray-400 flex-shrink-0")("Live"),
      div(
        attr("hx-ext")      := "sse",
        attr("sse-connect") := "/agent-monitor/stream",
        cls                 := "flex-1",
      )(
        div(
          id               := "agent-stats-container",
          attr("sse-swap") := "agent-stats",
          cls              := "flex justify-end",
        )(
          AgentMonitorView.statsHeaderFragment(AgentMonitorView.AgentGlobalStats.empty)
        ),
      ),
    )

  // ── Active runs section (right column) ───────────────────────────────────

  private def activeRunsSection(): Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/5 p-4 space-y-3")(
      sectionHeader("Active Runs"),
      div(
        id                 := "active-runs-list",
        attr("hx-get")     := "/runs/fragment?scope=active&sort=last_activity&limit=8",
        attr("hx-swap")    := "innerHTML",
        attr("hx-trigger") := "load, every 15s",
      )(
        div(cls := "text-xs text-gray-600 py-2")("Loading…")
      ),
    )

  // ── Recent activity section (right column) ────────────────────────────────

  private def recentActivitySection(recentEvents: List[ActivityEvent]): Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/5 p-4 space-y-4")(
      h2(cls := "text-xs font-medium uppercase tracking-wide text-gray-400")("Recent Activity"),
      div(
        attr("hx-get")     := "/api/activity/events?limit=10",
        attr("hx-swap")    := "innerHTML",
        attr("hx-trigger") := "load, every 10s",
        cls                := "max-h-[32rem] overflow-auto space-y-2",
      )(
        if recentEvents.isEmpty then
          Components.emptyStateFull("No activity yet", "Events will appear here once agents start running")
        else
          div(id := "activity-events", cls := "space-y-2")(
            recentEvents.map(ActivityView.eventCard)
          )
      ),
    )

  private def sectionHeader(title: String): Frag =
    h3(cls := "text-xs font-medium uppercase tracking-wide text-gray-400")(title)
