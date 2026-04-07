package decision.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ DecisionId, IssueId }

enum DecisionStatus derives JsonCodec, Schema:
  case Pending
  case Resolved
  case Escalated
  case Expired

enum DecisionUrgency derives JsonCodec, Schema:
  case Low
  case Medium
  case High
  case Critical

enum DecisionAction derives JsonCodec, Schema:
  case ReviewIssue
  case ManualEscalation

enum DecisionSourceKind derives JsonCodec, Schema:
  case IssueReview
  case Governance
  case AgentEscalation
  case Manual

enum DecisionResolutionKind derives JsonCodec, Schema:
  case Approved
  case ReworkRequested
  case Acknowledged
  case Escalated
  case Expired

final case class DecisionSource(
  kind: DecisionSourceKind,
  referenceId: String,
  summary: String,
  workspaceId: Option[String] = None,
  issueId: Option[IssueId] = None,
) derives JsonCodec, Schema

final case class DecisionResolution(
  kind: DecisionResolutionKind,
  actor: String,
  summary: String,
  respondedAt: Instant,
) derives JsonCodec, Schema

final case class Decision(
  id: DecisionId,
  title: String,
  context: String,
  action: DecisionAction,
  source: DecisionSource,
  urgency: DecisionUrgency,
  status: DecisionStatus,
  deadlineAt: Option[Instant],
  @fieldDefaultValue(None) resolution: Option[DecisionResolution] = None,
  @fieldDefaultValue(None) escalatedAt: Option[Instant] = None,
  @fieldDefaultValue(None) expiredAt: Option[Instant] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema:

  def isOpen: Boolean = status == DecisionStatus.Pending

  def responseTimeMillis: Option[Long] =
    resolution.map(res => java.time.Duration.between(createdAt, res.respondedAt).toMillis)

object Decision:
  def fromEvents(events: List[DecisionEvent]): Either[String, Decision] =
    events match
      case Nil => Left("Cannot rebuild Decision from an empty event stream")
      case _   =>
        events.foldLeft[Either[String, Option[Decision]]](Right(None)) { (acc, event) =>
          acc.flatMap(current => applyEvent(current, event))
        }.flatMap {
          case Some(decision) => Right(decision)
          case None           => Left("Decision stream did not produce a state")
        }

  private def applyEvent(current: Option[Decision], event: DecisionEvent): Either[String, Option[Decision]] =
    event match
      case created: DecisionEvent.Created     =>
        current match
          case Some(_) => Left(s"Decision ${created.decisionId.value} already initialized")
          case None    =>
            Right(
              Some(
                Decision(
                  id = created.decisionId,
                  title = created.title,
                  context = created.context,
                  action = created.action,
                  source = created.source,
                  urgency = created.urgency,
                  status = DecisionStatus.Pending,
                  deadlineAt = created.deadlineAt,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )
      case resolved: DecisionEvent.Resolved   =>
        current
          .toRight(s"Decision ${resolved.decisionId.value} not initialized before Resolved event")
          .map(decision =>
            Some(
              decision.copy(
                status = DecisionStatus.Resolved,
                resolution = Some(resolved.resolution),
                updatedAt = resolved.occurredAt,
              )
            )
          )
      case escalated: DecisionEvent.Escalated =>
        current
          .toRight(s"Decision ${escalated.decisionId.value} not initialized before Escalated event")
          .map(decision =>
            Some(
              decision.copy(
                status = DecisionStatus.Escalated,
                escalatedAt = Some(escalated.occurredAt),
                updatedAt = escalated.occurredAt,
              )
            )
          )
      case expired: DecisionEvent.Expired     =>
        current
          .toRight(s"Decision ${expired.decisionId.value} not initialized before Expired event")
          .map(decision =>
            Some(
              decision.copy(
                status = DecisionStatus.Expired,
                expiredAt = Some(expired.occurredAt),
                updatedAt = expired.occurredAt,
              )
            )
          )
