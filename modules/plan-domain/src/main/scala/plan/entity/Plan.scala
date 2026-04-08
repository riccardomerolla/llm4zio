package plan.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import governance.entity.GovernanceGate
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }

enum PlanStatus derives JsonCodec, Schema:
  case Draft
  case Validated
  case Executing
  case Completed
  case Abandoned

enum PlanValidationStatus derives JsonCodec, Schema:
  case Passed
  case Blocked

final case class PlanTaskDraft(
  draftId: String,
  title: String,
  description: String,
  issueType: String = "task",
  priority: String = "medium",
  estimate: Option[String] = None,
  @fieldDefaultValue(Nil) requiredCapabilities: List[String] = Nil,
  @fieldDefaultValue(Nil) dependencyDraftIds: List[String] = Nil,
  acceptanceCriteria: String = "",
  promptTemplate: String = "",
  @fieldDefaultValue(Nil) kaizenSkills: List[String] = Nil,
  @fieldDefaultValue(Nil) proofOfWorkRequirements: List[String] = Nil,
  included: Boolean = true,
) derives JsonCodec,
    Schema

final case class PlanValidationResult(
  status: PlanValidationStatus,
  @fieldDefaultValue(Nil) requiredGates: List[GovernanceGate] = Nil,
  @fieldDefaultValue(Nil) missingGates: List[GovernanceGate] = Nil,
  humanApprovalRequired: Boolean = false,
  reason: Option[String] = None,
  validatedAt: Instant,
) derives JsonCodec,
    Schema

final case class PlanVersion(
  version: Int,
  summary: String,
  rationale: String,
  @fieldDefaultValue(Nil) drafts: List[PlanTaskDraft] = Nil,
  validation: Option[PlanValidationResult] = None,
  status: PlanStatus,
  changedAt: Instant,
) derives JsonCodec,
    Schema

final case class Plan(
  id: PlanId,
  conversationId: Long,
  workspaceId: Option[String],
  specificationId: Option[SpecificationId],
  summary: String,
  rationale: String,
  status: PlanStatus,
  version: Int,
  @fieldDefaultValue(Nil) drafts: List[PlanTaskDraft] = Nil,
  validation: Option[PlanValidationResult] = None,
  @fieldDefaultValue(Nil) linkedIssueIds: List[IssueId] = Nil,
  @fieldDefaultValue(Nil) versions: List[PlanVersion] = Nil,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object Plan:
  def fromEvents(events: List[PlanEvent]): Either[String, Plan] =
    events match
      case Nil => Left("Cannot rebuild Plan from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Plan]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(plan) => Right(plan)
            case None       => Left("Plan event stream did not produce a state")
          }

  private def applyEvent(current: Option[Plan], event: PlanEvent): Either[String, Option[Plan]] =
    event match
      case created: PlanEvent.Created     =>
        current match
          case Some(_) => Left(s"Plan ${created.planId.value} already initialized")
          case None    =>
            val initialVersion = PlanVersion(
              version = 1,
              summary = created.summary,
              rationale = created.rationale,
              drafts = created.drafts,
              validation = None,
              status = PlanStatus.Draft,
              changedAt = created.occurredAt,
            )
            Right(
              Some(
                Plan(
                  id = created.planId,
                  conversationId = created.conversationId,
                  workspaceId = created.workspaceId,
                  specificationId = created.specificationId,
                  summary = created.summary,
                  rationale = created.rationale,
                  status = PlanStatus.Draft,
                  version = 1,
                  drafts = created.drafts,
                  validation = None,
                  linkedIssueIds = Nil,
                  versions = List(initialVersion),
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )
      case validated: PlanEvent.Validated =>
        current
          .toRight(s"Plan ${validated.planId.value} not initialized before Validated")
          .map(plan =>
            Some(
              plan.copy(
                status =
                  if validated.result.status == PlanValidationStatus.Passed then PlanStatus.Validated
                  else PlanStatus.Draft,
                validation = Some(validated.result),
                versions = replaceVersionValidation(
                  plan.versions,
                  plan.version,
                  Some(validated.result),
                  if validated.result.status == PlanValidationStatus.Passed then PlanStatus.Validated
                  else PlanStatus.Draft,
                  validated.occurredAt,
                ),
                updatedAt = validated.occurredAt,
              )
            )
          )
      case linked: PlanEvent.TasksCreated =>
        current
          .toRight(s"Plan ${linked.planId.value} not initialized before TasksCreated")
          .map(plan =>
            Some(
              plan.copy(
                status = PlanStatus.Executing,
                linkedIssueIds = (plan.linkedIssueIds ++ linked.issueIds).distinct,
                versions = replaceVersionValidation(
                  plan.versions,
                  plan.version,
                  plan.validation,
                  PlanStatus.Executing,
                  linked.occurredAt,
                ),
                updatedAt = linked.occurredAt,
              )
            )
          )
      case revised: PlanEvent.Revised     =>
        current
          .toRight(s"Plan ${revised.planId.value} not initialized before Revised")
          .map(plan =>
            Some(
              plan.copy(
                workspaceId = revised.workspaceId.orElse(plan.workspaceId),
                specificationId = revised.specificationId.orElse(plan.specificationId),
                summary = revised.summary,
                rationale = revised.rationale,
                status = PlanStatus.Draft,
                version = revised.version,
                drafts = revised.drafts,
                validation = None,
                versions = (plan.versions.filterNot(_.version == revised.version) :+ PlanVersion(
                  version = revised.version,
                  summary = revised.summary,
                  rationale = revised.rationale,
                  drafts = revised.drafts,
                  validation = None,
                  status = PlanStatus.Draft,
                  changedAt = revised.occurredAt,
                )).sortBy(_.version),
                updatedAt = revised.occurredAt,
              )
            )
          )
      case completed: PlanEvent.Completed =>
        current
          .toRight(s"Plan ${completed.planId.value} not initialized before Completed")
          .map(plan =>
            Some(
              plan.copy(
                status = PlanStatus.Completed,
                versions = replaceVersionValidation(
                  plan.versions,
                  plan.version,
                  plan.validation,
                  PlanStatus.Completed,
                  completed.occurredAt,
                ),
                updatedAt = completed.occurredAt,
              )
            )
          )
      case abandoned: PlanEvent.Abandoned =>
        current
          .toRight(s"Plan ${abandoned.planId.value} not initialized before Abandoned")
          .map(plan =>
            Some(
              plan.copy(
                status = PlanStatus.Abandoned,
                versions = replaceVersionValidation(
                  plan.versions,
                  plan.version,
                  plan.validation,
                  PlanStatus.Abandoned,
                  abandoned.occurredAt,
                ),
                updatedAt = abandoned.occurredAt,
              )
            )
          )

  private def replaceVersionValidation(
    versions: List[PlanVersion],
    currentVersion: Int,
    validation: Option[PlanValidationResult],
    status: PlanStatus,
    changedAt: Instant,
  ): List[PlanVersion] =
    versions.map {
      case version if version.version == currentVersion =>
        version.copy(validation = validation, status = status, changedAt = changedAt)
      case version                                      => version
    }
