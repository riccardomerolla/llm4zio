package web.views

import db.{ MigrationRunRow, PhaseProgressRow, RunStatus }
import scalatags.Text.all.*

object RunsView:

  def list(runs: List[MigrationRunRow], pageNumber: Int, pageSize: Int): String =
    Layout.page("Migration Runs", "/runs")(
      div(cls := "sm:flex sm:items-center mb-8")(
        div(cls := "sm:flex-auto")(
          h1(cls := "text-2xl font-bold text-white")("Migration Runs"),
          p(cls := "mt-2 text-sm text-gray-400")(s"Page $pageNumber"),
        ),
        div(cls := "mt-4 sm:ml-16 sm:mt-0 sm:flex-none")(
          a(
            href := "/runs/new",
            cls  := "rounded-md bg-indigo-500 px-3 py-2 text-center text-sm font-semibold text-white shadow-sm hover:bg-indigo-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500",
          )("New Run")
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        div(cls := "overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10")(
            thead(cls := "bg-white/5")(
              tr(
                th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "ID"
                ),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Source"
                ),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Status"
                ),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Phase"
                ),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Files"
                ),
                th(cls := "relative py-3 pl-3 pr-6"),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(
              runs.map(runListRow)
            ),
          )
        )
      ),
      paginationControls(pageNumber, pageSize, runs.length),
    )

  def detail(run: MigrationRunRow, phases: List[PhaseProgressRow]): String =
    Layout.page(s"Run #${run.id}", s"/runs/${run.id}")(
      // Header
      div(cls := "mb-8")(
        div(cls := "flex items-center justify-between")(
          h1(cls := "text-2xl font-bold text-white")(s"Run #${run.id}"),
          Components.statusBadge(run.status),
        )
      ),
      // Info cards
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 mb-8")(
        infoCard("Source Directory", run.sourceDir),
        infoCard("Output Directory", run.outputDir),
        infoCard("Started", run.startedAt.toString.take(19).replace("T", " ")),
        infoCard(
          "Files",
          s"${run.processedFiles}/${run.totalFiles} (${run.successfulConversions} ok, ${run.failedConversions} failed)",
        ),
      ),
      // Phase progress with SSE
      div(
        cls                 := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-8",
        attr("hx-ext")      := "sse",
        attr("sse-connect") := s"/runs/${run.id}/progress",
      )(
        h2(cls := "text-lg font-semibold text-white mb-4")("Phase Progress"),
        div(id := "phase-progress")(
          phaseProgressSection(phases)
        ),
      ),
      // Error message
      run.errorMessage.map { err =>
        div(cls := "bg-red-500/10 border border-red-500/30 rounded-lg p-4 mb-8")(
          h3(cls := "text-sm font-semibold text-red-400 mb-2")("Error"),
          pre(cls := "text-sm text-red-300 whitespace-pre-wrap font-mono")(err),
        )
      },
      // Actions
      div(cls := "flex flex-wrap gap-3")(
        a(
          href := s"/analysis?runId=${run.id}",
          cls  := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-white/20",
        )("View Analysis"),
        a(
          href := s"/graph?runId=${run.id}",
          cls  := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-white/20",
        )("View Graph"),
        if run.status == RunStatus.Running then
          button(
            attr("hx-delete")  := s"/runs/${run.id}",
            attr("hx-confirm") := "Are you sure you want to cancel this run?",
            cls                := "rounded-md bg-red-500/10 px-3 py-2 text-sm font-semibold text-red-400 ring-1 ring-inset ring-red-500/20 hover:bg-red-500/20",
          )("Cancel Run")
        else frag(),
      ),
    )

  def form: String =
    Layout.page("New Migration Run", "/runs/new")(
      div(cls := "max-w-2xl")(
        h1(cls := "text-2xl font-bold text-white mb-6")("Start New Migration"),
        tag("form")(method := "post", action := "/runs", cls := "space-y-6")(
          div(
            label(cls := "block text-sm font-medium text-white mb-2", `for` := "sourceDir")("Source Directory"),
            input(
              `type`      := "text",
              name        := "sourceDir",
              id          := "sourceDir",
              required    := true,
              placeholder := "/path/to/cobol/source",
              cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
            ),
          ),
          div(
            label(cls := "block text-sm font-medium text-white mb-2", `for` := "outputDir")("Output Directory"),
            input(
              `type`      := "text",
              name        := "outputDir",
              id          := "outputDir",
              required    := true,
              placeholder := "/path/to/output",
              cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
            ),
          ),
          div(cls := "flex items-center gap-3")(
            input(
              `type` := "checkbox",
              name   := "dryRun",
              id     := "dryRun",
              cls    := "h-4 w-4 rounded border-white/10 bg-white/5 text-indigo-600 focus:ring-indigo-600",
            ),
            label(cls := "text-sm text-gray-400", `for` := "dryRun")(
              "Dry run (analyse only, don't write output files)"
            ),
          ),
          div(cls := "flex gap-4 pt-2")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500",
            )("Start Migration"),
            a(
              href := "/runs",
              cls  := "rounded-md px-4 py-2 text-sm font-semibold text-gray-400 hover:text-white",
            )("Cancel"),
          ),
        ),
      )
    )

  def recentRunsFragment(runs: List[MigrationRunRow]): String =
    DashboardView.recentRunsContent(runs).render

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def runListRow(run: MigrationRunRow): Frag =
    tr(cls := "hover:bg-white/5")(
      td(cls := "whitespace-nowrap py-4 pl-6 pr-3 text-sm font-medium text-white")(
        a(href := s"/runs/${run.id}", cls := "text-indigo-400 hover:text-indigo-300")(s"#${run.id}")
      ),
      td(cls := "px-3 py-4 text-sm text-gray-400 max-w-[200px] truncate")(run.sourceDir),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(Components.statusBadge(run.status)),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(run.currentPhase.getOrElse("-")),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(
        s"${run.processedFiles}/${run.totalFiles}"
      ),
      td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
        a(href := s"/runs/${run.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("View")
      ),
    )

  private def phaseProgressSection(phases: List[PhaseProgressRow]): Frag =
    if phases.isEmpty then p(cls := "text-sm text-gray-400")("No phase data available yet.")
    else
      div(cls := "space-y-4")(
        phases.map { phase =>
          div(
            div(cls := "flex items-center justify-between mb-1")(
              span(cls := "text-sm font-medium text-white capitalize")(phase.phase),
              span(cls := "text-xs text-gray-400")(
                s"${phase.itemProcessed}/${phase.itemTotal}",
                if phase.errorCount > 0 then s" (${phase.errorCount} errors)" else "",
              ),
            ),
            Components.progressBar(phase.itemProcessed, phase.itemTotal),
            span(cls := "text-xs text-gray-500 mt-0.5 inline-block")(s"Status: ${phase.status}"),
          )
        }
      )

  private def infoCard(label: String, value: String): Frag =
    div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
      p(cls := "text-xs font-medium text-gray-400 mb-1")(label),
      p(cls := "text-sm text-white break-all")(value),
    )

  private def paginationControls(pageNumber: Int, pageSize: Int, count: Int): Frag =
    div(cls := "flex items-center justify-between mt-6")(
      if pageNumber > 1 then
        a(
          href := s"/runs?page=${pageNumber - 1}&pageSize=$pageSize",
          cls  := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white hover:bg-white/20",
        )("Previous")
      else span(),
      span(cls := "text-sm text-gray-400")(s"Page $pageNumber"),
      if count >= pageSize then
        a(
          href := s"/runs?page=${pageNumber + 1}&pageSize=$pageSize",
          cls  := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white hover:bg-white/20",
        )("Next")
      else span(),
    )
