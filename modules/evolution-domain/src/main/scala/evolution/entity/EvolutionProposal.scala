package evolution.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import _root_.config.entity.WorkflowDefinition
import daemon.entity.DaemonAgentSpec
import governance.entity.GovernancePolicy
import shared.ids.Ids.{ DecisionId, EvolutionProposalId, GovernancePolicyId, ProjectId }

enum EvolutionProposalStatus derives JsonCodec, Schema:
  case Proposed
  case Approved
  case Applied
  case RolledBack

enum EvolutionChangeOperation derives JsonCodec, Schema:
  case Upsert
  case Delete

enum EvolutionTemplateKind derives JsonCodec, Schema:
  case AddQualityGate
  case ChangeTestingStrategy
  case AddDaemonAgent
  case Custom(name: String)

sealed trait EvolutionTarget derives JsonCodec, Schema:
  def projectId: ProjectId
  def operation: EvolutionChangeOperation

object EvolutionTarget:
  final case class GovernancePolicyTarget(
    projectId: ProjectId,
    policyId: Option[GovernancePolicyId],
    name: String,
    transitionRules: List[governance.entity.GovernanceTransitionRule],
    daemonTriggers: List[governance.entity.GovernanceDaemonTrigger],
    escalationRules: List[governance.entity.GovernanceEscalationRule],
    completionCriteria: List[governance.entity.GovernanceCompletionCriteria],
    isDefault: Boolean,
    operation: EvolutionChangeOperation = EvolutionChangeOperation.Upsert,
  ) extends EvolutionTarget

  final case class WorkflowDefinitionTarget(
    projectId: ProjectId,
    workflow: WorkflowDefinition,
    operation: EvolutionChangeOperation = EvolutionChangeOperation.Upsert,
  ) extends EvolutionTarget

  final case class DaemonAgentSpecTarget(
    spec: DaemonAgentSpec,
    enabled: Boolean = true,
    operation: EvolutionChangeOperation = EvolutionChangeOperation.Upsert,
  ) extends EvolutionTarget:
    override def projectId: ProjectId = spec.projectId

enum EvolutionTargetSnapshot derives JsonCodec, Schema:
  case GovernancePolicyState(policy: GovernancePolicy)
  case WorkflowDefinitionState(projectId: ProjectId, workflow: WorkflowDefinition)
  case DaemonAgentSpecState(spec: DaemonAgentSpec, enabled: Boolean)

final case class EvolutionAuditRecord(
  actor: String,
  note: String,
  at: Instant,
) derives JsonCodec,
    Schema

final case class EvolutionProposal(
  id: EvolutionProposalId,
  projectId: ProjectId,
  title: String,
  rationale: String,
  target: EvolutionTarget,
  template: Option[EvolutionTemplateKind],
  proposer: EvolutionAuditRecord,
  status: EvolutionProposalStatus,
  decisionId: Option[DecisionId],
  @fieldDefaultValue(None) approval: Option[EvolutionAuditRecord] = None,
  @fieldDefaultValue(None) application: Option[EvolutionAuditRecord] = None,
  @fieldDefaultValue(None) rollback: Option[EvolutionAuditRecord] = None,
  @fieldDefaultValue(None) baselineSnapshot: Option[EvolutionTargetSnapshot] = None,
  @fieldDefaultValue(None) appliedSnapshot: Option[EvolutionTargetSnapshot] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object EvolutionProposal:
  def fromEvents(events: List[EvolutionProposalEvent]): Either[String, EvolutionProposal] =
    events match
      case Nil => Left("Cannot rebuild EvolutionProposal from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[EvolutionProposal]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(value) => Right(value)
            case None        => Left("Evolution proposal stream did not produce a state")
          }

  private def applyEvent(
    current: Option[EvolutionProposal],
    event: EvolutionProposalEvent,
  ): Either[String, Option[EvolutionProposal]] =
    event match
      case proposed: EvolutionProposalEvent.Proposed     =>
        current match
          case Some(_) => Left(s"Evolution proposal ${proposed.proposalId.value} already initialized")
          case None    =>
            val proposer = EvolutionAuditRecord(proposed.proposedBy, proposed.summary, proposed.occurredAt)
            Right(
              Some(
                EvolutionProposal(
                  id = proposed.proposalId,
                  projectId = proposed.projectId,
                  title = proposed.title,
                  rationale = proposed.rationale,
                  target = proposed.target,
                  template = proposed.template,
                  proposer = proposer,
                  status = EvolutionProposalStatus.Proposed,
                  decisionId = proposed.decisionId,
                  createdAt = proposed.occurredAt,
                  updatedAt = proposed.occurredAt,
                )
              )
            )
      case approved: EvolutionProposalEvent.Approved     =>
        current
          .toRight(s"Evolution proposal ${approved.proposalId.value} not initialized before Approved")
          .map(proposal =>
            Some(
              proposal.copy(
                status = EvolutionProposalStatus.Approved,
                approval = Some(EvolutionAuditRecord(approved.approvedBy, approved.summary, approved.occurredAt)),
                updatedAt = approved.occurredAt,
              )
            )
          )
      case applied: EvolutionProposalEvent.Applied       =>
        current
          .toRight(s"Evolution proposal ${applied.proposalId.value} not initialized before Applied")
          .map(proposal =>
            Some(
              proposal.copy(
                status = EvolutionProposalStatus.Applied,
                application = Some(EvolutionAuditRecord(applied.appliedBy, applied.summary, applied.occurredAt)),
                baselineSnapshot = applied.baselineSnapshot,
                appliedSnapshot = Some(applied.appliedSnapshot),
                updatedAt = applied.occurredAt,
              )
            )
          )
      case rolledBack: EvolutionProposalEvent.RolledBack =>
        current
          .toRight(s"Evolution proposal ${rolledBack.proposalId.value} not initialized before RolledBack")
          .map(proposal =>
            Some(
              proposal.copy(
                status = EvolutionProposalStatus.RolledBack,
                rollback =
                  Some(EvolutionAuditRecord(rolledBack.rolledBackBy, rolledBack.summary, rolledBack.occurredAt)),
                appliedSnapshot = Some(rolledBack.rollbackSnapshot),
                updatedAt = rolledBack.occurredAt,
              )
            )
          )
