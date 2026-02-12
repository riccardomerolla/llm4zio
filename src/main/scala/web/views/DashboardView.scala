package web.views

import db.{ MigrationRunRow, RunStatus }
import scalatags.Text.all.*

object DashboardView:

  def dashboard(runs: List[MigrationRunRow], workflowCount: Int): String =
    val totalRuns           = runs.length
    val completedRuns       = runs.count(_.status == RunStatus.Completed)
    val successRate         = if totalRuns > 0 then (completedRuns.toDouble / totalRuns * 100).toInt else 0
    val totalFilesProcessed = runs.map(_.processedFiles).sum
    val activeRuns          = runs.count(_.status == RunStatus.Running)

    Layout.page("Dashboard", "/")(
      h1(cls := "text-2xl font-bold text-white mb-6")("Dashboard"),
      // Summary cards
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5 mb-8")(
        Components.summaryCard(
          "Total Runs",
          totalRuns.toString,
          "M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z",
        ),
        Components.summaryCard(
          "Success Rate",
          s"$successRate%",
          "M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
        ),
        Components.summaryCard(
          "Files Processed",
          totalFilesProcessed.toString,
          "M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z",
        ),
        Components.summaryCard(
          "Active Runs",
          activeRuns.toString,
          "M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
        ),
        Components.summaryCard(
          "Workflows",
          workflowCount.toString,
          "M4.5 6h6.75m-6.75 6h6.75m-6.75 6h6.75m3.75-10.5L18 6m0 0 2.25 1.5M18 6v4.5m0 3L18 18m0 0 2.25-1.5M18 18v-4.5",
        ),
      ),
      // Recent runs with HTMX auto-refresh
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        div(cls := "flex items-center justify-between px-6 py-4 border-b border-white/10")(
          h2(cls := "text-lg font-semibold text-white")("Recent Runs"),
          div(cls := "flex items-center gap-3")(
            span(id := "refresh-indicator", cls := "htmx-indicator text-xs text-gray-500")("Updating..."),
            span(
              cls := "inline-flex items-center rounded-md bg-indigo-500/10 px-2 py-1 text-xs font-medium text-indigo-400 ring-1 ring-inset ring-indigo-500/20"
            )(
              "Auto-refresh 5s"
            ),
          ),
        ),
        div(
          id                   := "recent-runs",
          attr("data-hx-get")  := "/api/runs/recent",
          attr("hx-trigger")   := "every 5s",
          attr("data-hx-swap") := "innerHTML",
          attr("hx-indicator") := "#refresh-indicator",
        )(
          recentRunsContent(runs.take(10))
        ),
      ),
    )

  def recentRunsContent(runs: List[MigrationRunRow]): Frag =
    if runs.isEmpty then Components.emptyState("No migration runs yet. Start one from the New Run page.")
    else
      div(cls := "overflow-x-auto")(
        table(cls := "min-w-full divide-y divide-white/10")(
          thead(cls := "bg-white/5")(
            tr(
              th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("ID"),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Status"),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Started"),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Progress"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Phase"),
              th(cls := "relative py-3 pl-3 pr-6"),
            )
          ),
          tbody(cls := "divide-y divide-white/5")(
            runs.map(runRow)
          ),
        )
      )

  private def runRow(run: MigrationRunRow): Frag =
    tr(cls := "hover:bg-white/5")(
      td(cls := "whitespace-nowrap py-4 pl-6 pr-3 text-sm font-medium text-white")(
        a(href := s"/runs/${run.id}", cls := "text-indigo-400 hover:text-indigo-300")(s"#${run.id}")
      ),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(Components.statusBadge(run.status)),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(
        run.startedAt.toString.take(19).replace("T", " ")
      ),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(
        div(cls := "flex items-center gap-2 min-w-[120px]")(
          div(cls := "flex-1")(Components.progressBar(run.processedFiles, run.totalFiles)),
          span(cls := "text-xs text-gray-400 tabular-nums")(s"${run.processedFiles}/${run.totalFiles}"),
        )
      ),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(run.currentPhase.getOrElse("-")),
      td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
        a(href := s"/runs/${run.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("View")
      ),
    )
