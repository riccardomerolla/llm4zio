package web.views

import zio.json.*

import db.{ CobolAnalysisRow, CobolFileRow }
import models.{ CobolAnalysis, CobolDivisions, ComplexityMetrics, Procedure, Variable }
import scalatags.Text.all.*

object AnalysisView:

  def list(runId: Long, files: List[CobolFileRow], analyses: List[CobolAnalysisRow]): String =
    val analysisByFile = analyses.groupBy(_.fileId)
    Layout.page(s"Analysis — Run #$runId", "/analysis")(
      h1(cls := "text-2xl font-bold text-white mb-6")(s"Analysis for Run #$runId"),
      // Search bar with HTMX
      div(cls := "mb-6")(
        div(cls := "relative")(
          input(
            `type`               := "search",
            name                 := "q",
            placeholder          := "Search files...",
            cls                  := "block w-full rounded-md bg-white/5 border-0 py-2 pl-10 pr-3 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6",
            attr("hx-get")       := s"/api/analysis/search?runId=$runId",
            attr("hx-trigger")   := "keyup changed delay:300ms",
            attr("hx-target")    := "#file-list",
            attr("hx-swap")      := "innerHTML",
            attr("hx-indicator") := "#search-indicator",
          ),
          div(cls := "pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3")(
            Components.svgIcon(
              "m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z",
              "size-5 text-gray-400",
            )
          ),
        ),
        span(id := "search-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-1 inline-block")(
          "Searching..."
        ),
      ),
      // File list
      div(id := "file-list")(
        fileListContent(files, analysisByFile)
      ),
    )

  def detail(file: CobolFileRow, analysis: CobolAnalysisRow): String =
    val parsed = analysis.analysisJson.fromJson[CobolAnalysis].toOption
    Layout.page(s"Analysis — ${file.name}", s"/analysis/${file.id}")(
      // Header
      div(cls := "mb-8")(
        div(cls := "flex items-center gap-3 mb-2")(
          h1(cls := "text-2xl font-bold text-white")(file.name),
          Components.fileTypeBadge(file.fileType),
        ),
        p(cls := "text-sm text-gray-400")(
          s"${file.path}  •  ${file.lineCount} lines  •  ${file.size} bytes  •  ${file.encoding}"
        ),
      ),
      parsed match
        case Some(ca) =>
          frag(
            divisionsSection(ca.divisions),
            variablesSection(ca.variables),
            proceduresSection(ca.procedures),
            copybooksSection(ca.copybooks),
            complexitySection(ca.complexity),
            rawJsonSection(analysis.analysisJson),
          )
        case None     =>
          div(cls := "bg-red-500/10 border border-red-500/30 rounded-lg p-4")(
            p(cls := "text-red-300 mb-2")("Failed to parse analysis JSON."),
            pre(cls := "text-xs text-gray-400 overflow-x-auto whitespace-pre-wrap font-mono")(
              analysis.analysisJson
            ),
          ),
    )

  def searchFragment(files: List[CobolFileRow]): String =
    if files.isEmpty then
      div(cls := "text-center py-8 text-gray-400")("No files match your search.").render
    else
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        table(cls := "min-w-full divide-y divide-white/10")(
          tbody(cls := "divide-y divide-white/5")(
            files.map(fileRow(_, Map.empty))
          )
        )
      ).render

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def fileListContent(files: List[CobolFileRow], analysisByFile: Map[Long, List[CobolAnalysisRow]]): Frag =
    if files.isEmpty then Components.emptyState("No files found for this run.")
    else
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        table(cls := "min-w-full divide-y divide-white/10")(
          thead(cls := "bg-white/5")(
            tr(
              th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "File"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Type"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Lines"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Analysis"
              ),
              th(cls := "relative py-3 pl-3 pr-6"),
            )
          ),
          tbody(cls := "divide-y divide-white/5")(
            files.map(fileRow(_, analysisByFile))
          ),
        )
      )

  private def fileRow(file: CobolFileRow, analysisByFile: Map[Long, List[CobolAnalysisRow]]): Frag =
    val count = analysisByFile.getOrElse(file.id, Nil).size
    tr(cls := "hover:bg-white/5")(
      td(cls := "py-4 pl-6 pr-3 text-sm")(
        div(cls := "font-medium text-white")(file.name),
        div(cls := "text-xs text-gray-500 truncate max-w-xs")(file.path),
      ),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(Components.fileTypeBadge(file.fileType)),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400 tabular-nums")(file.lineCount.toString),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(
        if count > 0 then span(cls := "text-green-400")(s"$count record(s)") else span(cls := "text-gray-500")("—")
      ),
      td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
        a(href := s"/analysis/${file.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("View")
      ),
    )

  private def divisionsSection(divisions: CobolDivisions): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Divisions"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        divisionCard("Identification", divisions.identification),
        divisionCard("Environment", divisions.environment),
        divisionCard("Data", divisions.data),
        divisionCard("Procedure", divisions.procedure),
      ),
    )

  private def divisionCard(name: String, content: Option[String]): Frag =
    div(cls := "bg-white/5 rounded-md p-3")(
      h3(cls := "text-sm font-medium text-gray-300 mb-1")(name),
      content match
        case Some(c) => pre(cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-40 overflow-y-auto")(c)
        case None    => p(cls := "text-xs text-gray-500 italic")("Not present"),
    )

  private def variablesSection(variables: List[Variable]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Variables (${variables.length})"),
      if variables.isEmpty then p(cls := "text-sm text-gray-400")("No variables found.")
      else
        div(cls := "overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10 text-sm")(
            thead(
              tr(
                th(cls := "py-2 pr-3 text-left text-xs font-semibold text-gray-400")("Level"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Name"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Type"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Picture"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Usage"),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(
              variables.map { v =>
                tr(
                  td(cls := "py-2 pr-3 text-gray-400 tabular-nums")(v.level.toString.padTo(2, ' ')),
                  td(cls := "px-3 py-2 text-white font-mono")(v.name),
                  td(cls := "px-3 py-2 text-gray-400")(v.dataType),
                  td(cls := "px-3 py-2 text-gray-400 font-mono")(v.picture.getOrElse("—")),
                  td(cls := "px-3 py-2 text-gray-400")(v.usage.getOrElse("—")),
                )
              }
            ),
          )
        ),
    )

  private def proceduresSection(procedures: List[Procedure]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Procedures (${procedures.length})"),
      if procedures.isEmpty then p(cls := "text-sm text-gray-400")("No procedures found.")
      else
        div(cls := "space-y-3")(
          procedures.map { proc =>
            div(cls := "bg-white/5 rounded-md p-4")(
              h3(cls := "text-sm font-semibold text-white mb-2")(proc.name),
              if proc.paragraphs.nonEmpty then
                div(cls := "mb-2")(
                  span(cls := "text-xs font-medium text-gray-400")("Paragraphs: "),
                  span(cls := "text-xs text-gray-300")(proc.paragraphs.mkString(", ")),
                )
              else frag(),
              if proc.statements.nonEmpty then
                div(
                  span(cls := "text-xs font-medium text-gray-400")(s"${proc.statements.length} statement(s)")
                )
              else frag(),
            )
          }
        ),
    )

  private def copybooksSection(copybooks: List[String]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Copybook Dependencies (${copybooks.length})"),
      if copybooks.isEmpty then p(cls := "text-sm text-gray-400")("No copybook dependencies.")
      else
        div(cls := "flex flex-wrap gap-2")(
          copybooks.map { cb =>
            span(
              cls := "inline-flex items-center rounded-md bg-purple-500/10 px-2.5 py-1 text-xs font-medium text-purple-400 ring-1 ring-inset ring-purple-500/20"
            )(cb)
          }
        ),
    )

  private def complexitySection(metrics: ComplexityMetrics): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Complexity Metrics"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3")(
        metricCard("Cyclomatic Complexity", metrics.cyclomaticComplexity.toString),
        metricCard("Lines of Code", metrics.linesOfCode.toString),
        metricCard("Procedures", metrics.numberOfProcedures.toString),
      ),
    )

  private def metricCard(label: String, value: String): Frag =
    div(cls := "bg-white/5 rounded-md p-4 text-center")(
      p(cls := "text-2xl font-semibold text-white")(value),
      p(cls := "text-xs text-gray-400 mt-1")(label),
    )

  private def rawJsonSection(json: String): Frag =
    tag("details")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg mb-6")(
      tag("summary")(cls := "px-6 py-4 text-sm font-semibold text-gray-300 cursor-pointer hover:text-white")(
        "Raw JSON"
      ),
      div(cls := "px-6 pb-4")(
        pre(
          cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-96 overflow-y-auto bg-black/20 rounded-md p-4"
        )(
          json
        )
      ),
    )
