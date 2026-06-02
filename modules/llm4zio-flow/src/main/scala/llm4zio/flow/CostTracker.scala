package llm4zio.flow

import zio.*

import llm4zio.core.TokenUsage

/** Accumulates [[FlowEvent.TokensUsed]] events per-agent and per-model and renders the orca-style footer. Cost is an
  * estimate from [[PriceList]] (flagged `*`); token counts are exact. Only streamed calls emit usage today, so the
  * footer reflects the coder's streamed usage (planner/reviewer use structured output, which exposes no usage).
  */
final class CostTracker private (
  byAgent: Ref[Map[String, TokenUsage]],
  byModel: Ref[Map[String, TokenUsage]],
):
  def record(event: FlowEvent): UIO[Unit] = event match
    case FlowEvent.TokensUsed(agent, model, usage) =>
      byAgent.update(add(_, agent, usage)) *>
        byModel.update(add(_, model.getOrElse("(unknown)"), usage))
    case _                                         => ZIO.unit

  /** Fork a subscriber that records every event from `hub` until the scope closes. */
  def consume(hub: FlowEvents.Hub): ZIO[Scope, Nothing, Unit] =
    hub.stream.foreach(record).forkScoped.unit

  val summary: UIO[String] =
    for
      agents <- byAgent.get
      models <- byModel.get
    yield render(agents, models)

  private def add(m: Map[String, TokenUsage], k: String, u: TokenUsage): Map[String, TokenUsage] =
    val prev   = m.getOrElse(k, TokenUsage(0, 0, 0))
    val cached = (prev.cached.toList ++ u.cached.toList).reduceOption(_ + _)
    m.updated(k, TokenUsage(prev.prompt + u.prompt, prev.completion + u.completion, prev.total + u.total, cached))

  private def tokenLine(label: String, cost: Option[Double], u: TokenUsage): String =
    val cached = u.cached.filter(_ > 0).fold("")(c => s" ($c cached)")
    val money  = cost.map(c => f" ($$$c%.4f*)").getOrElse("")
    f"  $label: ${u.prompt} in$cached, ${u.completion} out$money"

  private def render(agents: Map[String, TokenUsage], models: Map[String, TokenUsage]): String =
    val agentLines   = agents.toList.sortBy(_._1).map { case (a, u) => tokenLine(a, None, u) }
    val modelEntries = models.toList.sortBy(_._1).map {
      case (m, u) =>
        val cost = if m == "(unknown)" then None else PriceList.costUsd(m, u)
        (tokenLine(m, cost, u), cost)
    }
    val costs        = modelEntries.flatMap(_._2)
    val total        = costs.sum
    val totalStr     = if total > 0 then f"$$$total%.4f" else "$0.00"
    val footnote     =
      if total > 0 then "\n\n* estimated from the pricing table (rates as of 2026-06 — may be stale)" else ""
    s"By agent:\n${agentLines.mkString("\n")}\n\nBy model:\n${modelEntries.map(_._1).mkString("\n")}" +
      s"\n\nTotal: $totalStr$footnote"

object CostTracker:
  val make: UIO[CostTracker] =
    for
      a <- Ref.make(Map.empty[String, TokenUsage])
      m <- Ref.make(Map.empty[String, TokenUsage])
    yield new CostTracker(a, m)
