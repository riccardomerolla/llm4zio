package governance.control

import java.time.Instant

import zio.test.*

import governance.entity.*
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

object GovernancePolicyEngineSpec extends ZIOSpecDefault:

  private val engine = GovernancePolicyEngineLive()
  private val now    = Instant.parse("2026-03-26T11:00:00Z")

  private val dispatchTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.Todo,
    to = GovernanceLifecycleStage.InProgress,
    action = GovernanceLifecycleAction.Dispatch,
  )

  private val approveTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.HumanReview,
    to = GovernanceLifecycleStage.Done,
    action = GovernanceLifecycleAction.Approve,
  )

  private def policy(
    rules: List[GovernanceTransitionRule] = Nil,
    triggers: List[GovernanceDaemonTrigger] = Nil,
    escalations: List[GovernanceEscalationRule] = Nil,
    completionCriteria: List[GovernanceCompletionCriteria] = Nil,
  ): GovernancePolicy =
    GovernancePolicy(
      id = GovernancePolicyId("policy-1"),
      projectId = ProjectId("project-1"),
      name = "Governance",
      version = 1,
      transitionRules = rules,
      daemonTriggers = triggers,
      escalationRules = escalations,
      completionCriteria = completionCriteria,
      isDefault = false,
      createdAt = now,
      updatedAt = now,
    )

  def spec: Spec[Any, Any] =
    suite("GovernancePolicyEngineSpec")(
      test("default no-op policy allows transitions") {
        for
          decision <- engine.evaluateTransition(
                        GovernancePolicy.noOp,
                        GovernanceEvaluationContext(issueType = "task", transition = dispatchTransition),
                      )
        yield assertTrue(decision.allowed, decision.requiredGates.isEmpty)
      },
      test("missing required gates blocks transition") {
        for
          decision <- engine.evaluateTransition(
                        policy(
                          rules = List(
                            GovernanceTransitionRule(
                              transition = dispatchTransition,
                              requiredGates = List(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
                            )
                          )
                        ),
                        GovernanceEvaluationContext(issueType = "task", transition = dispatchTransition),
                      )
        yield assertTrue(
          !decision.allowed,
          decision.missingGates == Set(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
        )
      },
      test("satisfied gates allow transition") {
        for
          decision <- engine.evaluateTransition(
                        policy(
                          rules = List(
                            GovernanceTransitionRule(
                              transition = dispatchTransition,
                              requiredGates = List(GovernanceGate.SpecReview),
                            )
                          )
                        ),
                        GovernanceEvaluationContext(
                          issueType = "task",
                          transition = dispatchTransition,
                          satisfiedGates = Set(GovernanceGate.SpecReview),
                        ),
                      )
        yield assertTrue(decision.allowed, decision.missingGates.isEmpty)
      },
      test("human approval requirement blocks until granted") {
        for
          blocked <- engine.evaluateTransition(
                       policy(
                         rules = List(
                           GovernanceTransitionRule(
                             transition = approveTransition,
                             requireHumanApproval = true,
                           )
                         )
                       ),
                       GovernanceEvaluationContext(issueType = "task", transition = approveTransition),
                     )
          allowed <- engine.evaluateTransition(
                       policy(
                         rules = List(
                           GovernanceTransitionRule(
                             transition = approveTransition,
                             requireHumanApproval = true,
                           )
                         )
                       ),
                       GovernanceEvaluationContext(
                         issueType = "task",
                         transition = approveTransition,
                         humanApprovalGranted = true,
                       ),
                     )
        yield assertTrue(!blocked.allowed, allowed.allowed)
      },
      test("completion criteria contributes gates for done transitions") {
        for
          decision <- engine.evaluateTransition(
                        policy(
                          completionCriteria = List(
                            GovernanceCompletionCriteria(
                              issueType = "feature",
                              requiredGates = List(GovernanceGate.CodeReview, GovernanceGate.CiPassed),
                            )
                          )
                        ),
                        GovernanceEvaluationContext(issueType = "feature", transition = approveTransition),
                      )
        yield assertTrue(
          !decision.allowed,
          decision.completionCriteria.exists(_.issueType == "feature"),
          decision.missingGates == Set(GovernanceGate.CodeReview, GovernanceGate.CiPassed),
        )
      },
      test("daemon triggers and escalations are returned for matching transitions") {
        val escalation = GovernanceEscalationRule(
          id = "notify-maintainer",
          transition = dispatchTransition,
          kind = GovernanceEscalationKind.NotifyHuman,
          target = "maintainer",
          gate = Some(GovernanceGate.SpecReview),
          afterSeconds = 300L,
        )

        for
          decision <- engine.evaluateTransition(
                        policy(
                          rules = List(
                            GovernanceTransitionRule(
                              transition = dispatchTransition,
                              requiredGates = List(GovernanceGate.SpecReview),
                            )
                          ),
                          triggers = List(
                            GovernanceDaemonTrigger(
                              id = "dispatch-daemon",
                              transition = dispatchTransition,
                              agentName = "planner",
                              issueTypes = List("task"),
                            )
                          ),
                          escalations = List(escalation),
                        ),
                        GovernanceEvaluationContext(issueType = "task", transition = dispatchTransition),
                      )
        yield assertTrue(
          decision.daemonTriggers.map(_.id) == List("dispatch-daemon"),
          decision.escalationRules == List(escalation),
        )
      },
    )
