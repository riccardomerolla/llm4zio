package web.views

import db.TaskReportRow
import scalatags.Text.all.*

object GraphView:

  def home: String =
    Layout.page("Graph", "/graph")(
      h1(cls := "text-2xl font-bold text-white mb-4")("Graph"),
      div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-6")(
        p(cls := "text-sm text-gray-300 mb-3")("Select a task to view graph reports."),
        a(href := "/tasks", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")("Go to Tasks"),
      ),
    )

  def page(taskId: Long, graphReports: List[TaskReportRow]): String =
    val initial = graphReports.headOption
    Layout.page(s"Graph Reports for Task #$taskId", "/graph")(
      div(cls := "flex items-center justify-between mb-6")(
        div(
          h1(cls := "text-2xl font-bold text-white")(s"Graph Reports for Task #$taskId"),
          p(cls := "text-sm text-gray-400 mt-1")("Mermaid graphs sourced from task_reports."),
        ),
        a(href := s"/tasks/$taskId", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")(
          "Back to Task"
        ),
      ),
      if graphReports.isEmpty then Components.emptyState("No graph reports available for this task.")
      else
        div(cls := "space-y-4")(
          selector(graphReports),
          div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
            div(id := "graph-meta", cls := "mb-3 text-xs text-gray-400"),
            div(
              id   := "graph-render-target",
              cls  := "min-h-[320px] overflow-auto rounded-md border border-white/10 bg-black/20 p-4",
            ),
          ),
          script(
            id           := "graph-initial-data",
            cls          := "hidden",
            attr("type") := "application/json",
          )(raw(initialJson(initial))),
          JsResources.mermaidScript,
          script(raw(clientScript())),
        ),
    )

  private def selector(graphReports: List[TaskReportRow]): Frag =
    div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
      label(cls := "block text-sm text-gray-300 mb-2", `for` := "graph-selector")("Graph"),
      select(
        id  := "graph-selector",
        cls := "w-full rounded-md bg-black/20 ring-1 ring-white/10 px-3 py-2 text-sm text-white",
      )(
        graphReports.map { report =>
          val createdAt = report.createdAt.toString.replace("T", " ").take(19)
          option(value := report.id.toString)(
            s"${report.stepName} ($createdAt)"
          )
        }
      ),
    )

  private def initialJson(initial: Option[TaskReportRow]): String =
    initial match
      case Some(report) =>
        s"""{"id":${report.id},"stepName":"${escape(report.stepName)}","createdAt":"${escape(
            report.createdAt.toString
          )}","source":"${escape(report.content)}"}"""
      case None         =>
        "{}"

  private def escape(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "")

  private def clientScript(): String =
    """document.addEventListener('DOMContentLoaded', function () {
      |  var selector = document.getElementById('graph-selector');
      |  var target = document.getElementById('graph-render-target');
      |  var meta = document.getElementById('graph-meta');
      |  var initialNode = document.getElementById('graph-initial-data');
      |  if (!selector || !target || !meta) return;
      |
      |  function renderMermaid(payload) {
      |    if (!payload || !payload.source) {
      |      target.textContent = 'No graph content available.';
      |      meta.textContent = '';
      |      return;
      |    }
      |    meta.textContent = payload.stepName + ' • ' + payload.createdAt.replace('T', ' ').slice(0, 19);
      |    if (!window.mermaid) {
      |      target.textContent = payload.source;
      |      return;
      |    }
      |    window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
      |    var container = document.createElement('div');
      |    container.className = 'mermaid text-slate-200';
      |    container.textContent = payload.source;
      |    target.innerHTML = '';
      |    target.appendChild(container);
      |    window.mermaid.run({ nodes: [container] }).catch(function () {
      |      target.textContent = payload.source;
      |    });
      |  }
      |
      |  async function loadGraph(reportId) {
      |    var resp = await fetch('/api/graph/' + reportId, { headers: { 'Accept': 'application/json' } });
      |    if (!resp.ok) {
      |      target.textContent = 'Failed to load graph report.';
      |      return;
      |    }
      |    var payload = await resp.json();
      |    renderMermaid(payload);
      |  }
      |
      |  selector.addEventListener('change', function () {
      |    loadGraph(selector.value).catch(function () {
      |      target.textContent = 'Failed to load graph report.';
      |    });
      |  });
      |
      |  try {
      |    var initial = initialNode ? JSON.parse(initialNode.textContent || '{}') : null;
      |    if (initial && initial.source) {
      |      renderMermaid(initial);
      |      selector.value = String(initial.id);
      |    } else if (selector.value) {
      |      loadGraph(selector.value);
      |    }
      |  } catch (_) {
      |    if (selector.value) loadGraph(selector.value);
      |  }
      |});
      |""".stripMargin
