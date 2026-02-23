package shared.web.components

import scalatags.Text.all.*

object ToolCallCard:

  def card(
    toolName: String,
    status: String,
    inputJson: Option[String] = None,
    outputJson: Option[String] = None,
  ): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-800/60 p-3 my-2")(
      div(cls := "flex items-center gap-2 mb-1")(
        statusIndicator(status),
        span(cls := "text-sm font-semibold text-indigo-300")(toolName),
        span(cls := s"text-xs px-2 py-0.5 rounded-full ${statusClasses(status)}")(status),
      ),
      inputJson.map { json =>
        tag("details")(cls := "text-xs text-gray-400 mt-1")(
          tag("summary")(cls := "cursor-pointer text-gray-500 hover:text-gray-300")("Input"),
          pre(cls := "mt-1 overflow-x-auto bg-black/30 rounded p-2 text-green-300")(json),
        )
      },
      outputJson.map { json =>
        tag("details")(cls := "text-xs text-gray-400 mt-1", attr("open") := "")(
          tag("summary")(cls := "cursor-pointer text-gray-500 hover:text-gray-300")("Output"),
          pre(cls := "mt-1 overflow-x-auto bg-black/30 rounded p-2 text-blue-300")(json),
        )
      },
    )

  private def statusIndicator(status: String): Frag =
    val dotColor = status match
      case "running"   => "bg-blue-400 animate-pulse"
      case "completed" => "bg-green-400"
      case "failed"    => "bg-red-400"
      case _           => "bg-gray-400"
    span(cls := s"w-2 h-2 rounded-full inline-block $dotColor")

  private def statusClasses(status: String): String = status match
    case "running"   => "bg-blue-500/10 text-blue-400 ring-1 ring-blue-500/20"
    case "completed" => "bg-green-500/10 text-green-400 ring-1 ring-green-500/20"
    case "failed"    => "bg-red-500/10 text-red-400 ring-1 ring-red-500/20"
    case _           => "bg-gray-500/10 text-gray-400 ring-1 ring-gray-500/20"
