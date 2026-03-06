package shared.web

import issues.entity.IssueWorkReport
import scalatags.Text.all.*
import taskrun.entity.{ CiStatus, PrStatus }

/** Renders the proof-of-work panel for an issue.
  *
  * Call [[panel]] with the projection's `IssueWorkReport`. Returns an empty string when no signals are present so
  * callers don't need to guard.
  */
object ProofOfWorkView:

  /** Render the full panel. Returns an empty string when the report has no signals.
    *
    * @param report
    *   the work report for this issue
    * @param collapsed
    *   if true the panel is rendered collapsed (toggle-able by JS)
    */
  def panel(report: IssueWorkReport, collapsed: Boolean): String =
    if !hasAnySignal(report) then ""
    else
      div(
        cls                        := "mt-3 rounded-lg border border-white/10 bg-slate-950/60 p-3 text-xs text-slate-200",
        attr("data-proof-of-work") := report.issueId.value,
        if collapsed then attr("data-pow-collapsed") := "true" else (),
      )(
        div(cls := "mb-2 flex items-center justify-between")(
          span(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-400")("Proof of Work"),
          if collapsed then
            button(
              `type`                  := "button",
              cls                     := "text-[11px] text-indigo-300 hover:text-indigo-200",
              attr("data-pow-toggle") := report.issueId.value,
            )("Show")
          else (),
        ),
        div(if collapsed then cls := "hidden" else cls := "")(
          report.walkthrough.map { w =>
            div(cls := "mb-2")(
              p(cls := "mb-1 text-[11px] font-semibold text-slate-300")("Walkthrough"),
              p(cls := "text-slate-200 leading-relaxed")(w),
            )
          },
          report.agentSummary.map { s =>
            div(cls := "mb-2")(
              p(cls := "mb-1 text-[11px] font-semibold text-slate-300")("Agent Summary"),
              p(cls := "text-slate-300")(s),
            )
          },
          report.diffStats.map { ds =>
            div(cls := "mb-2 flex gap-3")(
              span(cls := "text-slate-400")(s"${ds.filesChanged} files"),
              span(cls := "text-emerald-400")(s"+${ds.linesAdded}"),
              span(cls := "text-red-400")(s"-${ds.linesRemoved}"),
            )
          },
          report.prLink.map { url =>
            div(cls := "mb-2 flex items-center gap-2")(
              a(
                href   := url,
                target := "_blank",
                cls    := "truncate text-indigo-300 hover:text-indigo-200 underline",
              )(url),
              report.prStatus.map(s => prStatusBadge(s)),
            )
          },
          report.ciStatus.map { ci =>
            div(cls := "mb-2")(ciStatusBadge(ci))
          },
          report.tokenUsage.map { usage =>
            div(cls := "mb-2 flex gap-3 text-slate-400")(
              span(s"${usage.totalTokens} tokens"),
              report.runtimeSeconds.map(s => span(s"${s}s")),
            )
          },
          if report.reports.nonEmpty then
            div(cls := "mb-2")(
              p(cls := "mb-1 text-[11px] font-semibold text-slate-300")("Reports"),
              div(cls := "space-y-1")(
                report.reports.map { r =>
                  div(cls := "rounded border border-white/10 px-2 py-1")(
                    span(cls := "font-medium")(r.stepName),
                    span(cls := " ml-2 text-slate-400")(r.content),
                  )
                }
              ),
            )
          else (),
        ),
      ).render

  private def hasAnySignal(r: IssueWorkReport): Boolean =
    r.walkthrough.isDefined ||
    r.agentSummary.isDefined ||
    r.diffStats.isDefined ||
    r.prLink.isDefined ||
    r.ciStatus.isDefined ||
    r.tokenUsage.isDefined ||
    r.reports.nonEmpty ||
    r.artifacts.nonEmpty

  private def prStatusBadge(s: PrStatus): Frag =
    val (bg, text) = s match
      case PrStatus.Open   => ("bg-emerald-500/20 text-emerald-200", "Open")
      case PrStatus.Merged => ("bg-indigo-500/20 text-indigo-200", "Merged")
      case PrStatus.Closed => ("bg-slate-500/20 text-slate-300", "Closed")
      case PrStatus.Draft  => ("bg-yellow-500/20 text-yellow-200", "Draft")
    span(cls := s"rounded-full px-2 py-0.5 $bg")(text)

  private def ciStatusBadge(ci: CiStatus): Frag =
    val (bg, text) = ci match
      case CiStatus.Passed  => ("bg-emerald-500/20 text-emerald-200", "Passed")
      case CiStatus.Failed  => ("bg-red-500/20 text-red-300", "Failed")
      case CiStatus.Running => ("bg-yellow-500/20 text-yellow-200", "Running")
      case CiStatus.Pending => ("bg-slate-500/20 text-slate-300", "Pending")
    span(cls := s"rounded-full px-2 py-0.5 $bg")(text)
