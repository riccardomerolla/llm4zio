package evolution.control

import _root_.config.entity.WorkflowDefinition
import daemon.entity.{ DaemonAgentSpec, DaemonTriggerCondition }
import evolution.entity.{ EvolutionTarget, EvolutionTemplateKind }
import governance.entity.{ GovernanceCompletionCriteria, GovernanceGate, GovernancePolicy }
import shared.ids.Ids.ProjectId

object EvolutionTemplates:
  def addQualityGate(
    policy: GovernancePolicy,
    issueType: String,
    gate: GovernanceGate,
  ): (EvolutionTemplateKind, EvolutionTarget.GovernancePolicyTarget) =
    EvolutionTemplateKind.AddQualityGate ->
      EvolutionTarget.GovernancePolicyTarget(
        projectId = policy.projectId,
        policyId = Some(policy.id),
        name = policy.name,
        transitionRules = policy.transitionRules,
        daemonTriggers = policy.daemonTriggers,
        escalationRules = policy.escalationRules,
        completionCriteria = policy.completionCriteria.map {
          case criteria if criteria.issueType == issueType =>
            criteria.copy(requiredGates = (criteria.requiredGates :+ gate).distinct)
          case criteria                                    => criteria
        } match
          case existing if existing.exists(_.issueType == issueType) => existing
          case existing                                              =>
            existing :+ GovernanceCompletionCriteria(issueType = issueType, requiredGates = List(gate)),
        isDefault = policy.isDefault,
      )

  def changeTestingStrategy(
    projectId: ProjectId,
    workflow: WorkflowDefinition,
    additionalSteps: List[String],
  ): (EvolutionTemplateKind, EvolutionTarget.WorkflowDefinitionTarget) =
    EvolutionTemplateKind.ChangeTestingStrategy ->
      EvolutionTarget.WorkflowDefinitionTarget(
        projectId = projectId,
        workflow = workflow.copy(steps = (workflow.steps ++ additionalSteps).distinct),
      )

  def addDaemonAgent(spec: DaemonAgentSpec): (EvolutionTemplateKind, EvolutionTarget.DaemonAgentSpecTarget) =
    EvolutionTemplateKind.AddDaemonAgent ->
      EvolutionTarget.DaemonAgentSpecTarget(
        spec = spec.copy(
          builtIn = false,
          governed = false,
          trigger = spec.trigger match
            case _: DaemonTriggerCondition.EventDriven => spec.trigger
            case other                                 => other,
        )
      )
