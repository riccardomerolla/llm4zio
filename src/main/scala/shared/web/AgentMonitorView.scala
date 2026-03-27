package shared.web

import llm4zio.observability.MetricsSnapshot
import orchestration.control.{ AgentExecutionInfo, AgentExecutionState, AgentMonitorSnapshot }
import scalatags.Text.all.*

/** Renders the Agent Monitor as a Symphony-style dark terminal table.
  *
  * Column order: ID | STAGE | PID | AGE | TOKENS | SESSION | EVENT
  */
object AgentMonitorView:

  /** Aggregated global stats for the header bar. */
  final case class AgentGlobalStats(
    activeAgents: Int,
    maxAgents: Int,
    runtimeSeconds: Long,
    tokensIn: Long,
    tokensOut: Long,
    tokensTotal: Long,
  )

  object AgentGlobalStats:
    def fromSnapshotAndMetrics(snapshot: AgentMonitorSnapshot, llmMetrics: MetricsSnapshot): AgentGlobalStats =
      val active = snapshot.agents.count(a =>
        a.state == AgentExecutionState.Executing || a.state == AgentExecutionState.WaitingForTool
      )
      AgentGlobalStats(
        activeAgents = active,
        maxAgents = snapshot.agents.size max active,
        runtimeSeconds = llmMetrics.totalLatencyMs / 1000,
        tokensIn = llmMetrics.promptTokens,
        tokensOut = llmMetrics.completionTokens,
        tokensTotal = llmMetrics.totalTokens,
      )

    val empty: AgentGlobalStats = AgentGlobalStats(0, 0, 0L, 0L, 0L, 0L)

  /** View model for a single agent run row. */
  final case class AgentRunView(
    issueId: String,
    stage: String,
    pid: Option[Int],
    ageSeconds: Long,
    tokensTotal: Long,
    sessionId: Option[String],
    lastEvent: String,
    reviewHref: Option[String] = None,
  )

  def fromSnapshot(snapshot: AgentMonitorSnapshot): List[AgentRunView] =
    snapshot.agents.map(fromInfo(_, snapshot.generatedAt))

  def fromInfo(info: AgentExecutionInfo, now: java.time.Instant): AgentRunView =
    val ageSeconds = info.startedAt
      .map(s => java.time.Duration.between(s, now).getSeconds.max(0L))
      .getOrElse(0L)
    AgentRunView(
      issueId = info.runId.getOrElse("—"),
      stage = stageName(info.state),
      pid = None,
      ageSeconds = ageSeconds,
      tokensTotal = info.tokensUsed,
      sessionId = info.conversationId,
      lastEvent = info.message.getOrElse("—"),
      reviewHref = info.runId.filter(_.trim.nonEmpty).map(runId => s"/checkpoints/$runId"),
    )

  private def stageName(state: AgentExecutionState): String = state match
    case AgentExecutionState.Idle           => "IDLE"
    case AgentExecutionState.Executing      => "EXEC"
    case AgentExecutionState.WaitingForTool => "TOOL"
    case AgentExecutionState.Paused         => "PAUSED"
    case AgentExecutionState.Aborted        => "ABORT"
    case AgentExecutionState.Failed         => "FAIL"

  private def stageDotCls(stage: String): String = stage match
    case "EXEC"   => "bg-emerald-400"
    case "TOOL"   => "bg-amber-400"
    case "PAUSED" => "bg-sky-400"
    case "ABORT"  => "bg-rose-500"
    case "FAIL"   => "bg-rose-500"
    case _        => "bg-slate-500"

  private def formatTokens(n: Long): String =
    val s = n.toString
    s.reverse.grouped(3).mkString(",").reverse

  private def truncate(s: String, max: Int = 60): String =
    if s.length <= max then s else s.take(max - 1) + "…"

  private val headerCls = "px-3 py-2 text-left text-xs font-semibold tracking-widest text-slate-400 uppercase"
  private val cellCls   = "px-3 py-2 text-xs font-mono text-slate-300 align-top"

  def tableFragment(rows: List[AgentRunView]): Frag =
    val content: Frag =
      if rows.isEmpty then
        tr(
          td(
            colspan := "7",
            cls     := "px-3 py-8 text-center text-xs text-slate-500",
          )("No active agent runs")
        )
      else
        frag(rows.map(row)*)

    div(
      cls                      := "overflow-x-auto rounded-lg",
      attr("data-agent-table") := "true",
    )(
      tag("table")(cls := "w-full border-collapse bg-black/80 font-mono text-xs")(
        tag("thead")(
          tag("tr")(cls := "border-b border-white/10")(
            tag("th")(cls := headerCls)("ID"),
            tag("th")(cls := headerCls)("STAGE"),
            tag("th")(cls := headerCls)("PID"),
            tag("th")(cls := headerCls)("AGE"),
            tag("th")(cls := headerCls)("TOKENS"),
            tag("th")(cls := headerCls)("SESSION"),
            tag("th")(cls := headerCls)("EVENT"),
          )
        ),
        tag("tbody")(content),
      )
    )

  def table(rows: List[AgentRunView]): String =
    tableFragment(rows).render

  private def row(r: AgentRunView): Frag =
    val dotCls = stageDotCls(r.stage)
    tag("tr")(
      cls := s"border-b border-white/5 hover:bg-white/[0.03] ${
          if r.reviewHref.isDefined then "cursor-pointer" else ""
        }",
      r.reviewHref.map(href => onclick := s"window.location='$href'").getOrElse(()),
    )(
      tag("td")(cls := cellCls)(
        span(cls := "text-slate-400")(r.issueId.take(12))
      ),
      tag("td")(cls := cellCls)(
        div(cls := "flex items-center gap-1.5")(
          span(cls := s"inline-block h-1.5 w-1.5 rounded-full $dotCls"),
          span(r.stage),
        )
      ),
      tag("td")(cls := cellCls)(
        r.pid.map(p => span(p.toString)).getOrElse(span(cls := "text-slate-600")("—"))
      ),
      tag("td")(cls := cellCls)(
        span(formatAge(r.ageSeconds))
      ),
      tag("td")(cls := s"$cellCls text-right tabular-nums")(
        span(formatTokens(r.tokensTotal))
      ),
      tag("td")(cls := cellCls)(
        r.sessionId.map(s => span(cls := "text-slate-400")(s.take(8))).getOrElse(span(cls := "text-slate-600")("—"))
      ),
      tag("td")(cls := cellCls)(
        span(cls := "text-slate-300")(truncate(r.lastEvent))
      ),
    )

  private def formatAge(seconds: Long): String =
    if seconds < 60 then s"${seconds}s"
    else if seconds < 3600 then s"${seconds / 60}m"
    else s"${seconds / 3600}h"

  private def formatRuntime(seconds: Long): String =
    val m = seconds / 60
    val s = seconds % 60
    if m == 0 then s"${s}s"
    else s"${m}m ${s}s"

  def statsHeaderFragment(stats: AgentGlobalStats): Frag =
    div(
      cls                      := "grid grid-cols-2 gap-3 sm:grid-cols-4",
      attr("data-agent-stats") := "true",
    )(
      tag("ab-stat-card")(attr("label") := "Agents", attr("value")     := s"${stats.activeAgents} / ${stats.maxAgents}"),
      tag("ab-stat-card")(attr("label") := "Tokens in", attr("value")  := formatTokens(stats.tokensIn)),
      tag("ab-stat-card")(attr("label") := "Tokens out", attr("value") := formatTokens(stats.tokensOut)),
      tag("ab-stat-card")(attr("label") := "Runtime", attr("value")    := formatRuntime(stats.runtimeSeconds)),
    )

  def statsHeader(stats: AgentGlobalStats): String =
    statsHeaderFragment(stats).render
