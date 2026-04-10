package agent.control

import agent.entity.Agent
import workspace.entity.{ RunStatus, WorkspaceRun }

final case class AgentMatchResult(
  agent: Agent,
  score: Double,
  overlapCount: Int,
  requiredCount: Int,
  activeRuns: Int,
)

object AgentMatching:

  def rankAgents(
    agents: List[Agent],
    requiredCapabilities: List[String],
    activeRunsByAgent: Map[String, Int],
  ): List[AgentMatchResult] =
    val required = requiredCapabilities.map(normalize).filter(_.nonEmpty).distinct
    val ranked   = agents
      .filter(_.enabled)
      .flatMap { agent =>
        val active = activeRunsByAgent.getOrElse(agent.name.trim.toLowerCase, 0)
        if active >= agent.maxConcurrentRuns then None
        else
          val caps    = agent.capabilities.map(normalize).filter(_.nonEmpty).toSet
          val overlap = if required.isEmpty then 0 else required.count(caps.contains)
          val score   =
            if required.isEmpty then 1.0
            else overlap.toDouble / required.size.toDouble
          Some(
            AgentMatchResult(
              agent = agent,
              score = score,
              overlapCount = overlap,
              requiredCount = required.size,
              activeRuns = active,
            )
          )
      }
    ranked.sortBy(r => (-r.score, r.activeRuns, r.agent.name.toLowerCase))

  def activeRunsByAgent(runs: List[WorkspaceRun]): Map[String, Int] =
    runs
      .collect {
        case run
             if run.status == RunStatus.Pending ||
             run.status.isInstanceOf[RunStatus.Running] =>
          run.agentName.trim.toLowerCase
      }
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap

  private def normalize(value: String): String =
    value.trim.toLowerCase
