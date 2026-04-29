package governance.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

enum GovernanceLifecycleStage derives JsonCodec, Schema:
  case Backlog
  case Todo
  case InProgress
  case HumanReview
  case Rework
  case Merging
  case Done

enum GovernanceLifecycleAction derives JsonCodec, Schema:
  case Dispatch
  case StartWork
  case CompleteWork
  case Approve
  case Merge
  case Requeue

enum GovernanceGate derives JsonCodec, Schema:
  case SpecReview
  case PlanningReview
  case HumanApproval
  case CodeReview
  case CiPassed
  case ProofOfWork
  case CanvasReview
  case NormsCompliance
  case SafeguardsCompliance
  case ApiTestPassed
  case Custom(name: String)

enum GovernanceEscalationKind derives JsonCodec, Schema:
  case NotifyHuman
  case BlockTransition
  case RaisePriority
  case Custom(name: String)

final case class GovernanceTransition(
  from: GovernanceLifecycleStage,
  to: GovernanceLifecycleStage,
  action: GovernanceLifecycleAction,
) derives JsonCodec,
    Schema

final case class GovernanceTransitionRule(
  transition: GovernanceTransition,
  @fieldDefaultValue(Nil) requiredGates: List[GovernanceGate] = Nil,
  @fieldDefaultValue(Nil) allowedIssueTypes: List[String] = Nil,
  requireHumanApproval: Boolean = false,
  @fieldDefaultValue(Nil) blockedTags: List[String] = Nil,
) derives JsonCodec,
    Schema

final case class GovernanceDaemonTrigger(
  id: String,
  transition: GovernanceTransition,
  agentName: String,
  @fieldDefaultValue(Nil) issueTypes: List[String] = Nil,
  enabled: Boolean = true,
  schedule: Option[String] = None,
) derives JsonCodec,
    Schema

final case class GovernanceEscalationRule(
  id: String,
  transition: GovernanceTransition,
  kind: GovernanceEscalationKind,
  target: String,
  gate: Option[GovernanceGate] = None,
  afterSeconds: Long = 0L,
) derives JsonCodec,
    Schema

final case class GovernanceCompletionCriteria(
  issueType: String,
  @fieldDefaultValue(Nil) requiredGates: List[GovernanceGate] = Nil,
  requireHumanApproval: Boolean = false,
  @fieldDefaultValue(Nil) requiredArtifacts: List[String] = Nil,
) derives JsonCodec,
    Schema

final case class GovernancePolicy(
  id: GovernancePolicyId,
  projectId: ProjectId,
  name: String,
  version: Int,
  @fieldDefaultValue(Nil) transitionRules: List[GovernanceTransitionRule] = Nil,
  @fieldDefaultValue(Nil) daemonTriggers: List[GovernanceDaemonTrigger] = Nil,
  @fieldDefaultValue(Nil) escalationRules: List[GovernanceEscalationRule] = Nil,
  @fieldDefaultValue(Nil) completionCriteria: List[GovernanceCompletionCriteria] = Nil,
  isDefault: Boolean = false,
  createdAt: Instant,
  updatedAt: Instant,
  archivedAt: Option[Instant] = None,
) derives JsonCodec,
    Schema

object GovernancePolicy:
  def fromEvents(events: List[GovernancePolicyEvent]): Either[String, GovernancePolicy] =
    events match
      case Nil => Left("Cannot rebuild GovernancePolicy from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[GovernancePolicy]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(policy) => Right(policy)
            case None         => Left("Governance policy event stream did not produce a state")
          }

  val noOp: GovernancePolicy =
    GovernancePolicy(
      id = GovernancePolicyId("governance-default"),
      projectId = ProjectId("global-default"),
      name = "Default Governance Policy",
      version = 0,
      transitionRules = Nil,
      daemonTriggers = Nil,
      escalationRules = Nil,
      completionCriteria = Nil,
      isDefault = true,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
      archivedAt = None,
    )

  private def applyEvent(
    current: Option[GovernancePolicy],
    event: GovernancePolicyEvent,
  ): Either[String, Option[GovernancePolicy]] =
    event match
      case created: GovernancePolicyEvent.PolicyCreated   =>
        current match
          case Some(_) =>
            Left(s"GovernancePolicy ${created.policyId.value} already initialized")
          case None    =>
            Right(
              Some(
                GovernancePolicy(
                  id = created.policyId,
                  projectId = created.projectId,
                  name = created.name,
                  version = created.version,
                  transitionRules = created.transitionRules,
                  daemonTriggers = created.daemonTriggers,
                  escalationRules = created.escalationRules,
                  completionCriteria = created.completionCriteria,
                  isDefault = created.isDefault,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                  archivedAt = None,
                )
              )
            )
      case updated: GovernancePolicyEvent.PolicyUpdated   =>
        current
          .toRight(s"GovernancePolicy ${updated.policyId.value} not initialized before PolicyUpdated")
          .map(policy =>
            Some(
              policy.copy(
                name = updated.name,
                version = updated.version,
                transitionRules = updated.transitionRules,
                daemonTriggers = updated.daemonTriggers,
                escalationRules = updated.escalationRules,
                completionCriteria = updated.completionCriteria,
                isDefault = updated.isDefault,
                updatedAt = updated.occurredAt,
              )
            )
          )
      case archived: GovernancePolicyEvent.PolicyArchived =>
        current
          .toRight(s"GovernancePolicy ${archived.policyId.value} not initialized before PolicyArchived")
          .map(policy => Some(policy.copy(archivedAt = Some(archived.archivedAt), updatedAt = archived.occurredAt)))
