package evolution.control

import zio.*
import zio.schema.{ Schema, derived }

import _root_.config.entity.WorkflowDefinition
import daemon.entity.{ DaemonAgentSpec, DaemonAgentSpecRepository }
import db.{ ConfigRepository, PersistenceError as DbPersistenceError }
import decision.control.DecisionInbox
import decision.entity.{ DecisionResolutionKind, DecisionStatus, DecisionUrgency }
import evolution.entity.*
import governance.entity.{ GovernancePolicy, GovernancePolicyEvent, GovernancePolicyRepository }
import orchestration.control.{ WorkflowService, WorkflowServiceError }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, EvolutionProposalId, GovernancePolicyId }

enum EvolutionError derives zio.json.JsonCodec:
  case PersistenceFailed(message: String)
  case DecisionApprovalRequired(proposalId: EvolutionProposalId)
  case InvalidStatus(proposalId: EvolutionProposalId, expected: String, actual: EvolutionProposalStatus)
  case InvalidDecision(proposalId: EvolutionProposalId, decisionId: DecisionId, reason: String)
  case TargetNotFound(kind: String, reference: String)

trait EvolutionEngine:
  def propose(request: EvolutionProposalRequest): IO[EvolutionError, EvolutionProposal]
  def approve(proposalId: EvolutionProposalId, actor: String, summary: String): IO[EvolutionError, EvolutionProposal]
  def apply(proposalId: EvolutionProposalId, actor: String, summary: String): IO[EvolutionError, EvolutionProposal]
  def rollback(proposalId: EvolutionProposalId, actor: String, summary: String): IO[EvolutionError, EvolutionProposal]
  def get(proposalId: EvolutionProposalId): IO[EvolutionError, EvolutionProposal]
  def list(filter: EvolutionProposalFilter = EvolutionProposalFilter()): IO[EvolutionError, List[EvolutionProposal]]
  def history(proposalId: EvolutionProposalId): IO[EvolutionError, List[EvolutionProposalEvent]]

object EvolutionEngine:
  def propose(request: EvolutionProposalRequest): ZIO[EvolutionEngine, EvolutionError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionEngine](_.propose(request))

  def approve(
    proposalId: EvolutionProposalId,
    actor: String,
    summary: String,
  ): ZIO[EvolutionEngine, EvolutionError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionEngine](_.approve(proposalId, actor, summary))

  def apply(
    proposalId: EvolutionProposalId,
    actor: String,
    summary: String,
  ): ZIO[EvolutionEngine, EvolutionError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionEngine](_.apply(proposalId, actor, summary))

  def rollback(
    proposalId: EvolutionProposalId,
    actor: String,
    summary: String,
  ): ZIO[EvolutionEngine, EvolutionError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionEngine](_.rollback(proposalId, actor, summary))

  def get(proposalId: EvolutionProposalId): ZIO[EvolutionEngine, EvolutionError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionEngine](_.get(proposalId))

  def list(
    filter: EvolutionProposalFilter = EvolutionProposalFilter()
  ): ZIO[EvolutionEngine, EvolutionError, List[EvolutionProposal]] =
    ZIO.serviceWithZIO[EvolutionEngine](_.list(filter))

  def history(proposalId: EvolutionProposalId): ZIO[EvolutionEngine, EvolutionError, List[EvolutionProposalEvent]] =
    ZIO.serviceWithZIO[EvolutionEngine](_.history(proposalId))

  val live
    : ZLayer[
      EvolutionProposalRepository & DecisionInbox & GovernancePolicyRepository & WorkflowService & ConfigRepository & DaemonAgentSpecRepository,
      Nothing,
      EvolutionEngine,
    ] =
    ZLayer.fromFunction(EvolutionEngineLive.apply)

final case class EvolutionProposalRequest(
  projectId: shared.ids.Ids.ProjectId,
  title: String,
  rationale: String,
  target: EvolutionTarget,
  proposedBy: String,
  summary: String,
  template: Option[EvolutionTemplateKind] = None,
) derives zio.json.JsonCodec,
    Schema

final case class EvolutionEngineLive(
  repository: EvolutionProposalRepository,
  decisionInbox: DecisionInbox,
  governanceRepository: GovernancePolicyRepository,
  workflowService: WorkflowService,
  configRepository: ConfigRepository,
  daemonRepository: DaemonAgentSpecRepository,
) extends EvolutionEngine:

  override def propose(request: EvolutionProposalRequest): IO[EvolutionError, EvolutionProposal] =
    for
      now       <- Clock.instant
      proposalId = EvolutionProposalId.generate
      decision  <- decisionInbox
                     .openManualDecision(
                       title = s"Approve evolution: ${request.title.trim}",
                       context = request.rationale.trim,
                       referenceId = proposalId.value,
                       summary = request.summary.trim,
                       urgency = DecisionUrgency.High,
                     )
                     .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      event      = EvolutionProposalEvent.Proposed(
                     proposalId = proposalId,
                     projectId = request.projectId,
                     title = request.title.trim,
                     rationale = request.rationale.trim,
                     target = request.target,
                     template = request.template,
                     proposedBy = request.proposedBy.trim,
                     summary = request.summary.trim,
                     decisionId = Some(decision.id),
                     occurredAt = now,
                   )
      _         <- repository.append(event).mapError(error => EvolutionError.PersistenceFailed(error.toString))
      created   <- repository.get(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))
    yield created

  override def approve(proposalId: EvolutionProposalId, actor: String, summary: String)
    : IO[EvolutionError, EvolutionProposal] =
    for
      proposal   <- get(proposalId)
      _          <- ensureStatus(proposal, EvolutionProposalStatus.Proposed, "Proposed")
      decisionId <- ZIO
                      .fromOption(proposal.decisionId)
                      .orElseFail(EvolutionError.DecisionApprovalRequired(proposal.id))
      decision   <- decisionInbox.get(decisionId).mapError(error => EvolutionError.PersistenceFailed(error.toString))
      _          <- ensureDecisionApproved(proposal.id, decisionId, decision.status, decision.resolution.map(_.kind))
      now        <- Clock.instant
      _          <- repository
                      .append(
                        EvolutionProposalEvent.Approved(
                          proposalId = proposal.id,
                          projectId = proposal.projectId,
                          decisionId = decisionId,
                          approvedBy = actor.trim,
                          summary = summary.trim,
                          occurredAt = now,
                        )
                      )
                      .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      updated    <- repository.get(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))
    yield updated

  override def apply(proposalId: EvolutionProposalId, actor: String, summary: String)
    : IO[EvolutionError, EvolutionProposal] =
    for
      proposal <- get(proposalId)
      _        <- ensureStatus(proposal, EvolutionProposalStatus.Approved, "Approved")
      now      <- Clock.instant
      result   <- applyTarget(proposal.target)
      _        <- repository
                    .append(
                      EvolutionProposalEvent.Applied(
                        proposalId = proposal.id,
                        projectId = proposal.projectId,
                        appliedBy = actor.trim,
                        summary = summary.trim,
                        baselineSnapshot = result._1,
                        appliedSnapshot = result._2,
                        occurredAt = now,
                      )
                    )
                    .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      updated  <- repository.get(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))
    yield updated

  override def rollback(proposalId: EvolutionProposalId, actor: String, summary: String)
    : IO[EvolutionError, EvolutionProposal] =
    for
      proposal <- get(proposalId)
      _        <- ensureStatus(proposal, EvolutionProposalStatus.Applied, "Applied")
      snapshot <- proposal.baselineSnapshot match
                    case Some(value) => restoreSnapshot(Some(value), proposal.target)
                    case None        => restoreSnapshot(None, proposal.target)
      now      <- Clock.instant
      _        <- repository
                    .append(
                      EvolutionProposalEvent.RolledBack(
                        proposalId = proposal.id,
                        projectId = proposal.projectId,
                        rolledBackBy = actor.trim,
                        summary = summary.trim,
                        rollbackSnapshot = snapshot,
                        occurredAt = now,
                      )
                    )
                    .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      updated  <- repository.get(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))
    yield updated

  override def get(proposalId: EvolutionProposalId): IO[EvolutionError, EvolutionProposal] =
    repository.get(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))

  override def list(filter: EvolutionProposalFilter): IO[EvolutionError, List[EvolutionProposal]] =
    repository.list(filter).mapError(error => EvolutionError.PersistenceFailed(error.toString))

  override def history(proposalId: EvolutionProposalId): IO[EvolutionError, List[EvolutionProposalEvent]] =
    repository.history(proposalId).mapError(error => EvolutionError.PersistenceFailed(error.toString))

  private def ensureStatus(
    proposal: EvolutionProposal,
    expected: EvolutionProposalStatus,
    expectedLabel: String,
  ): IO[EvolutionError, Unit] =
    if proposal.status == expected then ZIO.unit
    else ZIO.fail(EvolutionError.InvalidStatus(proposal.id, expectedLabel, proposal.status))

  private def ensureDecisionApproved(
    proposalId: EvolutionProposalId,
    decisionId: DecisionId,
    status: DecisionStatus,
    resolution: Option[DecisionResolutionKind],
  ): IO[EvolutionError, Unit] =
    if status == DecisionStatus.Resolved && resolution.contains(DecisionResolutionKind.Approved) then ZIO.unit
    else
      ZIO.fail(EvolutionError.InvalidDecision(
        proposalId,
        decisionId,
        s"Decision is $status / ${resolution.map(_.toString).getOrElse("unresolved")}",
      ))

  private def applyTarget(
    target: EvolutionTarget
  ): IO[EvolutionError, (Option[EvolutionTargetSnapshot], EvolutionTargetSnapshot)] =
    target match
      case governance: EvolutionTarget.GovernancePolicyTarget =>
        applyGovernance(governance)
      case workflow: EvolutionTarget.WorkflowDefinitionTarget =>
        applyWorkflow(workflow)
      case daemon: EvolutionTarget.DaemonAgentSpecTarget      =>
        applyDaemon(daemon)

  private def restoreSnapshot(
    snapshot: Option[EvolutionTargetSnapshot],
    target: EvolutionTarget,
  ): IO[EvolutionError, EvolutionTargetSnapshot] =
    snapshot match
      case Some(EvolutionTargetSnapshot.GovernancePolicyState(policy))        =>
        upsertGovernancePolicy(policy).as(EvolutionTargetSnapshot.GovernancePolicyState(policy))
      case Some(EvolutionTargetSnapshot.WorkflowDefinitionState(_, workflow)) =>
        upsertWorkflow(workflow).as(EvolutionTargetSnapshot.WorkflowDefinitionState(target.projectId, workflow))
      case Some(EvolutionTargetSnapshot.DaemonAgentSpecState(spec, enabled))  =>
        upsertDaemon(spec, enabled).as(EvolutionTargetSnapshot.DaemonAgentSpecState(spec, enabled))
      case None                                                               =>
        deleteTarget(target)

  private def deleteTarget(target: EvolutionTarget): IO[EvolutionError, EvolutionTargetSnapshot] =
    target match
      case governance: EvolutionTarget.GovernancePolicyTarget =>
        currentGovernance(governance).flatMap {
          case Some(policy) =>
            archiveGovernancePolicy(policy).as(EvolutionTargetSnapshot.GovernancePolicyState(policy))
          case None         =>
            ZIO.fail(EvolutionError.TargetNotFound("governance_policy", governance.projectId.value))
        }
      case workflow: EvolutionTarget.WorkflowDefinitionTarget =>
        currentWorkflow(workflow.workflow.name).flatMap {
          case Some(existing) =>
            deleteWorkflow(existing).as(EvolutionTargetSnapshot.WorkflowDefinitionState(workflow.projectId, existing))
          case None           =>
            ZIO.fail(EvolutionError.TargetNotFound("workflow_definition", workflow.workflow.name))
        }
      case daemon: EvolutionTarget.DaemonAgentSpecTarget      =>
        currentDaemon(daemon.spec).flatMap {
          case Some(existing) =>
            deleteDaemon(existing._1).as(EvolutionTargetSnapshot.DaemonAgentSpecState(existing._1, existing._2))
          case None           =>
            ZIO.fail(EvolutionError.TargetNotFound("daemon_agent_spec", daemon.spec.id.value))
        }

  private def applyGovernance(
    target: EvolutionTarget.GovernancePolicyTarget
  ): IO[EvolutionError, (Option[EvolutionTargetSnapshot], EvolutionTargetSnapshot)] =
    for
      current <- currentGovernance(target)
      _       <- target.operation match
                   case EvolutionChangeOperation.Upsert => upsertGovernanceTarget(target)
                   case EvolutionChangeOperation.Delete => current match
                       case Some(policy) => archiveGovernancePolicy(policy)
                       case None         => ZIO.unit
      applied <- currentGovernance(target).map(_.orElse(current))
      value   <- ZIO
                   .fromOption(applied)
                   .orElseFail(EvolutionError.TargetNotFound("governance_policy", target.projectId.value))
    yield (
      current.map(EvolutionTargetSnapshot.GovernancePolicyState.apply),
      EvolutionTargetSnapshot.GovernancePolicyState(value),
    )

  private def applyWorkflow(
    target: EvolutionTarget.WorkflowDefinitionTarget
  ): IO[EvolutionError, (Option[EvolutionTargetSnapshot], EvolutionTargetSnapshot)] =
    for
      current <- currentWorkflow(target.workflow.name)
      _       <- target.operation match
                   case EvolutionChangeOperation.Upsert => upsertWorkflow(target.workflow)
                   case EvolutionChangeOperation.Delete => current match
                       case Some(existing) => deleteWorkflow(existing)
                       case None           => ZIO.unit
      applied <- target.operation match
                   case EvolutionChangeOperation.Upsert =>
                     currentWorkflow(target.workflow.name)
                   case EvolutionChangeOperation.Delete =>
                     ZIO.succeed(current)
      value   <- ZIO
                   .fromOption(applied.orElse(current))
                   .orElseFail(EvolutionError.TargetNotFound("workflow_definition", target.workflow.name))
    yield (
      current.map(EvolutionTargetSnapshot.WorkflowDefinitionState(target.projectId, _)),
      EvolutionTargetSnapshot.WorkflowDefinitionState(target.projectId, value),
    )

  private def applyDaemon(
    target: EvolutionTarget.DaemonAgentSpecTarget
  ): IO[EvolutionError, (Option[EvolutionTargetSnapshot], EvolutionTargetSnapshot)] =
    for
      current <- currentDaemon(target.spec)
      _       <- target.operation match
                   case EvolutionChangeOperation.Upsert => upsertDaemon(target.spec, target.enabled)
                   case EvolutionChangeOperation.Delete => deleteDaemon(target.spec)
      applied <- target.operation match
                   case EvolutionChangeOperation.Upsert => currentDaemon(target.spec)
                   case EvolutionChangeOperation.Delete => ZIO.succeed(current)
      value   <- ZIO
                   .fromOption(applied.orElse(current))
                   .orElseFail(EvolutionError.TargetNotFound("daemon_agent_spec", target.spec.id.value))
    yield (
      current.map((spec, enabled) => EvolutionTargetSnapshot.DaemonAgentSpecState(spec, enabled)),
      EvolutionTargetSnapshot.DaemonAgentSpecState(value._1, value._2),
    )

  private def currentGovernance(
    target: EvolutionTarget.GovernancePolicyTarget
  ): IO[EvolutionError, Option[GovernancePolicy]] =
    target.policyId match
      case Some(policyId) =>
        governanceRepository.get(policyId).map(Some(_)).catchAll {
          case _: PersistenceError.NotFound => ZIO.none
          case other                        => ZIO.fail(EvolutionError.PersistenceFailed(other.toString))
        }
      case None           =>
        governanceRepository.getActiveByProject(target.projectId).map(Some(_)).catchAll {
          case _: PersistenceError.NotFound => ZIO.none
          case other                        => ZIO.fail(EvolutionError.PersistenceFailed(other.toString))
        }

  private def upsertGovernanceTarget(target: EvolutionTarget.GovernancePolicyTarget): IO[EvolutionError, Unit] =
    Clock.instant.flatMap { now =>
      currentGovernance(target).flatMap {
        case Some(existing) =>
          governanceRepository
            .append(
              GovernancePolicyEvent.PolicyUpdated(
                policyId = existing.id,
                projectId = target.projectId,
                name = target.name,
                version = existing.version + 1,
                transitionRules = target.transitionRules,
                daemonTriggers = target.daemonTriggers,
                escalationRules = target.escalationRules,
                completionCriteria = target.completionCriteria,
                isDefault = target.isDefault,
                occurredAt = now,
              )
            )
            .mapError(error => EvolutionError.PersistenceFailed(error.toString))
        case None           =>
          val policyId = target.policyId.getOrElse(GovernancePolicyId.generate)
          governanceRepository
            .append(
              GovernancePolicyEvent.PolicyCreated(
                policyId = policyId,
                projectId = target.projectId,
                name = target.name,
                version = 1,
                transitionRules = target.transitionRules,
                daemonTriggers = target.daemonTriggers,
                escalationRules = target.escalationRules,
                completionCriteria = target.completionCriteria,
                isDefault = target.isDefault,
                occurredAt = now,
              )
            )
            .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      }
    }

  private def upsertGovernancePolicy(policy: GovernancePolicy): IO[EvolutionError, Unit] =
    Clock.instant.flatMap { now =>
      governanceRepository
        .append(
          GovernancePolicyEvent.PolicyUpdated(
            policyId = policy.id,
            projectId = policy.projectId,
            name = policy.name,
            version = policy.version + 1,
            transitionRules = policy.transitionRules,
            daemonTriggers = policy.daemonTriggers,
            escalationRules = policy.escalationRules,
            completionCriteria = policy.completionCriteria,
            isDefault = policy.isDefault,
            occurredAt = now,
          )
        )
        .mapError(error => EvolutionError.PersistenceFailed(error.toString))
    }

  private def archiveGovernancePolicy(policy: GovernancePolicy): IO[EvolutionError, Unit] =
    Clock.instant.flatMap { now =>
      governanceRepository
        .append(
          GovernancePolicyEvent.PolicyArchived(
            policyId = policy.id,
            projectId = policy.projectId,
            archivedAt = now,
            occurredAt = now,
          )
        )
        .mapError(error => EvolutionError.PersistenceFailed(error.toString))
    }

  private def currentWorkflow(name: String): IO[EvolutionError, Option[WorkflowDefinition]] =
    workflowService.getWorkflowByName(name).mapError(mapWorkflowError)

  private def upsertWorkflow(workflow: WorkflowDefinition): IO[EvolutionError, Unit] =
    currentWorkflow(workflow.name).flatMap {
      case Some(existing) => workflowService.updateWorkflow(workflow.copy(id = existing.id)).mapError(mapWorkflowError)
      case None           => workflowService.createWorkflow(workflow.copy(id = None)).unit.mapError(mapWorkflowError)
    }

  private def deleteWorkflow(workflow: WorkflowDefinition): IO[EvolutionError, Unit] =
    workflow.id.flatMap(_.toLongOption) match
      case Some(id) => workflowService.deleteWorkflow(id).mapError(mapWorkflowError)
      case None     => ZIO.fail(EvolutionError.TargetNotFound("workflow_definition", workflow.name))

  private def currentDaemon(spec: DaemonAgentSpec): IO[EvolutionError, Option[(DaemonAgentSpec, Boolean)]] =
    daemonRepository.get(spec.id).map(Some(_)).catchAll {
      case _: PersistenceError.NotFound => ZIO.none
      case other                        => ZIO.fail(EvolutionError.PersistenceFailed(other.toString))
    }.flatMap {
      case None        => ZIO.none
      case Some(value) =>
        daemonEnabled(value).map(enabled => Some(value -> enabled))
    }

  private def upsertDaemon(spec: DaemonAgentSpec, enabled: Boolean): IO[EvolutionError, Unit] =
    daemonRepository
      .save(spec.copy(builtIn = false, governed = false))
      .mapError(error => EvolutionError.PersistenceFailed(error.toString)) *>
      configRepository
        .upsertSetting(enabledKey(spec.projectId, spec.daemonKey), enabled.toString)
        .mapError(error => EvolutionError.PersistenceFailed(error.toString))

  private def deleteDaemon(spec: DaemonAgentSpec): IO[EvolutionError, Unit] =
    daemonRepository.delete(spec.id).mapError(error => EvolutionError.PersistenceFailed(error.toString)) *>
      configRepository
        .deleteSetting(enabledKey(spec.projectId, spec.daemonKey))
        .catchAll {
          case _: DbPersistenceError.NotFound => ZIO.unit
          case other                          => ZIO.fail(EvolutionError.PersistenceFailed(other.toString))
        }

  private def daemonEnabled(spec: DaemonAgentSpec): IO[EvolutionError, Boolean] =
    configRepository
      .getSetting(enabledKey(spec.projectId, spec.daemonKey))
      .mapError(error => EvolutionError.PersistenceFailed(error.toString))
      .map(_.fold(true)(_.value.trim.equalsIgnoreCase("true")))

  private def enabledKey(projectId: shared.ids.Ids.ProjectId, daemonKey: String): String =
    s"daemons.${projectId.value}.${specKey(daemonKey)}.enabled"

  private def specKey(value: String): String =
    daemon.entity.DaemonAgentSpec.normalizeKey(value)

  private def mapWorkflowError(error: WorkflowServiceError): EvolutionError =
    error match
      case WorkflowServiceError.PersistenceFailed(value) => EvolutionError.PersistenceFailed(value.toString)
      case other                                         => EvolutionError.PersistenceFailed(other.toString)
