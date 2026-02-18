package web.views

import db.TaskReportRow
import scalatags.Text.all.*

object ReportsView:

  def reportsHome: String =
    Layout.page("Reports", "/reports")(
      h1(cls := "text-2xl font-bold text-white mb-4")("Reports"),
      div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-6")(
        p(cls := "text-sm text-gray-300 mb-3")("Select a task to view its reports."),
        a(href := "/tasks", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")("Go to Tasks"),
      ),
    )

  def reportsList(taskId: Long, reports: List[TaskReportRow]): String =
    Layout.page(s"Reports for Task #$taskId", "/reports")(
      div(cls := "flex items-center justify-between mb-6")(
        div(
          h1(cls := "text-2xl font-bold text-white")(s"Reports for Task #$taskId"),
          p(cls := "text-sm text-gray-400 mt-1")("Generic step reports stored in task_reports."),
        ),
        a(href := s"/tasks/$taskId", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")(
          "Back to Task"
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        div(cls := "px-6 py-4 border-b border-white/10")(
          h2(cls := "text-lg font-semibold text-white")("Task Reports")
        ),
        if reports.isEmpty then Components.emptyState("No reports found for this task.")
        else
          div(cls := "overflow-x-auto")(
            table(cls := "min-w-full divide-y divide-white/10")(
              thead(cls := "bg-white/5")(
                tr(
                  th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Step"
                  ),
                  th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Type"
                  ),
                  th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Created"
                  ),
                  th(cls := "relative py-3 pl-3 pr-6"),
                )
              ),
              tbody(cls := "divide-y divide-white/5")(
                reports.map(reportRow)
              ),
            )
          ),
      ),
    )

  def reportDetail(report: TaskReportRow): String =
    Layout.page(s"Report #${report.id}", "/reports")(
      div(cls := "flex items-center justify-between mb-6")(
        div(
          h1(cls := "text-2xl font-bold text-white")(s"${report.stepName} Report"),
          p(cls := "text-sm text-gray-400 mt-1")(s"Report #${report.id} • Task #${report.taskRunId}"),
        ),
        a(
          href := s"/reports?taskId=${report.taskRunId}",
          cls  := "text-indigo-400 hover:text-indigo-300 text-sm font-medium",
        )("Back to Reports"),
      ),
      div(cls := "mb-4 flex items-center gap-3")(
        typeBadge(report.reportType),
        span(cls := "text-xs text-gray-500")(report.createdAt.toString.replace("T", " ").take(19)),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
        pre(id := "report-source", cls := "hidden")(report.content),
        div(id := "report-render-target", cls := "prose prose-invert max-w-none text-sm text-gray-100"),
      ),
      reportScripts(report.reportType),
    )

  private def reportRow(report: TaskReportRow): Frag =
    tr(cls := "hover:bg-white/5")(
      td(cls := "whitespace-nowrap py-4 pl-6 pr-3 text-sm font-medium text-white")(report.stepName),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(typeBadge(report.reportType)),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(report.createdAt.toString.replace(
        "T",
        " ",
      ).take(19)),
      td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
        a(href := s"/reports/${report.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("Open")
      ),
    )

  private def typeBadge(reportType: String): Frag =
    val normalized = reportType.trim.toLowerCase
    val classes    = normalized match
      case "markdown" =>
        "inline-flex items-center rounded-md bg-indigo-500/10 px-2 py-1 text-xs font-medium text-indigo-300 ring-1 ring-indigo-400/20"
      case "graph"    =>
        "inline-flex items-center rounded-md bg-emerald-500/10 px-2 py-1 text-xs font-medium text-emerald-300 ring-1 ring-emerald-400/20"
      case _          =>
        "inline-flex items-center rounded-md bg-gray-500/10 px-2 py-1 text-xs font-medium text-gray-300 ring-1 ring-gray-400/20"
    span(cls := classes)(normalized)

  private def reportScripts(reportType: String): Frag =
    val normalized = reportType.trim.toLowerCase
    normalized match
      case "markdown" =>
        frag(
          JsResources.markedScript,
          script(
            raw(
              """document.addEventListener('DOMContentLoaded', function () {
                |  var source = document.getElementById('report-source');
                |  var target = document.getElementById('report-render-target');
                |  if (!source || !target) return;
                |  if (window.marked && window.marked.parse) {
                |    target.innerHTML = window.marked.parse(source.textContent || '');
                |  } else {
                |    target.textContent = source.textContent || '';
                |  }
                |});
                |""".stripMargin
            )
          ),
        )
      case "graph"    =>
        frag(
          JsResources.mermaidScript,
          script(
            raw(
              """document.addEventListener('DOMContentLoaded', function () {
                |  var source = document.getElementById('report-source');
                |  var target = document.getElementById('report-render-target');
                |  if (!source || !target) return;
                |  var graph = source.textContent || '';
                |  if (!window.mermaid) {
                |    target.textContent = graph;
                |    return;
                |  }
                |  window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
                |  var container = document.createElement('div');
                |  container.className = 'mermaid text-slate-200';
                |  container.textContent = graph;
                |  target.innerHTML = '';
                |  target.appendChild(container);
                |  window.mermaid.run({ nodes: [container] }).catch(function () {
                |    target.textContent = graph;
                |  });
                |});
                |""".stripMargin
            )
          ),
        )
      case _          =>
        script(
          raw(
            """document.addEventListener('DOMContentLoaded', function () {
              |  var source = document.getElementById('report-source');
              |  var target = document.getElementById('report-render-target');
              |  if (!source || !target) return;
              |  target.textContent = source.textContent || '';
              |});
              |""".stripMargin
          )
        )
