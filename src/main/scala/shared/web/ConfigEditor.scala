package shared.web

import scalatags.Text.all.*

object ConfigEditor:

  def page: String =
    Layout.page("Config Editor", "/config")(
      div(cls := "mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Advanced Configuration Editor"),
        p(cls := "text-gray-400 text-sm mt-2")(
          "Edit gateway configuration with validation, diff, history rollback, and hot reload"
        ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("config-editor")(
          attr("api-base") := "/api/config"
        )()
      ),
      tag("link")(
        attr("rel")  := "stylesheet",
        attr("href") := "https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/config-editor.js"),
    )
