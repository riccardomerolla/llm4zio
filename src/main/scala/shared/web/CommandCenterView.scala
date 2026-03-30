package shared.web

import activity.entity.ActivityEvent
import taskrun.entity.TaskRunRow
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
        adeModuleGrid(),
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

  // ── ADE module grid ───────────────────────────────────────────────────────

  // Heroicons-compatible SVG path strings for each ADE module
  private val IconPulse        = "M3 12h4.5l2.25-6 3.5 12 2.25-6H21"
  private val IconTableColumns =
    "M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5Zm2 4h6V7H5v2Zm0 4h6v-2H5v2Zm0 4h6v-2H5v2Zm8-8h6V7h-6v2Zm0 4h6v-2h-6v2Zm0 4h6v-2h-6v2Z"
  private val IconClock        = "M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
  private val IconCheckCircle  = "M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
  private val IconDocument     =
    "M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z"
  private val IconSparkles     =
    "M9.813 15.904 9 18l-.813-2.096L6 15l2.187-.904L9 12l.813 2.096L12 15l-2.187.904ZM17.25 8.25 16.5 10.5l-2.25.75 2.25.75.75 2.25.75-2.25 2.25-.75-2.25-.75-.75-2.25ZM4.5 4.5 4 6l-1.5.5 1.5.5.5 1.5.5-1.5L6.5 6 5 5.5 4.5 4.5Z"
  private val IconCpuChip      =
    "M9 3.75H7.5A2.25 2.25 0 0 0 5.25 6v1.5m0 9V18A2.25 2.25 0 0 0 7.5 20.25H9m6-16.5h1.5A2.25 2.25 0 0 1 18.75 6v1.5m0 9V18A2.25 2.25 0 0 1 16.5 20.25H15m-6-13.5h6a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H9a1.5 1.5 0 0 1-1.5-1.5v-6A1.5 1.5 0 0 1 9 6.75Zm-3-1.5V9m0 6v2.25m12-12V9m0 6v2.25M3.75 9H6m12 0h2.25M3.75 15H6m12 0h2.25"

  private def adeModuleGrid(): Frag =
    div(cls := "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4")(
      moduleCard("/sdlc", "SDLC Dashboard", IconPulse, "text-cyan-400", "Metrics, lifecycle & delivery health"),
      moduleCard("/board", "Board", IconTableColumns, "text-blue-400", "Git-backed issue kanban"),
      moduleCard("/decisions", "Decisions", IconClock, "text-purple-400", "Human-in-the-loop review inbox"),
      moduleCard("/checkpoints", "Checkpoints", IconCheckCircle, "text-emerald-400", "Quality gates during agent runs"),
      moduleCard("/governance", "Governance", IconDocument, "text-amber-400", "Policy engine & transition rules"),
      moduleCard("/evolution", "Evolution", IconSparkles, "text-rose-400", "Structural change proposals"),
      moduleCard("/daemons", "Daemons", IconCpuChip, "text-sky-400", "Background agents & scheduled jobs"),
    )

  private def moduleCard(href: String, name: String, iconPathD: String, iconCls: String, desc: String): Frag =
    Components.interactiveCard(href)(
      Components.svgIcon(iconPathD, s"size-8 $iconCls mb-3"),
      div(cls := "text-sm font-semibold text-white")(name),
      p(cls := "mt-1 text-xs text-slate-400")(desc),
    )

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
              cls  := "flex items-center gap-1.5 text-sm hover:underline",
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
        )
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
