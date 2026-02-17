package web.views

import scalatags.Text.all.*

object LogsView:

  def page(defaultPath: String): String =
    Layout.page("Logs", "/logs")(
      div(cls := "mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Live Logs"),
        p(cls := "text-gray-400 text-sm mt-2")(
          "Real-time log tailing with level filtering, search, and syntax highlighting"
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("log-viewer")(
          id                := "log-viewer",
          cls               := "block",
          attr("ws-url")    := "/ws/logs",
          attr("log-path")  := defaultPath,
          attr("max-lines") := "1500",
        )()
      ),
      tag("link")(
        attr("rel")  := "stylesheet",
        attr("href") := "https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/log-viewer.js"),
    )
