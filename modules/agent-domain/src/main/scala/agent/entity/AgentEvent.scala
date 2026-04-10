package agent.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.AgentId

sealed trait AgentEvent derives JsonCodec, Schema:
  def agentId: AgentId
  def occurredAt: Instant

object AgentEvent:
  final case class Created(
    agent: Agent,
    occurredAt: Instant,
  ) extends AgentEvent:
    override val agentId: AgentId = agent.id

  final case class Updated(
    agent: Agent,
    occurredAt: Instant,
  ) extends AgentEvent:
    override val agentId: AgentId = agent.id

  final case class Enabled(
    agentId: AgentId,
    occurredAt: Instant,
  ) extends AgentEvent

  final case class Disabled(
    agentId: AgentId,
    reason: Option[String],
    occurredAt: Instant,
  ) extends AgentEvent

  final case class Deleted(
    agentId: AgentId,
    occurredAt: Instant,
  ) extends AgentEvent
