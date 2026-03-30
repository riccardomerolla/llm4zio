package shared.web

import issues.entity.IssueWorkReport
import issues.entity.api.{ AgentIssueView, IssueStatus }
import scalatags.Text.all.*
import shared.ids.Ids.IssueId

/** Aggregate statistics and filter helpers for the issue board. */
object BoardStats:

  final case class Stats(
    running: Int,
    completed: Int,
    tokensTotal: Long,
  )

  /** Compute board-level stats from the current issue list and projection snapshot. */
  def compute(issues: List[AgentIssueView], workReports: Map[IssueId, IssueWorkReport]): Stats =
    val running   = issues.count(_.status == IssueStatus.InProgress)
    val completed = issues.count(s => s.status == IssueStatus.Done)
    val tokens    = workReports.values.flatMap(_.tokenUsage.map(_.totalTokens)).sum
    Stats(running, completed, tokens)

  /** Keep only issues that have at least one proof-of-work signal in the projection. */
  def hasProofFilter(
    issues: List[AgentIssueView],
    workReports: Map[IssueId, IssueWorkReport],
  ): List[AgentIssueView] =
    issues.filter { issue =>
      issue.id.exists { id =>
        workReports.get(IssueId(id)).exists(hasSignal)
      }
    }

  /** Render a compact stats bar to embed above the board columns. */
  def statsBar(stats: Stats): String =
    val tokensLabel =
      if stats.tokensTotal >= 1000 then s"${stats.tokensTotal / 1000}k"
      else stats.tokensTotal.toString
    div(
      cls                      := "flex flex-wrap items-center gap-3 rounded-xl border border-white/10 bg-slate-900/60 px-4 py-2",
      attr("data-board-stats") := "true",
    )(
      div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
        span(cls := "h-2 w-2 rounded-full bg-amber-400"),
        span(cls := "text-xs font-semibold text-amber-300")(stats.running.toString),
        span(cls := "text-xs text-slate-400")("running"),
      ),
      div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
        span(cls := "text-xs font-semibold text-emerald-300")("✓"),
        span(cls := "text-xs font-semibold text-emerald-300")(stats.completed.toString),
        span(cls := "text-xs text-slate-400")("completed"),
      ),
      div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
        span(cls := "text-xs font-semibold text-purple-300")("⚡"),
        span(cls := "text-xs font-semibold text-purple-300")(tokensLabel),
        span(cls := "text-xs text-slate-400")("tokens"),
      ),
    ).render

  private def hasSignal(r: IssueWorkReport): Boolean =
    r.walkthrough.isDefined ||
    r.agentSummary.isDefined ||
    r.diffStats.isDefined ||
    r.prLink.isDefined ||
    r.ciStatus.isDefined ||
    r.tokenUsage.isDefined ||
    r.reports.nonEmpty ||
    r.artifacts.nonEmpty
