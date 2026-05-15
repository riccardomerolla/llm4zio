package agent.control

import agent.entity.{ Agent, EmployeeRole }
import issues.entity.TicketLane
import workspace.entity.{ RunStatus, WorkspaceRun }

final case class AgentMatchResult(
  agent: Agent,
  score: Double,
  overlapCount: Int,
  requiredCount: Int,
  activeRuns: Int,
)

object AgentMatching:

  /** Phase 2 (R4): role-and-occupancy picker that supersedes capability
    * matching. Picks the enabled employee whose role matches the lane and
    * who has occupancy=0 (one-in-flight invariant from Q8). Falls back to a
    * Custom employee if no role match is enabled and available.
    *
    * Returns None if every role-matched employee is busy — caller should
    * leave the ticket on the board until an employee frees up.
    */
  def pickEmployeeForLane(
    lane: TicketLane,
    agents: List[Agent],
    activeRunsByAgent: Map[String, Int],
  ): Option[Agent] =
    val targetRoles = rolesForLane(lane)
    val available   = agents
      .filter(_.enabled)
      .filter(a => activeRunsByAgent.getOrElse(a.name.trim.toLowerCase, 0) < a.maxConcurrentRuns)
    available
      .find(a => targetRoles.contains(a.role))
      .orElse(available.find(_.role == EmployeeRole.Custom))

  /** Role(s) that handle a given lane. A single role per lane in the default
    * cast; the function returns a Set to keep room for future overlap.
    */
  def rolesForLane(lane: TicketLane): Set[EmployeeRole] =
    lane match
      case TicketLane.Frontend => Set(EmployeeRole.FrontendEng)
      case TicketLane.Backend  => Set(EmployeeRole.BackendEng)
      case TicketLane.Testing  => Set(EmployeeRole.QA)
      case TicketLane.Triage   => Set(EmployeeRole.PM)
      case TicketLane.Review   => Set(EmployeeRole.Reviewer)
      case TicketLane.Custom   => Set(EmployeeRole.Custom)

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
