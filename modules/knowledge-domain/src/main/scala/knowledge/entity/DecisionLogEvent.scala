package knowledge.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ DecisionLogId, IssueId, PlanId, SpecificationId }

sealed trait DecisionLogEvent derives JsonCodec, Schema:
  def decisionLogId: DecisionLogId
  def occurredAt: Instant

object DecisionLogEvent:
  final case class Created(
    decisionLogId: DecisionLogId,
    title: String,
    context: String,
    @fieldDefaultValue(Nil) optionsConsidered: List[DecisionOption] = Nil,
    decisionTaken: String,
    rationale: String,
    @fieldDefaultValue(Nil) consequences: List[String] = Nil,
    decisionDate: Instant,
    decisionMaker: DecisionMaker,
    workspaceId: Option[String] = None,
    @fieldDefaultValue(Nil) issueIds: List[IssueId] = Nil,
    @fieldDefaultValue(Nil) specificationIds: List[SpecificationId] = Nil,
    @fieldDefaultValue(Nil) planIds: List[PlanId] = Nil,
    runId: Option[String] = None,
    conversationId: Option[String] = None,
    @fieldDefaultValue(Nil) relatedDecisionLogIds: List[DecisionLogId] = Nil,
    @fieldDefaultValue(Nil) designConstraints: List[String] = Nil,
    @fieldDefaultValue(Nil) lessonsLearned: List[String] = Nil,
    @fieldDefaultValue(Nil) systemUnderstanding: List[String] = Nil,
    @fieldDefaultValue(Nil) architecturalRationales: List[String] = Nil,
    occurredAt: Instant,
  ) extends DecisionLogEvent

  final case class Revised(
    decisionLogId: DecisionLogId,
    version: Int,
    title: String,
    context: String,
    @fieldDefaultValue(Nil) optionsConsidered: List[DecisionOption] = Nil,
    decisionTaken: String,
    rationale: String,
    @fieldDefaultValue(Nil) consequences: List[String] = Nil,
    decisionDate: Instant,
    decisionMaker: DecisionMaker,
    workspaceId: Option[String] = None,
    @fieldDefaultValue(Nil) issueIds: List[IssueId] = Nil,
    @fieldDefaultValue(Nil) specificationIds: List[SpecificationId] = Nil,
    @fieldDefaultValue(Nil) planIds: List[PlanId] = Nil,
    runId: Option[String] = None,
    conversationId: Option[String] = None,
    @fieldDefaultValue(Nil) relatedDecisionLogIds: List[DecisionLogId] = Nil,
    @fieldDefaultValue(Nil) designConstraints: List[String] = Nil,
    @fieldDefaultValue(Nil) lessonsLearned: List[String] = Nil,
    @fieldDefaultValue(Nil) systemUnderstanding: List[String] = Nil,
    @fieldDefaultValue(Nil) architecturalRationales: List[String] = Nil,
    occurredAt: Instant,
  ) extends DecisionLogEvent
