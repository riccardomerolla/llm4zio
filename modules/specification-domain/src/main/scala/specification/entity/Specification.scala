package specification.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ IssueId, SpecificationId }

enum SpecificationStatus derives JsonCodec, Schema:
  case Draft
  case InRefinement
  case Approved
  case Superseded

enum SpecificationAuthorKind derives JsonCodec, Schema:
  case Human
  case Agent

final case class SpecificationAuthor(
  kind: SpecificationAuthorKind,
  id: String,
  displayName: String,
) derives JsonCodec,
    Schema

final case class SpecificationReviewComment(
  commentId: String,
  author: SpecificationAuthor,
  content: String,
  createdAt: Instant,
) derives JsonCodec,
    Schema

final case class SpecificationRevision(
  version: Int,
  title: String,
  content: String,
  author: SpecificationAuthor,
  status: SpecificationStatus,
  changedAt: Instant,
  @fieldDefaultValue(Nil) reviewComments: List[SpecificationReviewComment] = Nil,
) derives JsonCodec,
    Schema

final case class SpecificationDiff(
  fromVersion: Int,
  toVersion: Int,
  beforeContent: String,
  afterContent: String,
) derives JsonCodec,
    Schema

final case class Specification(
  id: SpecificationId,
  title: String,
  content: String,
  status: SpecificationStatus,
  version: Int,
  revisions: List[SpecificationRevision],
  linkedIssueIds: List[IssueId],
  linkedPlanRef: Option[String],
  author: SpecificationAuthor,
  reviewComments: List[SpecificationReviewComment],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object Specification:
  def fromEvents(events: List[SpecificationEvent]): Either[String, Specification] =
    events match
      case Nil => Left("Cannot rebuild Specification from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Specification]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(specification) => Right(specification)
            case None                => Left("Specification event stream did not produce a state")
          }

  def diff(
    specification: Specification,
    fromVersion: Int,
    toVersion: Int,
  ): Either[String, SpecificationDiff] =
    for
      before <- specification.revisions.find(_.version == fromVersion).toRight(s"Unknown version $fromVersion")
      after  <- specification.revisions.find(_.version == toVersion).toRight(s"Unknown version $toVersion")
    yield SpecificationDiff(
      fromVersion = fromVersion,
      toVersion = toVersion,
      beforeContent = before.content,
      afterContent = after.content,
    )

  private def applyEvent(
    current: Option[Specification],
    event: SpecificationEvent,
  ): Either[String, Option[Specification]] =
    event match
      case created: SpecificationEvent.Created                 =>
        current match
          case Some(_) =>
            Left(s"Specification ${created.specificationId.value} already initialized")
          case None    =>
            val initialRevision = SpecificationRevision(
              version = 1,
              title = created.title,
              content = created.content,
              author = created.author,
              status = created.status,
              changedAt = created.occurredAt,
            )
            Right(
              Some(
                Specification(
                  id = created.specificationId,
                  title = created.title,
                  content = created.content,
                  status = created.status,
                  version = 1,
                  revisions = List(initialRevision),
                  linkedIssueIds = Nil,
                  linkedPlanRef = created.linkedPlanRef,
                  author = created.author,
                  reviewComments = Nil,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )
      case revised: SpecificationEvent.Revised                 =>
        current
          .toRight(s"Specification ${revised.specificationId.value} not initialized before Revised")
          .map { specification =>
            val nextVersion = revised.version
            val revision    = SpecificationRevision(
              version = nextVersion,
              title = revised.title,
              content = revised.afterContent,
              author = revised.author,
              status = revised.status,
              changedAt = revised.occurredAt,
              reviewComments = specification.reviewComments,
            )
            Some(
              specification.copy(
                title = revised.title,
                content = revised.afterContent,
                status = revised.status,
                version = nextVersion,
                revisions = specification.revisions.filterNot(_.version == nextVersion) :+ revision,
                linkedPlanRef = revised.linkedPlanRef.orElse(specification.linkedPlanRef),
                updatedAt = revised.occurredAt,
              )
            )
          }
      case approved: SpecificationEvent.Approved               =>
        current
          .toRight(s"Specification ${approved.specificationId.value} not initialized before Approved")
          .map(specification =>
            Some(
              specification.copy(
                status = SpecificationStatus.Approved,
                updatedAt = approved.occurredAt,
              )
            )
          )
      case superseded: SpecificationEvent.Superseded           =>
        current
          .toRight(s"Specification ${superseded.specificationId.value} not initialized before Superseded")
          .map(specification =>
            Some(
              specification.copy(
                status = SpecificationStatus.Superseded,
                updatedAt = superseded.occurredAt,
              )
            )
          )
      case linkedIssues: SpecificationEvent.IssuesLinked       =>
        current
          .toRight(s"Specification ${linkedIssues.specificationId.value} not initialized before IssuesLinked")
          .map(specification =>
            Some(
              specification.copy(
                linkedIssueIds = (specification.linkedIssueIds ++ linkedIssues.issueIds).distinct,
                updatedAt = linkedIssues.occurredAt,
              )
            )
          )
      case linkedPlan: SpecificationEvent.PlanLinked           =>
        current
          .toRight(s"Specification ${linkedPlan.specificationId.value} not initialized before PlanLinked")
          .map(specification =>
            Some(
              specification.copy(
                linkedPlanRef = Some(linkedPlan.planRef),
                updatedAt = linkedPlan.occurredAt,
              )
            )
          )
      case addedComment: SpecificationEvent.ReviewCommentAdded =>
        current
          .toRight(s"Specification ${addedComment.specificationId.value} not initialized before ReviewCommentAdded")
          .map(specification =>
            Some(
              specification.copy(
                reviewComments = specification.reviewComments :+ addedComment.comment,
                status = SpecificationStatus.InRefinement,
                updatedAt = addedComment.occurredAt,
              )
            )
          )
