package web.views

import db.DependencyRow
import scalatags.Text.all.*

object GraphView:

  def page(runId: Long, deps: List[DependencyRow]): String =
    val mermaidCode = generateMermaid(deps)

    Layout.page(s"Dependency Graph — Run #$runId", "/graph")(
      div(cls := "sm:flex sm:items-center mb-8")(
        div(cls := "sm:flex-auto")(
          h1(cls := "text-2xl font-bold text-white")(s"Dependency Graph — Run #$runId"),
          p(cls := "mt-2 text-sm text-gray-400")(s"${deps.length} dependencies"),
        ),
        div(cls := "mt-4 sm:ml-16 sm:mt-0 sm:flex-none flex gap-3")(
          a(
            href             := s"/api/graph/$runId/export?format=mermaid",
            attr("download") := s"run-$runId-graph.mmd",
            cls              := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-white/20",
          )("Export Mermaid"),
          a(
            href             := s"/api/graph/$runId/export?format=json",
            attr("download") := s"run-$runId-graph.json",
            cls              := "rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-white/20",
          )("Export JSON"),
        ),
      ),
      // Mermaid diagram container
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 overflow-x-auto")(
        if deps.isEmpty then Components.emptyState("No dependencies found for this run.")
        else
          div(cls := "mermaid")(
            mermaidCode
          )
      ),
      // Mermaid initialisation (ESM import, dark theme)
      script(attr("type") := "module")(
        raw(
          """import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
            |mermaid.initialize({
            |  startOnLoad: true,
            |  theme: 'dark',
            |  themeVariables: {
            |    darkMode: true,
            |    background: '#111827',
            |    primaryColor: '#6366f1',
            |    primaryTextColor: '#fff',
            |    primaryBorderColor: '#4f46e5',
            |    lineColor: '#6b7280',
            |    secondaryColor: '#374151',
            |    tertiaryColor: '#1f2937'
            |  }
            |});""".stripMargin
        )
      ),
      // Dependency table
      if deps.nonEmpty then
        div(cls := "mt-8 bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
          div(cls := "px-6 py-4 border-b border-white/10")(
            h2(cls := "text-lg font-semibold text-white")("Dependency Table")
          ),
          div(cls := "overflow-x-auto")(
            table(cls := "min-w-full divide-y divide-white/10 text-sm")(
              thead(cls := "bg-white/5")(
                tr(
                  th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Source"
                  ),
                  th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Target"
                  ),
                  th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                    "Type"
                  ),
                )
              ),
              tbody(cls := "divide-y divide-white/5")(
                deps.map { dep =>
                  tr(cls := "hover:bg-white/5")(
                    td(cls := "py-3 pl-6 pr-3 text-white font-mono")(dep.sourceNode),
                    td(cls := "px-3 py-3 text-white font-mono")(dep.targetNode),
                    td(cls := "px-3 py-3 text-gray-400")(dep.edgeType),
                  )
                }
              ),
            )
          ),
        )
      else frag(),
    )

  private def generateMermaid(deps: List[DependencyRow]): String =
    if deps.isEmpty then "graph TD\n  empty[No dependencies]"
    else
      val edges = deps.map { dep =>
        s"  ${sanitize(dep.sourceNode)} -->|${dep.edgeType}| ${sanitize(dep.targetNode)}"
      }
      ("graph TD" :: edges).mkString("\n")

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9_]", "_")
