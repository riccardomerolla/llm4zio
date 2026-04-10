package agent.entity

import java.time.{ Duration, Instant }

import zio.json.*
import zio.schema.{ Schema, derived }

import shared.ids.Ids.AgentId

final case class Agent(
  id: AgentId,
  name: String,
  description: String,
  cliTool: String,
  capabilities: List[String],
  defaultModel: Option[String],
  systemPrompt: Option[String],
  maxConcurrentRuns: Int,
  envVars: Map[String, String],
  dockerMemoryLimit: Option[String] = None,
  dockerCpuLimit: Option[String] = None,
  timeout: Duration,
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant] = None,
  trustLevel: TrustLevel = TrustLevel.Standard,
  permissions: AgentPermissions =
    AgentPermissions.defaults(
      trustLevel = TrustLevel.Standard,
      cliTool = "",
      timeout = Duration.ofMinutes(30),
      maxEstimatedTokens = None,
    ),
) derives Schema, JsonCodec

object Agent:
  given JsonCodec[Duration] = JsonCodec[String].transformOrFail(
    str => scala.util.Try(Duration.parse(str)).toEither.left.map(_.getMessage),
    duration => duration.toString,
  )

  def fromEvents(events: List[AgentEvent]): Either[String, Agent] =
    events match
      case Nil => Left("Cannot rebuild Agent from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Agent]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(agent) => Right(agent)
            case None        => Left("Agent event stream did not produce a state")
          }

  private def applyEvent(current: Option[Agent], event: AgentEvent): Either[String, Option[Agent]] =
    event match
      case created: AgentEvent.Created   =>
        current match
          case Some(_) => Left(s"Agent ${created.agent.id.value} already initialized")
          case None    => Right(Some(created.agent))
      case updated: AgentEvent.Updated   =>
        current
          .toRight(s"Agent ${updated.agent.id.value} not initialized before Updated event")
          .map(_ => Some(updated.agent))
      case enabled: AgentEvent.Enabled   =>
        current
          .toRight(s"Agent ${enabled.agentId.value} not initialized before Enabled event")
          .map(agent => Some(agent.copy(enabled = true, updatedAt = enabled.occurredAt, deletedAt = None)))
      case disabled: AgentEvent.Disabled =>
        current
          .toRight(s"Agent ${disabled.agentId.value} not initialized before Disabled event")
          .map(agent => Some(agent.copy(enabled = false, updatedAt = disabled.occurredAt)))
      case deleted: AgentEvent.Deleted   =>
        current
          .toRight(s"Agent ${deleted.agentId.value} not initialized before Deleted event")
          .map(agent =>
            Some(agent.copy(enabled = false, updatedAt = deleted.occurredAt, deletedAt = Some(deleted.occurredAt)))
          )
