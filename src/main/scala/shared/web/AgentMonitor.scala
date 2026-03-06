package shared.web

import scalatags.Text.all.*

object AgentMonitor:

  def page: String =
    Layout.page("Agent Monitor", "/agent-monitor")(
      div(cls := "mb-4 flex items-center justify-between")(
        div(
          h1(cls := "text-2xl font-bold text-white")("Agent Activity Monitor"),
          p(cls := "mt-1 text-sm text-slate-400")(
            "Real-time execution states, resource usage, and control actions for all agents"
          ),
        ),
      ),
      div(
        cls                    := "rounded-lg border border-white/10 bg-black/60",
        attr("hx-ext")         := "sse",
        attr("sse-connect")    := "/agent-monitor/stream",
        attr("sse-swap")       := "agent-table",
        attr("hx-target")      := "#agent-table-container",
        attr("hx-swap")        := "innerHTML",
      )(
        div(id := "agent-table-container")(
          AgentMonitorView.table(Nil)
        )
      ),
    )
