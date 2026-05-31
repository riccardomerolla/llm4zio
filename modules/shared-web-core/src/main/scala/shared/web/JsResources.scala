package shared.web

import scala.io.Source

import scalatags.Text.all.*

object JsResources:

  object WebJars:
    val tailwindBrowserScript: String = "/webjars/tailwindcss__browser/4.1.12/dist/index.global.js"
    val htmxScript: String            = "/webjars/htmx.org/2.0.7/dist/htmx.min.js"
    val htmxSseScript: String         = "/webjars/htmx-ext-sse/2.2.4/dist/sse.min.js"
    val markedScript: String          = "/webjars/marked/16.2.1/lib/marked.umd.js"
    val mermaidScript: String         = "/webjars/mermaid/11.6.0/dist/mermaid.min.js"
    val highlightTheme: String        = "/webjars/highlight.js/11.11.1/styles/github-dark.min.css"

  val tailwindBrowserScript: Frag = script(src := WebJars.tailwindBrowserScript)

  val htmxScript: Frag = script(src := WebJars.htmxScript)

  val htmxSseScript: Frag = script(src := WebJars.htmxSseScript)

  val markedScript: Frag = script(src := WebJars.markedScript)

  val mermaidScript: Frag = script(src := WebJars.mermaidScript)

  val highlightThemeStylesheet: Frag =
    tag("link")(
      attr("rel")  := "stylesheet",
      attr("href") := WebJars.highlightTheme,
    )

  val importMapScript: Frag =
    script(attr("type") := "importmap")(raw("""
      |{
      |  "imports": {
      |    "lit": "/webjars/lit/3.3.0/index.js",
      |    "lit/": "/webjars/lit/3.3.0/",
      |    "lit-element/": "/webjars/lit-element/4.2.2/",
      |    "lit-html": "/webjars/lit-html/3.3.3/lit-html.js",
      |    "lit-html/": "/webjars/lit-html/3.3.3/",
      |    "@lit/reactive-element": "/webjars/lit__reactive-element/2.1.2/reactive-element.js",
      |    "@lit/reactive-element/": "/webjars/lit__reactive-element/2.1.2/",
      |    "@lit-labs/ssr-dom-shim": "/webjars/lit-labs__ssr-dom-shim/1.6.0/index.js",
      |    "@lit-labs/ssr-dom-shim/": "/webjars/lit-labs__ssr-dom-shim/1.6.0/",
      |    "highlight.js": "/webjars/highlight.js/11.11.1/es/common.js",
      |    "chart.js": "/webjars/chart.js/4.4.7/dist/chart.js",
      |    "@kurkle/color": "/webjars/kurkle__color/0.3.4/dist/color.esm.js"
      |  }
      |}
      |""".stripMargin))

  def inlineModuleScript(resourcePath: String): Frag =
    script(attr("type") := "module")(raw(load(resourcePath)))

  private def load(resourcePath: String): String =
    val streamOpt = Option(getClass.getResourceAsStream(resourcePath))
    streamOpt match
      case Some(stream) =>
        val source = Source.fromInputStream(stream, "UTF-8")
        try source.mkString
        finally source.close()
      case None         =>
        s"console.error('Missing JS resource: $resourcePath');"
