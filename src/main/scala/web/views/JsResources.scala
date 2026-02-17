package web.views

import scala.io.Source

import scalatags.Text.all.*

object JsResources:

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
