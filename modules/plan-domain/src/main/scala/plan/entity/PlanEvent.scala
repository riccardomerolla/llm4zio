package plan.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }

sealed trait PlanEvent derives JsonCodec, Schema:
  def planId: PlanId
  def occurredAt: Instant

object PlanEvent:
  final case class Created(
    planId: PlanId,
    conversationId: Long,
    workspaceId: Option[String],
    specificationId: Option[SpecificationId],
    summary: String,
    rationale: String,
    @fieldDefaultValue(Nil) drafts: List[PlanTaskDraft] = Nil,
    occurredAt: Instant,
  ) extends PlanEvent

  final case class Validated(
    planId: PlanId,
    result: PlanValidationResult,
    occurredAt: Instant,
  ) extends PlanEvent

  final case class TasksCreated(
    planId: PlanId,
    @fieldDefaultValue(Nil) issueIds: List[IssueId] = Nil,
    occurredAt: Instant,
  ) extends PlanEvent

  final case class Revised(
    planId: PlanId,
    version: Int,
    workspaceId: Option[String],
    specificationId: Option[SpecificationId],
    summary: String,
    rationale: String,
    @fieldDefaultValue(Nil) drafts: List[PlanTaskDraft] = Nil,
    occurredAt: Instant,
  ) extends PlanEvent

  final case class Completed(
    planId: PlanId,
    occurredAt: Instant,
  ) extends PlanEvent

  final case class Abandoned(
    planId: PlanId,
    reason: Option[String],
    occurredAt: Instant,
  ) extends PlanEvent
