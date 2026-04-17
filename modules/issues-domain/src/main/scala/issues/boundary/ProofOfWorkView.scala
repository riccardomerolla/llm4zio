package issues.boundary

import scalatags.Text.all.*

import issues.entity.{ IssueCiStatus, IssuePrStatus, IssueWorkReport }
import workspace.entity.RequirementCheck

/** Renders the proof-of-work panel for an issue.
  *
  * Call [[panel]] with the projection's `IssueWorkReport`. Returns an empty string when no signals are present so
  * callers don't need to guard.
  *
  * Moved from `shared-web` to `issues-domain/boundary` in phase 5A.1 — `issuesDomain` already dependsOn
  * `workspaceDomain`, so the dependency graph is clean.
  */
object ProofOfWorkView:

  /** Render the full panel. Returns an empty string when the report has no signals.
    *
    * @param report
    *   the work report for this issue
    * @param collapsed
    *   if true the panel is rendered collapsed (toggle-able by JS)
    */
  def panel(report: IssueWorkReport, collapsed: Boolean, checks: List[RequirementCheck] = Nil): String =
    if !hasAnySignal(report) && checks.isEmpty then ""
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
          if checks.nonEmpty then
            div(cls := "mt-3")(
              p(cls := "mb-2 text-[11px] font-semibold text-slate-300")("Verification Checklist"),
              div(cls := "space-y-2")(
                checks.map { check =>
                  val stateCls =
                    if check.passed then "border-emerald-400/30 bg-emerald-500/10 text-emerald-100"
                    else "border-rose-400/30 bg-rose-500/10 text-rose-100"
                  div(cls := s"rounded border px-2 py-2 text-[11px] $stateCls")(
                    div(cls := "flex items-center justify-between gap-2")(
                      span(cls := "font-medium")(check.requirement),
                      span(cls := "text-[10px] font-semibold uppercase tracking-wide")(
                        if check.passed then "Pass" else "Fail"
                      ),
                    ),
                    p(cls := "mt-1 text-[10px] opacity-90")(check.details),
                  )
                }
              ),
            )
          else (),
        ),
      ).render

  /** Render a compact expandable evidence bar for use on board cards.
    *
    * Returns an empty string when the report has no signals. Uses a native HTML `<details>`/`<summary>` element so no
    * JavaScript is required for expand/collapse.
    */
  def evidenceBar(report: IssueWorkReport): String =
    if !hasAnySignal(report) then ""
    else
      val chips: Seq[Frag] = Seq(
        report.prStatus.map(s => prStatusBadge(s)),
        report.ciStatus.map(ci => ciStatusBadge(ci)),
        report.diffStats.map(ds =>
          span(cls := "rounded-full bg-slate-700/60 px-2 py-0.5 text-[10px] text-slate-300")(
            s"${ds.filesChanged} files"
          )
        ),
      ).flatten

      tag("details")(
        cls := "mt-2 border-t border-white/10 pt-2"
      )(
        tag("summary")(
          cls := "flex cursor-pointer list-none flex-wrap items-center gap-1.5 text-[10px] text-slate-400 hover:text-slate-200"
        )(
          span(cls := "mr-1 text-slate-500")("Evidence"),
          chips,
        ),
        div(cls := "mt-2")(
          raw(panel(report, collapsed = false))
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

  private def prStatusBadge(s: IssuePrStatus): Frag =
    val (bg, text) = s match
      case IssuePrStatus.Open   => ("bg-emerald-500/20 text-emerald-200", "Open")
      case IssuePrStatus.Merged => ("bg-indigo-500/20 text-indigo-200", "Merged")
      case IssuePrStatus.Closed => ("bg-slate-500/20 text-slate-300", "Closed")
      case IssuePrStatus.Draft  => ("bg-yellow-500/20 text-yellow-200", "Draft")
    span(cls := s"rounded-full px-2 py-0.5 $bg")(text)

  private def ciStatusBadge(ci: IssueCiStatus): Frag =
    val (bg, text) = ci match
      case IssueCiStatus.Passed  => ("bg-emerald-500/20 text-emerald-200", "Passed")
      case IssueCiStatus.Failed  => ("bg-red-500/20 text-red-300", "Failed")
      case IssueCiStatus.Running => ("bg-yellow-500/20 text-yellow-200", "Running")
      case IssueCiStatus.Pending => ("bg-slate-500/20 text-slate-300", "Pending")
    span(cls := s"rounded-full px-2 py-0.5 $bg")(text)
