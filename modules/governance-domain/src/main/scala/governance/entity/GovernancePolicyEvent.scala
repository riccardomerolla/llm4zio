package governance.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

sealed trait GovernancePolicyEvent derives JsonCodec, Schema:
  def policyId: GovernancePolicyId
  def projectId: ProjectId
  def occurredAt: Instant

object GovernancePolicyEvent:
  final case class PolicyCreated(
    policyId: GovernancePolicyId,
    projectId: ProjectId,
    name: String,
    version: Int,
    transitionRules: List[GovernanceTransitionRule],
    daemonTriggers: List[GovernanceDaemonTrigger],
    escalationRules: List[GovernanceEscalationRule],
    completionCriteria: List[GovernanceCompletionCriteria],
    isDefault: Boolean,
    occurredAt: Instant,
  ) extends GovernancePolicyEvent

  final case class PolicyUpdated(
    policyId: GovernancePolicyId,
    projectId: ProjectId,
    name: String,
    version: Int,
    transitionRules: List[GovernanceTransitionRule],
    daemonTriggers: List[GovernanceDaemonTrigger],
    escalationRules: List[GovernanceEscalationRule],
    completionCriteria: List[GovernanceCompletionCriteria],
    isDefault: Boolean,
    occurredAt: Instant,
  ) extends GovernancePolicyEvent

  final case class PolicyArchived(
    policyId: GovernancePolicyId,
    projectId: ProjectId,
    archivedAt: Instant,
    occurredAt: Instant,
  ) extends GovernancePolicyEvent
