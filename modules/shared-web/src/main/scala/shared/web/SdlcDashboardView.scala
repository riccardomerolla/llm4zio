package shared.web

import java.time.Instant
import java.time.temporal.ChronoUnit

import activity.boundary.ActivityView
import activity.entity.ActivityEvent
import scalatags.Text.all.*
import sdlc.entity.*

object SdlcDashboardView:

  def page(snapshot: SdlcSnapshot): String =
    Layout.page("SDLC Dashboard", "/sdlc")(
      div(cls := "space-y-4")(
        header(snapshot),
        div(
          id                 := "sdlc-dashboard-fragment",
          attr("hx-get")     := "/sdlc/fragment",
          attr("hx-trigger") := "load, every 10s",
          attr("hx-swap")    := "innerHTML",
        )(raw(fragment(snapshot))),
      )
    )

  def fragment(snapshot: SdlcSnapshot): String =
    div(cls := "space-y-4")(
      overview(snapshot),
      div(cls := "grid gap-4 xl:grid-cols-3")(
        governance(snapshot.governance),
        daemonHealth(snapshot.daemonHealth),
        evolution(snapshot.evolution),
      ),
      lifecycle(snapshot),
      div(cls := "grid gap-4 xl:grid-cols-2")(
        churn(snapshot),
        stoppages(snapshot),
      ),
      div(cls := "grid gap-4 xl:grid-cols-2")(
        escalations(snapshot),
        agentPerformance(snapshot),
      ),
      recentActivity(snapshot.recentActivity),
    ).render

  private def header(snapshot: SdlcSnapshot): Frag =
    sectionPanel(
      "SDLC Dashboard",
      s"Derived from issue and activity history at ${formatInstant(snapshot.generatedAt)}",
    )(
      div(cls := "grid gap-3 sm:grid-cols-2 xl:grid-cols-4")(
        statCard(
          "Specifications",
          snapshot.specificationCount.toString,
          "Total tracked specifications",
          snapshot.specificationTrend,
        ),
        statCard("Plans", snapshot.planCount.toString, "Persisted execution plans", snapshot.planTrend),
        statCard("Issues", snapshot.issueCount.toString, "Tracked delivery work items", snapshot.issueTrend),
        statCard(
          "Pending Decisions",
          snapshot.pendingDecisionCount.toString,
          "Open review or escalation decisions",
          snapshot.pendingDecisionTrend,
        ),
      )
    )

  private def overview(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Thresholds", "Live operating thresholds loaded from configuration")(
      div(cls := "grid gap-3 sm:grid-cols-2 xl:grid-cols-3")(
        thresholdLine(
          "Churn",
          s"${snapshot.thresholds.churnTransitions}+ transitions or ${snapshot.thresholds.churnBounces}+ bounces",
        ),
        thresholdLine("Stalled", s"${snapshot.thresholds.stalledHours}h without movement"),
        thresholdLine("Blocked", s"${snapshot.thresholds.blockedHours}h with dependencies"),
        thresholdLine("Review", s"${snapshot.thresholds.reviewHours}h in human review"),
        thresholdLine("Decision Aging", s"${snapshot.thresholds.decisionHours}h for pending decisions"),
      )
    )

  private def lifecycle(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Lifecycle", "Idea to done flow stitched across specifications, plans, and issue states")(
      div(cls := "grid gap-3 md:grid-cols-2 xl:grid-cols-4")(
        snapshot.lifecycle.map { stage =>
          Components.interactiveCard(stage.href)(
            div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(stage.label),
            div(cls := "mt-2 text-3xl font-semibold text-white")(stage.count.toString),
            p(cls := "mt-2 text-xs text-slate-400")(stage.description),
          )
        }
      )
    )

  private def churn(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Churn Detection", "Issues with abnormal transition volume or repeated bouncing")(
      if snapshot.churnAlerts.isEmpty then emptyState("No churn alerts above the configured threshold.")
      else
        table(
          cls := "min-w-full divide-y divide-white/10 text-sm"
        )(
          thead(cls := "text-left text-xs uppercase tracking-wide text-slate-400")(
            tr(th("Issue"), th("State"), th("Transitions"), th("Bounces"), th("Last Change"))
          ),
          tbody(cls := "divide-y divide-white/5")(
            snapshot.churnAlerts.map { alert =>
              tr(
                td(cls := "py-2 pr-3")(
                  a(
                    href := s"/issues/${alert.issueId}",
                    cls  := "font-medium text-sky-300 hover:text-sky-200",
                  )(s"#${alert.issueId}"),
                  div(cls := "text-xs text-slate-400")(alert.title),
                ),
                td(cls := "py-2 pr-3 text-slate-300")(alert.currentState),
                td(cls := "py-2 pr-3 text-white")(alert.transitionCount.toString),
                td(cls := "py-2 pr-3 text-white")(alert.bounceCount.toString),
                td(cls := "py-2 text-slate-400")(formatInstant(alert.lastChangedAt)),
              )
            }
          ),
        )
    )

  private def stoppages(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Stoppages", "Items stalled in place or blocked by unresolved dependencies")(
      if snapshot.stoppages.isEmpty then emptyState("No stalled or blocked items over threshold.")
      else
        div(cls := "space-y-2")(
          snapshot.stoppages.map { alert =>
            div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-3")(
              div(cls := "flex items-start justify-between gap-3")(
                div(
                  a(
                    href := s"/issues/${alert.issueId}",
                    cls  := "font-medium text-white hover:text-sky-200",
                  )(s"#${alert.issueId} ${alert.title}"),
                  p(cls := "mt-1 text-xs text-slate-400")(s"${alert.currentState} for ${alert.ageHours}h"),
                ),
                span(cls := badgeClass(alert.kind))(alert.kind),
              ),
              Option.when(alert.blockedBy.nonEmpty)(
                p(cls := "mt-2 text-xs text-slate-400")(s"Blocked by: ${alert.blockedBy.mkString(", ")}")
              ),
            )
          }
        )
    )

  private def escalations(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Escalations", "Pending human review and decision queues ranked by urgency and age")(
      if snapshot.escalations.isEmpty then emptyState("No review or decision escalations need attention.")
      else
        div(cls := "space-y-2")(
          snapshot.escalations.map { alert =>
            val destination =
              if alert.kind == "Decision" then s"/decisions/${alert.referenceId}" else s"/issues/${alert.referenceId}"
            div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-3")(
              div(cls := "flex items-start justify-between gap-3")(
                div(
                  a(href := destination, cls := "font-medium text-white hover:text-sky-200")(alert.title),
                  p(cls := "mt-1 text-xs text-slate-400")(alert.summary),
                ),
                div(cls := "text-right")(
                  span(cls := urgencyBadgeClass(alert.urgency))(alert.urgency),
                  p(cls := "mt-1 text-xs text-slate-400")(s"${alert.ageHours}h"),
                ),
              )
            )
          }
        )
    )

  private def agentPerformance(snapshot: SdlcSnapshot): Frag =
    sectionPanel("Agent Performance", "Throughput, quality, cycle time, and token-based cost rollup")(
      if snapshot.agentPerformance.isEmpty then emptyState("No agent performance data is available yet.")
      else
        table(
          cls := "min-w-full divide-y divide-white/10 text-sm"
        )(
          thead(cls := "text-left text-xs uppercase tracking-wide text-slate-400")(
            tr(th("Agent"), th("Throughput"), th("Success"), th("Avg Cycle"), th("Active"), th("Cost"))
          ),
          tbody(cls := "divide-y divide-white/5")(
            snapshot.agentPerformance.map { metric =>
              tr(
                td(cls := "py-2 pr-3 font-medium text-white")(metric.agentName),
                td(cls := "py-2 pr-3 text-white")(metric.throughput.toString),
                td(cls := "py-2 pr-3 text-slate-300")(s"${formatPercent(metric.successRate)}%"),
                td(cls := "py-2 pr-3 text-slate-300")(s"${formatHours(metric.averageCycleHours)}h"),
                td(cls := "py-2 pr-3 text-slate-300")(metric.activeIssues.toString),
                td(cls := "py-2 text-slate-300")(f"$$${metric.costUsd}%.4f"),
              )
            }
          ),
        )
    )

  private def governance(governance: GovernanceOverview): Frag =
    sectionPanel("Governance", "Policy activity and validation outcomes across tracked plans")(
      div(cls := "grid gap-3 sm:grid-cols-2")(
        metricTile(
          "Pass Rate",
          s"${formatPercent(governance.passRate)}%",
          s"${governance.passCount} pass / ${governance.failCount} fail",
        ),
        metricTile("Active Policies", governance.activePolicyCount.toString, "Policies currently in force"),
      )
    )

  private def daemonHealth(daemonHealth: DaemonHealthOverview): Frag =
    sectionPanel("Daemon Health", "Runtime posture for ADE daemon agents")(
      div(cls := "grid gap-3 sm:grid-cols-3")(
        metricTile("Running", daemonHealth.runningCount.toString, "Enabled daemons ready to execute"),
        metricTile("Stopped", daemonHealth.stoppedCount.toString, "Disabled or explicitly stopped daemons"),
        metricTile("Errored", daemonHealth.erroredCount.toString, "Daemons with degraded health or runtime errors"),
      )
    )

  private def evolution(evolution: EvolutionOverview): Frag =
    sectionPanel("Evolution", "Proposal backlog and the latest applied system changes")(
      div(cls := "space-y-3")(
        metricTile("Pending Proposals", evolution.pendingProposalCount.toString, "Awaiting approval or application"),
        if evolution.recentlyApplied.isEmpty then emptyState("No applied evolutions have been recorded yet.")
        else
          div(cls := "space-y-2")(
            evolution.recentlyApplied.map { proposal =>
              div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-3")(
                div(cls := "flex items-start justify-between gap-3")(
                  div(
                    p(cls := "font-medium text-white")(proposal.title),
                    p(cls := "mt-1 text-xs text-slate-400")(s"#${proposal.proposalId}"),
                  ),
                  div(cls := "text-right")(
                    span(
                      cls := "rounded-md border border-emerald-400/30 bg-emerald-500/10 px-2 py-1 text-xs font-semibold uppercase tracking-wide text-emerald-200"
                    )(
                      proposal.status
                    ),
                    p(cls := "mt-1 text-xs text-slate-400")(formatInstant(proposal.appliedAt)),
                  ),
                )
              )
            }
          ),
      )
    )

  private def recentActivity(events: List[ActivityEvent]): Frag =
    sectionPanel("Recent Activity", "Latest activity events supporting the SDLC view")(
      if events.isEmpty then emptyState("No recent activity events are available.")
      else div(cls := "space-y-3")(events.map(ActivityView.eventCard))
    )

  private def statCard(
    titleText: String,
    value: String,
    detail: String,
    trend: TrendIndicator,
  ): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4")(
      div(cls := "flex items-start justify-between gap-3")(
        div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(titleText),
        trendPill(trend),
      ),
      div(cls := "mt-2 text-3xl font-semibold text-white")(value),
      p(cls := "mt-2 text-xs text-slate-400")(detail),
      p(cls := "mt-2 text-xs text-slate-500")(trendSummary(trend)),
    )

  private def thresholdLine(titleText: String, detail: String): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-950/60 px-3 py-2")(
      div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(titleText),
      div(cls := "mt-1 text-sm text-slate-200")(detail),
    )

  private def sectionPanel(titleText: String, subtitle: String)(content: Frag): Frag =
    tag("section")(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
      div(cls := "mb-3")(
        h2(cls := "text-base font-semibold text-white")(titleText),
        p(cls := "mt-1 text-xs text-slate-400")(subtitle),
      ),
      content,
    )

  private def emptyState(message: String): Frag =
    div(cls := "rounded-lg border border-dashed border-white/10 bg-slate-950/40 p-4 text-sm text-slate-400")(message)

  private def metricTile(titleText: String, value: String, detail: String): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-3")(
      div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(titleText),
      div(cls := "mt-2 text-2xl font-semibold text-white")(value),
      p(cls := "mt-2 text-xs text-slate-400")(detail),
    )

  private def trendPill(trend: TrendIndicator): Frag =
    span(cls := s"rounded-md border px-2 py-1 text-xs font-semibold ${trendTone(trend.direction)}")(
      s"${trendArrow(trend.direction)} ${trend.direction.toString.toLowerCase}"
    )

  private def trendArrow(direction: TrendDirection): String =
    direction match
      case TrendDirection.Up   => "↑"
      case TrendDirection.Down => "↓"
      case TrendDirection.Flat => "→"

  private def trendTone(direction: TrendDirection): String =
    direction match
      case TrendDirection.Up   => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case TrendDirection.Down => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case TrendDirection.Flat => "border-white/15 bg-white/5 text-slate-200"

  private def trendSummary(trend: TrendIndicator): String =
    s"${trend.currentPeriodCount} vs ${trend.previousPeriodCount} in the last ${trend.periodLabel}"

  private def badgeClass(kind: String): String =
    val tone =
      if kind.equalsIgnoreCase("blocked") then "border-amber-400/30 bg-amber-500/10 text-amber-200"
      else "border-rose-400/30 bg-rose-500/10 text-rose-200"
    s"rounded-md border px-2 py-1 text-xs font-semibold uppercase tracking-wide $tone"

  private def urgencyBadgeClass(urgency: String): String =
    val tone = urgency.toLowerCase match
      case "critical" => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case "high"     => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case "medium"   => "border-sky-400/30 bg-sky-500/10 text-sky-200"
      case _          => "border-white/15 bg-white/5 text-slate-200"
    s"rounded-md border px-2 py-1 text-xs font-semibold uppercase tracking-wide $tone"

  private def formatInstant(value: Instant): String =
    value.truncatedTo(ChronoUnit.SECONDS).toString

  private def formatPercent(value: Double): String =
    f"${value * 100.0d}%.1f"

  private def formatHours(value: Double): String =
    f"$value%.1f"
