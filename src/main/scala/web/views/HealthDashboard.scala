package web.views

import scalatags.Text.all.*

object HealthDashboard:

  def page: String =
    Layout.page("System Health", "/health")(
      div(cls := "mb-6")(
        h1(cls := "text-2xl font-bold text-white")("System Health Dashboard"),
        p(cls := "text-gray-400 text-sm mt-2")("Real-time gateway, agent, channel, and resource telemetry"),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("health-dashboard")(
          attr("ws-url") := "/ws/console"
        )()
      ),
      JsResources.inlineModuleScript("/static/client/components/health-dashboard.js"),
    )
