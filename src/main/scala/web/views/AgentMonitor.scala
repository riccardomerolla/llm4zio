package web.views

import scalatags.Text.all.*

object AgentMonitor:

  def page: String =
    Layout.page("Agent Monitor", "/agent-monitor")(
      div(cls := "mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Agent Activity Monitor"),
        p(cls := "text-gray-400 text-sm mt-2")(
          "Real-time execution states, resource usage, and control actions for all agents"
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("agent-monitor-panel")(
          attr("ws-url") := "/ws/console"
        )()
      ),
      JsResources.inlineModuleScript("/static/client/components/agent-monitor.js"),
    )
