package llm4zio.flow

import zio.*
import zio.stream.ZStream

import llm4zio.core.TokenUsage

/** Accumulates [[FlowEvent.TokensUsed]] events per-agent and per-model and renders the orca-style footer. Cost is an
  * estimate from [[PriceList]] (flagged `*`); token counts are exact. Usage is captured from both streamed coder turns
  * and structured planner/reviewer calls (via `executeStructuredWithUsage`), so the footer covers the whole flow for
  * backends that surface usage (gemini/codex; claude's non-stream structured path reports none).
  *
  * Stage attribution: the tracker also watches [[FlowEvent.StageStarted]]/`StageCompleted`/`StageFailed` on the same
  * (ordered) event hub and attributes each usage to the innermost open stage — `"(no stage)"` outside any — exposing
  * the per-(stage, agent, model) [[CostCell]]s the cost ledger persists.
  */
final class CostTracker private (
  byAgent: Ref[Map[String, TokenUsage]],
  byModel: Ref[Map[String, TokenUsage]],
  byCell: Ref[Map[(String, String, String), TokenUsage]],
  openStages: Ref[List[String]],
):
  def record(event: FlowEvent): UIO[Unit] = event match
    case FlowEvent.TokensUsed(agent, model, usage) =>
      val modelKey = model.getOrElse("(unknown)")
      byAgent.update(add(_, agent, usage)) *>
        byModel.update(add(_, modelKey, usage)) *>
        openStages.get.flatMap(stack =>
          byCell.update(m =>
            val key = (stack.headOption.getOrElse("(no stage)"), agent, modelKey)
            m.updated(key, merge(m.get(key), usage))
          )
        )
    case FlowEvent.StageStarted(name)              => openStages.update(name :: _)
    case _: FlowEvent.StageCompleted               => openStages.update(_.drop(1))
    case _: FlowEvent.StageFailed                  => openStages.update(_.drop(1))
    case _                                         => ZIO.unit

  /** The per-(stage, agent, model) roll-ups accumulated so far, with per-cell cost estimates, deterministically
    * ordered.
    */
  val cells: UIO[List[CostCell]] =
    byCell.get.map { m =>
      m.toList
        .sortBy { case ((stage, agent, model), _) => (stage, agent, model) }
        .map {
          case ((stage, agent, model), u) =>
            CostCell(
              stage = stage,
              agent = agent,
              model = model,
              prompt = u.prompt.toLong,
              completion = u.completion.toLong,
              cached = u.cached.map(_.toLong),
              total = u.total.toLong,
              costUsd = if model == "(unknown)" then None else PriceList.costUsd(model, u),
            )
        }
    }

  /** Fork a subscriber that records every event from `hub` until the scope closes. Subscribes synchronously before
    * forking so no event published after the call is missed (a lazy `ZStream.fromHub` would race with early publishes).
    */
  def consume(hub: FlowEvents.Hub): ZIO[Scope, Nothing, Unit] =
    for
      queue <- hub.subscribe
      _     <- ZStream.fromQueue(queue).foreach(record).forkScoped
    yield ()

  val summary: UIO[String] =
    for
      agents <- byAgent.get
      models <- byModel.get
    yield render(agents, models)

  private def merge(prev: Option[TokenUsage], u: TokenUsage): TokenUsage =
    val p      = prev.getOrElse(TokenUsage(0, 0, 0))
    val cached = (p.cached.toList ++ u.cached.toList).reduceOption(_ + _)
    TokenUsage(p.prompt + u.prompt, p.completion + u.completion, p.total + u.total, cached)

  private def add(m: Map[String, TokenUsage], k: String, u: TokenUsage): Map[String, TokenUsage] =
    m.updated(k, merge(m.get(k), u))

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
      if total > 0 then s"\n\n* estimated from the pricing table (rates as of ${PriceList.asOf} — may be stale)"
      else ""
    s"By agent:\n${agentLines.mkString("\n")}\n\nBy model:\n${modelEntries.map(_._1).mkString("\n")}" +
      s"\n\nTotal: $totalStr$footnote"

object CostTracker:
  val make: UIO[CostTracker] =
    for
      a <- Ref.make(Map.empty[String, TokenUsage])
      m <- Ref.make(Map.empty[String, TokenUsage])
      c <- Ref.make(Map.empty[(String, String, String), TokenUsage])
      s <- Ref.make(List.empty[String])
    yield new CostTracker(a, m, c, s)
