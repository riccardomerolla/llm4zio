package specification.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ IssueId, SpecificationId }

sealed trait SpecificationEvent derives JsonCodec, Schema:
  def specificationId: SpecificationId
  def occurredAt: Instant

object SpecificationEvent:
  final case class Created(
    specificationId: SpecificationId,
    title: String,
    content: String,
    author: SpecificationAuthor,
    status: SpecificationStatus,
    linkedPlanRef: Option[String],
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class Revised(
    specificationId: SpecificationId,
    version: Int,
    title: String,
    beforeContent: String,
    afterContent: String,
    author: SpecificationAuthor,
    status: SpecificationStatus,
    linkedPlanRef: Option[String],
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class Approved(
    specificationId: SpecificationId,
    approvedBy: SpecificationAuthor,
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class Superseded(
    specificationId: SpecificationId,
    supersededBy: Option[SpecificationId],
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class IssuesLinked(
    specificationId: SpecificationId,
    issueIds: List[IssueId],
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class PlanLinked(
    specificationId: SpecificationId,
    planRef: String,
    occurredAt: Instant,
  ) extends SpecificationEvent

  final case class ReviewCommentAdded(
    specificationId: SpecificationId,
    comment: SpecificationReviewComment,
    occurredAt: Instant,
  ) extends SpecificationEvent
