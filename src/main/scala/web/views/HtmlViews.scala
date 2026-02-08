package web.views

import db.{ CobolAnalysisRow, CobolFileRow, DependencyRow, MigrationRunRow, PhaseProgressRow }

object HtmlViews:

  def dashboard(runs: List[MigrationRunRow]): String =
    DashboardView.dashboard(runs)

  def runsList(runs: List[MigrationRunRow], pageNumber: Int, pageSize: Int): String =
    RunsView.list(runs, pageNumber, pageSize)

  def runDetail(run: MigrationRunRow, phases: List[PhaseProgressRow]): String =
    RunsView.detail(run, phases)

  def runForm: String =
    RunsView.form

  def recentRunsFragment(runs: List[MigrationRunRow]): String =
    RunsView.recentRunsFragment(runs)

  def analysisList(runId: Long, files: List[CobolFileRow], analyses: List[CobolAnalysisRow]): String =
    AnalysisView.list(runId, files, analyses)

  def analysisDetail(file: CobolFileRow, analysis: CobolAnalysisRow): String =
    AnalysisView.detail(file, analysis)

  def analysisSearchFragment(files: List[CobolFileRow]): String =
    AnalysisView.searchFragment(files)

  def graphPage(runId: Long, deps: List[DependencyRow]): String =
    GraphView.page(runId, deps)
