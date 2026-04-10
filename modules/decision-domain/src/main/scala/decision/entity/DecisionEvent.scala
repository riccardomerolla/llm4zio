package decision.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.DecisionId

sealed trait DecisionEvent derives JsonCodec, Schema:
  def decisionId: DecisionId
  def occurredAt: Instant

object DecisionEvent:
  final case class Created(
    decisionId: DecisionId,
    title: String,
    context: String,
    action: DecisionAction,
    source: DecisionSource,
    urgency: DecisionUrgency,
    deadlineAt: Option[Instant],
    occurredAt: Instant,
  ) extends DecisionEvent

  final case class Resolved(
    decisionId: DecisionId,
    resolution: DecisionResolution,
    occurredAt: Instant,
  ) extends DecisionEvent

  final case class Escalated(
    decisionId: DecisionId,
    reason: String,
    occurredAt: Instant,
  ) extends DecisionEvent

  final case class Expired(
    decisionId: DecisionId,
    occurredAt: Instant,
  ) extends DecisionEvent
