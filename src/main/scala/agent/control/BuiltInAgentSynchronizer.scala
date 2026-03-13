package agent.control

import java.time.Duration

import zio.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import orchestration.control.AgentRegistry
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId

object BuiltInAgentSynchronizer:

  def sync: ZIO[AgentRepository, PersistenceError, Unit] =
    for
      repository    <- ZIO.service[AgentRepository]
      existing      <- repository.list(includeDeleted = true)
      existingByName = existing.map(agent => agent.name.trim.toLowerCase -> agent).toMap
      now           <- Clock.instant
      seeded         = seedBuiltInAgents(now)
      toCreate       = seeded.filterNot(agent => existingByName.contains(agent.name.trim.toLowerCase))
      toUpdate       = seeded.flatMap { desired =>
                         existingByName
                           .get(desired.name.trim.toLowerCase)
                           .filter(existingAgent => needsSync(existingAgent, desired))
                           .map(existingAgent => existingAgent -> desired)
                       }
      _             <- ZIO.foreachDiscard(toCreate)(agent => repository.append(AgentEvent.Created(agent, now)))
      _             <- ZIO.foreachDiscard(toUpdate) { (existingAgent, desiredAgent) =>
                         repository.append(
                           AgentEvent.Updated(
                             desiredAgent.copy(
                               id = existingAgent.id,
                               enabled = existingAgent.enabled,
                               createdAt = existingAgent.createdAt,
                               updatedAt = now,
                               deletedAt = existingAgent.deletedAt,
                             ),
                             now,
                           )
                         )
                       }
    yield ()

  def seedBuiltInAgents(now: java.time.Instant): List[Agent] =
    AgentRegistry.builtInAgents.map { info =>
      Agent(
        id = AgentId.generate,
        name = info.name,
        description = info.description,
        cliTool = inferCliTool(info.name),
        capabilities = info.tags,
        defaultModel = None,
        systemPrompt = None,
        maxConcurrentRuns = 1,
        envVars = Map.empty,
        timeout = Duration.ofMinutes(30),
        enabled = true,
        createdAt = now,
        updatedAt = now,
      )
    }

  private def inferCliTool(name: String): String =
    val lower = name.trim.toLowerCase
    if lower.contains("claude") then "claude"
    else if lower.contains("opencode") then "opencode"
    else if lower.contains("gemini") then "gemini"
    else if lower.contains("codex") then "codex"
    else "gemini"

  private def needsSync(existing: Agent, desired: Agent): Boolean =
    existing.description != desired.description ||
    existing.cliTool != desired.cliTool ||
    existing.capabilities != desired.capabilities ||
    existing.defaultModel != desired.defaultModel ||
    existing.systemPrompt != desired.systemPrompt ||
    existing.maxConcurrentRuns != desired.maxConcurrentRuns ||
    existing.envVars != desired.envVars ||
    existing.dockerMemoryLimit != desired.dockerMemoryLimit ||
    existing.dockerCpuLimit != desired.dockerCpuLimit ||
    existing.timeout != desired.timeout
