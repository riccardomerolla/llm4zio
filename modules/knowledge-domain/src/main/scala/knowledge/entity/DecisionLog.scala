package knowledge.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ DecisionLogId, IssueId, PlanId, SpecificationId }

enum DecisionMakerKind derives JsonCodec, Schema:
  case Human
  case Agent
  case System

final case class DecisionMaker(
  kind: DecisionMakerKind,
  name: String,
) derives JsonCodec,
    Schema

final case class DecisionOption(
  name: String,
  summary: String,
  @fieldDefaultValue(Nil) pros: List[String] = Nil,
  @fieldDefaultValue(Nil) cons: List[String] = Nil,
  selected: Boolean = false,
) derives JsonCodec,
    Schema

final case class DecisionLogVersion(
  version: Int,
  title: String,
  context: String,
  @fieldDefaultValue(Nil) optionsConsidered: List[DecisionOption] = Nil,
  decisionTaken: String,
  rationale: String,
  @fieldDefaultValue(Nil) consequences: List[String] = Nil,
  @fieldDefaultValue(Nil) designConstraints: List[String] = Nil,
  @fieldDefaultValue(Nil) lessonsLearned: List[String] = Nil,
  @fieldDefaultValue(Nil) systemUnderstanding: List[String] = Nil,
  @fieldDefaultValue(Nil) architecturalRationales: List[String] = Nil,
  changedAt: Instant,
) derives JsonCodec,
    Schema

final case class DecisionLog(
  id: DecisionLogId,
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
  version: Int = 1,
  @fieldDefaultValue(Nil) versions: List[DecisionLogVersion] = Nil,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object DecisionLog:
  def fromEvents(events: List[DecisionLogEvent]): Either[String, DecisionLog] =
    events match
      case Nil => Left("Cannot rebuild DecisionLog from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[DecisionLog]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(log) => Right(log)
            case None      => Left("DecisionLog event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[DecisionLog],
    event: DecisionLogEvent,
  ): Either[String, Option[DecisionLog]] =
    event match
      case created: DecisionLogEvent.Created =>
        current match
          case Some(_) => Left(s"DecisionLog ${created.decisionLogId.value} already initialized")
          case None    =>
            val version = DecisionLogVersion(
              version = 1,
              title = created.title,
              context = created.context,
              optionsConsidered = created.optionsConsidered,
              decisionTaken = created.decisionTaken,
              rationale = created.rationale,
              consequences = created.consequences,
              designConstraints = created.designConstraints,
              lessonsLearned = created.lessonsLearned,
              systemUnderstanding = created.systemUnderstanding,
              architecturalRationales = created.architecturalRationales,
              changedAt = created.occurredAt,
            )
            Right(
              Some(
                DecisionLog(
                  id = created.decisionLogId,
                  title = created.title,
                  context = created.context,
                  optionsConsidered = created.optionsConsidered,
                  decisionTaken = created.decisionTaken,
                  rationale = created.rationale,
                  consequences = created.consequences,
                  decisionDate = created.decisionDate,
                  decisionMaker = created.decisionMaker,
                  workspaceId = created.workspaceId,
                  issueIds = created.issueIds,
                  specificationIds = created.specificationIds,
                  planIds = created.planIds,
                  runId = created.runId,
                  conversationId = created.conversationId,
                  relatedDecisionLogIds = created.relatedDecisionLogIds,
                  designConstraints = created.designConstraints,
                  lessonsLearned = created.lessonsLearned,
                  systemUnderstanding = created.systemUnderstanding,
                  architecturalRationales = created.architecturalRationales,
                  version = 1,
                  versions = List(version),
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )
      case revised: DecisionLogEvent.Revised =>
        current
          .toRight(s"DecisionLog ${revised.decisionLogId.value} not initialized before Revised")
          .map { log =>
            val version = DecisionLogVersion(
              version = revised.version,
              title = revised.title,
              context = revised.context,
              optionsConsidered = revised.optionsConsidered,
              decisionTaken = revised.decisionTaken,
              rationale = revised.rationale,
              consequences = revised.consequences,
              designConstraints = revised.designConstraints,
              lessonsLearned = revised.lessonsLearned,
              systemUnderstanding = revised.systemUnderstanding,
              architecturalRationales = revised.architecturalRationales,
              changedAt = revised.occurredAt,
            )
            Some(
              log.copy(
                title = revised.title,
                context = revised.context,
                optionsConsidered = revised.optionsConsidered,
                decisionTaken = revised.decisionTaken,
                rationale = revised.rationale,
                consequences = revised.consequences,
                decisionDate = revised.decisionDate,
                decisionMaker = revised.decisionMaker,
                workspaceId = revised.workspaceId.orElse(log.workspaceId),
                issueIds = revised.issueIds.distinct,
                specificationIds = revised.specificationIds.distinct,
                planIds = revised.planIds.distinct,
                runId = revised.runId.orElse(log.runId),
                conversationId = revised.conversationId.orElse(log.conversationId),
                relatedDecisionLogIds = revised.relatedDecisionLogIds.distinct,
                designConstraints = revised.designConstraints,
                lessonsLearned = revised.lessonsLearned,
                systemUnderstanding = revised.systemUnderstanding,
                architecturalRationales = revised.architecturalRationales,
                version = revised.version,
                versions = (log.versions.filterNot(_.version == revised.version) :+ version).sortBy(_.version),
                updatedAt = revised.occurredAt,
              )
            )
          }
