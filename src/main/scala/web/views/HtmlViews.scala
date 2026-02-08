package web.views

import db.{ CobolAnalysisRow, CobolFileRow, DependencyRow, MigrationRunRow, PhaseProgressRow }
import scalatags.Text.all.*
import scalatags.Text.tags2.title as titleTag

object HtmlViews:

  def dashboard(runs: List[MigrationRunRow]): String =
    page(
      titleText = "Migration Dashboard",
      bodyContent = div(
        h1("Migration Dashboard"),
        p(s"Total runs: ${runs.length}"),
        runTable(runs),
      ),
    )

  def runsList(runs: List[MigrationRunRow], pageNumber: Int, pageSize: Int): String =
    page(
      titleText = "Runs",
      bodyContent = div(
        h1("Migration Runs"),
        p(s"Page: $pageNumber, pageSize: $pageSize"),
        runTable(runs),
      ),
    )

  def runDetail(run: MigrationRunRow, phases: List[PhaseProgressRow]): String =
    val phaseRows = phases.map { phase =>
      tr(
        td(phase.phase),
        td(phase.status),
        td(s"${phase.itemProcessed}/${phase.itemTotal}"),
        td(phase.errorCount.toString),
      )
    }

    page(
      titleText = s"Run ${run.id}",
      bodyContent = div(
        h1(s"Run ${run.id}"),
        p(s"Status: ${run.status}"),
        p(s"Source: ${run.sourceDir}"),
        p(s"Output: ${run.outputDir}"),
        table(
          thead(tr(th("Phase"), th("Status"), th("Progress"), th("Errors"))),
          tbody(phaseRows),
        ),
      ),
    )

  def runForm: String =
    page(
      titleText = "New Run",
      bodyContent = div(
        h1("Start Migration"),
        form(method := "post", action := "/runs")(
          label("Source dir"),
          input(name := "sourceDir", `type` := "text", required := true),
          br(),
          label("Output dir"),
          input(name := "outputDir", `type` := "text", required := true),
          br(),
          label("Dry run"),
          input(name := "dryRun", `type`    := "checkbox"),
          br(),
          button(`type` := "submit")("Start"),
        ),
      ),
    )

  def analysisList(runId: Long, files: List[CobolFileRow], analyses: List[CobolAnalysisRow]): String =
    val analysisByFile = analyses.groupBy(_.fileId)
    page(
      titleText = s"Analysis $runId",
      bodyContent = div(
        h1(s"Analysis for run $runId"),
        ul(
          files.map { file =>
            li(
              a(href := s"/analysis/${file.id}")(file.name),
              span(s" (${analysisByFile.getOrElse(file.id, Nil).size} records)"),
            )
          }
        ),
      ),
    )

  def analysisDetail(file: CobolFileRow, analysis: CobolAnalysisRow): String =
    page(
      titleText = s"Analysis ${file.name}",
      bodyContent = div(
        h1(s"Analysis ${file.name}"),
        p(s"Path: ${file.path}"),
        pre(analysis.analysisJson),
      ),
    )

  def analysisSearchFragment(files: List[CobolFileRow]): String =
    ul(
      files.map(file => li(a(href := s"/analysis/${file.id}")(file.name)))
    ).render

  def graphPage(runId: Long, deps: List[DependencyRow]): String =
    page(
      titleText = s"Graph $runId",
      bodyContent = div(
        h1(s"Dependency graph for run $runId"),
        p(s"Edges: ${deps.length}"),
      ),
    )

  def recentRunsFragment(runs: List[MigrationRunRow]): String =
    ul(
      runs.map(run => li(a(href := s"/runs/${run.id}")(s"Run ${run.id} - ${run.status}")))
    ).render

  private def runTable(runs: List[MigrationRunRow]) =
    table(
      thead(tr(th("ID"), th("Status"), th("Started"), th("Phase"))),
      tbody(
        runs.map(run =>
          tr(
            td(a(href := s"/runs/${run.id}")(run.id.toString)),
            td(run.status.toString),
            td(run.startedAt.toString),
            td(run.currentPhase.getOrElse("-")),
          )
        )
      ),
    )

  private def page(titleText: String, bodyContent: Modifier): String =
    "<!doctype html>" + html(
      head(meta(charset := "utf-8"), titleTag(titleText)),
      body(bodyContent),
    ).render
