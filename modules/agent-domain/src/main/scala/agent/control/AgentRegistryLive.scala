package agent.control

import zio.*

import _root_.config.entity.*
import agent.entity.AgentRegistry
import shared.entity.TaskStep

object AgentRegistryLive:

  val live: ZLayer[Any, Nothing, AgentRegistry] = ZLayer {
    for
      agentsRef <- Ref.Synchronized.make[Map[String, AgentInfo]](
                     AgentRegistry.builtInAgents.map(a => a.name.toLowerCase -> a).toMap
                   )
    yield new AgentRegistryLiveImpl(agentsRef)
  }

final private[agent] class AgentRegistryLiveImpl(
  agents: Ref.Synchronized[Map[String, AgentInfo]]
) extends AgentRegistry:

  override def registerAgent(request: RegisterAgentRequest): UIO[AgentInfo] =
    val agentInfo = AgentInfo(
      name = request.name,
      handle = request.handle.getOrElse(AgentRegistry.sanitizeHandle(request.name)),
      displayName = request.displayName,
      description = request.description,
      agentType = request.agentType,
      usesAI = request.usesAI,
      tags = request.tags,
      skills = request.skills,
      supportedSteps = request.supportedSteps,
      version = request.version,
      metrics = AgentMetrics(),
      health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
    )
    agents.update(_ + (request.name.toLowerCase -> agentInfo)).as(agentInfo)

  override def findByName(name: String): UIO[Option[AgentInfo]] =
    agents.get.map(_.get(name.trim.toLowerCase))

  override def findAgents(query: AgentQuery): UIO[List[AgentInfo]] =
    agents.get.map { allAgents =>
      allAgents.values.toList.filter { agent =>
        val matchSkill       = query.skill.forall(s => agent.skills.exists(_.skill == s))
        val matchInputType   =
          query.inputType.forall(it => agent.skills.exists(_.inputTypes.contains(it)))
        val matchOutputType  =
          query.outputType.forall(ot => agent.skills.exists(_.outputTypes.contains(ot)))
        val matchStep        = query.supportedStep.forall(agent.supportedSteps.contains)
        val matchSuccessRate =
          query.minSuccessRate.forall(msr => agent.metrics.successRate >= msr)
        val matchEnabled     = !query.onlyEnabled || agent.health.isEnabled

        matchSkill && matchInputType && matchOutputType && matchStep && matchSuccessRate && matchEnabled
      }
    }

  override def getAllAgents: UIO[List[AgentInfo]] =
    agents.get.map(_.values.toList.sortBy(_.name))

  override def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(skill = Some(skill)))

  override def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(supportedStep = Some(step)))

  override def findAgentsForTransformation(inputType: String, outputType: String): UIO[List[AgentInfo]] =
    findAgents(AgentQuery(inputType = Some(inputType), outputType = Some(outputType)))

  override def recordInvocation(agentName: String, success: Boolean, latencyMs: Long): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- agents.updateZIO { allAgents =>
               allAgents.get(agentName.toLowerCase) match
                 case None        => ZIO.succeed(allAgents)
                 case Some(agent) =>
                   val updated = agent.copy(metrics = agent.metrics.recordInvocation(success, latencyMs, now))
                   ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
             }
    yield ()

  override def updateHealth(agentName: String, success: Boolean, message: Option[String]): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- agents.updateZIO { allAgents =>
               allAgents.get(agentName.toLowerCase) match
                 case None        => ZIO.succeed(allAgents)
                 case Some(agent) =>
                   val updated =
                     if success then agent.copy(health = agent.health.recordSuccess(now))
                     else agent.copy(health = agent.health.recordFailure(now, message.getOrElse("Unknown error")))
                   ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
             }
    yield ()

  override def setAgentEnabled(agentName: String, enabled: Boolean): UIO[Unit] =
    agents.updateZIO { allAgents =>
      allAgents.get(agentName.toLowerCase) match
        case None        => ZIO.succeed(allAgents)
        case Some(agent) =>
          val updated =
            if enabled then agent.copy(health = agent.health.enable())
            else agent.copy(health = agent.health.disable("Manually disabled"))
          ZIO.succeed(allAgents + (agentName.toLowerCase -> updated))
    }

  override def getMetrics(agentName: String): UIO[Option[AgentMetrics]] =
    findByName(agentName).map(_.map(_.metrics))

  override def getHealth(agentName: String): UIO[Option[AgentHealth]] =
    findByName(agentName).map(_.map(_.health))

  override def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int] =
    agents.get.flatMap { _ =>
      val builtInNamesLower = AgentRegistry.builtInAgents.map(_.name.toLowerCase).toSet
      val deduplicated      = customAgents
        .filterNot(agent => builtInNamesLower.contains(agent.name.trim.toLowerCase))
        .groupBy(_.name.trim.toLowerCase)
        .values
        .map(_.head)
        .toList

      ZIO
        .foreach(deduplicated) { customAgent =>
          val agentInfo = AgentRegistry.toCustomAgentInfo(customAgent)
          agents.update(_ + (agentInfo.name.toLowerCase -> agentInfo)).as(1)
        }
        .map(_.sum)
    }

  override def getRankedAgents(query: AgentQuery): UIO[List[AgentInfo]] =
    findAgents(query).map { matchingAgents =>
      matchingAgents.sortBy { agent =>
        val healthScore  = agent.health.status match
          case AgentHealthStatus.Healthy   => 100
          case AgentHealthStatus.Degraded  => 50
          case AgentHealthStatus.Unhealthy => 10
          case AgentHealthStatus.Unknown   => 75
        val successScore = agent.metrics.successRate * 100
        val latencyScore = Math.max(0, 100 - agent.metrics.averageLatencyMs / 1000)
        val enabledScore = if agent.health.isEnabled then 100 else 0
        -(healthScore + successScore + latencyScore + enabledScore)
      }
    }
