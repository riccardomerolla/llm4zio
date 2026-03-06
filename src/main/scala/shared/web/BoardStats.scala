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
    val completed = issues.count(_.status == IssueStatus.Completed)
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
    div(
      cls                      := "flex flex-wrap items-center gap-4 rounded-xl border border-white/10 bg-slate-900/60 px-4 py-2 text-xs text-slate-300",
      attr("data-board-stats") := "true",
    )(
      span(
        span(cls := "font-semibold text-emerald-300")(stats.running.toString),
        " running",
      ),
      span(
        span(cls := "font-semibold text-indigo-300")(stats.completed.toString),
        " completed",
      ),
      span(
        span(cls := "font-semibold text-slate-200")(s"${stats.tokensTotal / 1000}k"),
        " tokens",
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
