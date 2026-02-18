package web.views

import db.{ FileType, RunStatus }
import scalatags.Text.all.*
import scalatags.Text.svgAttrs.{ d, viewBox }
import scalatags.Text.svgTags.{ path, svg }

object Components:

  def statusBadge(status: RunStatus): Frag =
    val (bg, text, label) = status match
      case RunStatus.Pending   => ("bg-yellow-500/10 ring-yellow-500/20", "text-yellow-400", "Pending")
      case RunStatus.Running   => ("bg-blue-500/10 ring-blue-500/20", "text-blue-400", "Running")
      case RunStatus.Paused    => ("bg-amber-500/10 ring-amber-500/20", "text-amber-400", "Paused")
      case RunStatus.Completed => ("bg-green-500/10 ring-green-500/20", "text-green-400", "Completed")
      case RunStatus.Failed    => ("bg-red-500/10 ring-red-500/20", "text-red-400", "Failed")
      case RunStatus.Cancelled => ("bg-gray-500/10 ring-gray-500/20", "text-gray-400", "Cancelled")
    span(cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium $bg $text ring-1 ring-inset")(
      label
    )

  def fileTypeBadge(ft: FileType): Frag =
    val (bg, text, label) = ft match
      case FileType.Program  => ("bg-indigo-500/10 ring-indigo-500/20", "text-indigo-400", "Program")
      case FileType.Copybook => ("bg-purple-500/10 ring-purple-500/20", "text-purple-400", "Copybook")
      case FileType.JCL      => ("bg-pink-500/10 ring-pink-500/20", "text-pink-400", "JCL")
      case FileType.Unknown  => ("bg-gray-500/10 ring-gray-500/20", "text-gray-400", "Unknown")
    span(cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium $bg $text ring-1 ring-inset")(
      label
    )

  def progressBar(current: Int, total: Int): Frag =
    val pct = if total > 0 then (current.toDouble / total * 100).toInt else 0
    div(cls := "w-full bg-white/10 rounded-full h-2.5")(
      div(cls := "bg-indigo-500 h-2.5 rounded-full transition-all duration-300", style := s"width: $pct%")
    )

  def summaryCard(titleText: String, value: String, svgPath: String): Frag =
    div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
      div(cls := "flex items-center justify-between")(
        div(
          p(cls := "text-sm font-medium text-gray-400")(titleText),
          p(cls := "mt-2 text-3xl font-semibold text-white")(value),
        ),
        svgIcon(svgPath, "size-10 text-indigo-400"),
      )
    )

  def loadingSpinner: Frag =
    div(cls := "htmx-indicator flex justify-center items-center p-4")(
      svgIcon(
        "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z",
        "animate-spin h-6 w-6 text-indigo-400",
      )
    )

  def emptyState(message: String): Frag =
    div(cls := "text-center py-12")(
      svgIcon(
        "M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z",
        "mx-auto size-12 text-gray-500 mb-4",
      ),
      p(cls := "text-sm text-gray-400")(message),
    )

  def svgIcon(pathD: String, classes: String): Frag =
    svg(
      cls                  := classes,
      viewBox              := "0 0 24 24",
      attr("fill")         := "none",
      attr("stroke")       := "currentColor",
      attr("stroke-width") := "1.5",
    )(
      path(
        d                       := pathD,
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
      )
    )
