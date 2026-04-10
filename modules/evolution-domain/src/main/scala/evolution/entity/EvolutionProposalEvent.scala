package evolution.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ DecisionId, EvolutionProposalId, ProjectId }

sealed trait EvolutionProposalEvent derives JsonCodec, Schema:
  def proposalId: EvolutionProposalId
  def projectId: ProjectId
  def occurredAt: Instant

object EvolutionProposalEvent:
  final case class Proposed(
    proposalId: EvolutionProposalId,
    projectId: ProjectId,
    title: String,
    rationale: String,
    target: EvolutionTarget,
    template: Option[EvolutionTemplateKind],
    proposedBy: String,
    summary: String,
    decisionId: Option[DecisionId],
    occurredAt: Instant,
  ) extends EvolutionProposalEvent

  final case class Approved(
    proposalId: EvolutionProposalId,
    projectId: ProjectId,
    decisionId: DecisionId,
    approvedBy: String,
    summary: String,
    occurredAt: Instant,
  ) extends EvolutionProposalEvent

  final case class Applied(
    proposalId: EvolutionProposalId,
    projectId: ProjectId,
    appliedBy: String,
    summary: String,
    baselineSnapshot: Option[EvolutionTargetSnapshot],
    appliedSnapshot: EvolutionTargetSnapshot,
    occurredAt: Instant,
  ) extends EvolutionProposalEvent

  final case class RolledBack(
    proposalId: EvolutionProposalId,
    projectId: ProjectId,
    rolledBackBy: String,
    summary: String,
    rollbackSnapshot: EvolutionTargetSnapshot,
    occurredAt: Instant,
  ) extends EvolutionProposalEvent
