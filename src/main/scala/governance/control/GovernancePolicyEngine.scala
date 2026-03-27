package governance.control

import zio.*

import governance.entity.*

enum GovernancePolicyEngineError:
  case InvalidIssueType

final case class GovernanceEvaluationContext(
  issueType: String,
  transition: GovernanceTransition,
  satisfiedGates: Set[GovernanceGate] = Set.empty,
  tags: Set[String] = Set.empty,
  humanApprovalGranted: Boolean = false,
)

final case class GovernanceTransitionDecision(
  allowed: Boolean,
  requiredGates: Set[GovernanceGate],
  missingGates: Set[GovernanceGate],
  humanApprovalRequired: Boolean,
  daemonTriggers: List[GovernanceDaemonTrigger],
  escalationRules: List[GovernanceEscalationRule],
  completionCriteria: Option[GovernanceCompletionCriteria],
  reason: Option[String],
)

trait GovernancePolicyEngine:
  def evaluateTransition(
    policy: GovernancePolicy,
    context: GovernanceEvaluationContext,
  ): IO[GovernancePolicyEngineError, GovernanceTransitionDecision]

object GovernancePolicyEngine:
  def evaluateTransition(
    policy: GovernancePolicy,
    context: GovernanceEvaluationContext,
  ): ZIO[GovernancePolicyEngine, GovernancePolicyEngineError, GovernanceTransitionDecision] =
    ZIO.serviceWithZIO[GovernancePolicyEngine](_.evaluateTransition(policy, context))

  val live: ULayer[GovernancePolicyEngine] =
    ZLayer.succeed(GovernancePolicyEngineLive())

final private case class GovernancePolicyEngineLive() extends GovernancePolicyEngine:

  override def evaluateTransition(
    policy: GovernancePolicy,
    context: GovernanceEvaluationContext,
  ): IO[GovernancePolicyEngineError, GovernanceTransitionDecision] =
    val issueType = context.issueType.trim
    if issueType.isEmpty then ZIO.fail(GovernancePolicyEngineError.InvalidIssueType)
    else
      val transitionRules = matchingTransitionRules(policy, context.transition, issueType)
      val completionRule  = matchingCompletionCriteria(policy, issueType, context.transition.to)
      val requiredGates   =
        transitionRules.flatMap(_.requiredGates).toSet ++ completionRule.toList.flatMap(_.requiredGates)
      val missingGates    = requiredGates.diff(context.satisfiedGates)
      val humanRequired   =
        transitionRules.exists(_.requireHumanApproval) || completionRule.exists(_.requireHumanApproval)
      val blockedByTags   = transitionRules.exists(rule => rule.blockedTags.exists(context.tags.contains))
      val triggers        =
        policy.daemonTriggers.filter(trigger =>
          trigger.enabled &&
          trigger.transition == context.transition &&
          (trigger.issueTypes.isEmpty || trigger.issueTypes.contains(issueType))
        )
      val escalations     =
        policy.escalationRules.filter(rule =>
          rule.transition == context.transition &&
          rule.gate.forall(missingGates.contains)
        )
      val approvalMissing = humanRequired && !context.humanApprovalGranted
      val allowed         = !blockedByTags && missingGates.isEmpty && !approvalMissing
      val reason          =
        if blockedByTags then Some(s"Blocked by governance tags for ${renderTransition(context.transition)}")
        else if missingGates.nonEmpty then
          Some(s"Missing required gates: ${missingGates.toList.map(renderGate).sorted.mkString(", ")}")
        else if approvalMissing then Some(s"Human approval required for ${renderTransition(context.transition)}")
        else None

      ZIO.succeed(
        GovernanceTransitionDecision(
          allowed = allowed,
          requiredGates = requiredGates,
          missingGates = missingGates,
          humanApprovalRequired = humanRequired,
          daemonTriggers = triggers,
          escalationRules = escalations,
          completionCriteria = completionRule,
          reason = reason,
        )
      )

  private def matchingTransitionRules(
    policy: GovernancePolicy,
    transition: GovernanceTransition,
    issueType: String,
  ): List[GovernanceTransitionRule] =
    policy.transitionRules.filter(rule =>
      rule.transition == transition &&
      (rule.allowedIssueTypes.isEmpty || rule.allowedIssueTypes.contains(issueType))
    )

  private def matchingCompletionCriteria(
    policy: GovernancePolicy,
    issueType: String,
    targetStage: GovernanceLifecycleStage,
  ): Option[GovernanceCompletionCriteria] =
    Option.when(targetStage == GovernanceLifecycleStage.Done) {
      policy.completionCriteria.find(_.issueType == issueType)
    }.flatten

  private def renderTransition(transition: GovernanceTransition): String =
    s"${transition.from.toString} -> ${transition.to.toString}"

  private def renderGate(gate: GovernanceGate): String =
    gate match
      case GovernanceGate.Custom(name) => name.trim
      case other                       => other.toString
