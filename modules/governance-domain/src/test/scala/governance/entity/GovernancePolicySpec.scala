package governance.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

object GovernancePolicySpec extends ZIOSpecDefault:

  private val dispatchTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.Todo,
    to = GovernanceLifecycleStage.InProgress,
    action = GovernanceLifecycleAction.Dispatch,
  )

  private val now = Instant.parse("2026-03-26T09:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("GovernancePolicySpec")(
      test("fromEvents rebuilds the latest policy version") {
        val policyId  = GovernancePolicyId("policy-1")
        val projectId = ProjectId("project-1")
        val created   = GovernancePolicyEvent.PolicyCreated(
          policyId = policyId,
          projectId = projectId,
          name = "Initial Policy",
          version = 1,
          transitionRules = List(
            GovernanceTransitionRule(
              transition = dispatchTransition,
              requiredGates = List(GovernanceGate.SpecReview),
            )
          ),
          daemonTriggers = Nil,
          escalationRules = Nil,
          completionCriteria = Nil,
          isDefault = false,
          occurredAt = now,
        )
        val updated   = GovernancePolicyEvent.PolicyUpdated(
          policyId = policyId,
          projectId = projectId,
          name = "Updated Policy",
          version = 2,
          transitionRules = List(
            GovernanceTransitionRule(
              transition = dispatchTransition,
              requiredGates = List(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
              requireHumanApproval = true,
            )
          ),
          daemonTriggers = List(
            GovernanceDaemonTrigger(
              id = "dispatch-daemon",
              transition = dispatchTransition,
              agentName = "planner",
            )
          ),
          escalationRules = List(
            GovernanceEscalationRule(
              id = "escalate-dispatch",
              transition = dispatchTransition,
              kind = GovernanceEscalationKind.NotifyHuman,
              target = "maintainer",
              gate = Some(GovernanceGate.PlanningReview),
              afterSeconds = 900L,
            )
          ),
          completionCriteria = List(
            GovernanceCompletionCriteria(
              issueType = "feature",
              requiredGates = List(GovernanceGate.CodeReview, GovernanceGate.CiPassed),
              requireHumanApproval = true,
            )
          ),
          isDefault = false,
          occurredAt = now.plusSeconds(60),
        )

        val rebuilt = GovernancePolicy.fromEvents(List(created, updated))

        assertTrue(
          rebuilt.exists(_.name == "Updated Policy"),
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.transitionRules.head.requiredGates == List(
            GovernanceGate.SpecReview,
            GovernanceGate.PlanningReview,
          )),
          rebuilt.exists(_.daemonTriggers.map(_.id) == List("dispatch-daemon")),
          rebuilt.exists(_.completionCriteria.map(_.issueType) == List("feature")),
        )
      },
      test("noOp policy preserves permissive defaults") {
        assertTrue(
          GovernancePolicy.noOp.isDefault,
          GovernancePolicy.noOp.version == 0,
          GovernancePolicy.noOp.transitionRules.isEmpty,
          GovernancePolicy.noOp.daemonTriggers.isEmpty,
          GovernancePolicy.noOp.completionCriteria.isEmpty,
        )
      },
    )
