package plan.entity

import java.time.Instant

import zio.test.*

import governance.entity.GovernanceGate
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }

object PlanSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  private val draft = PlanTaskDraft(
    draftId = "issue-1",
    title = "Design data model",
    description = "Define planner data structures",
    estimate = Some("M"),
  )

  def spec: Spec[Any, Nothing] =
    suite("PlanSpec")(
      test("fromEvents rebuilds validation issue links and versions") {
        val planId = PlanId("plan-1")
        val events = List[PlanEvent](
          PlanEvent.Created(
            planId = planId,
            conversationId = 42L,
            workspaceId = Some("ws-1"),
            specificationId = Some(SpecificationId("spec-1")),
            summary = "Planner plan",
            rationale = "Break the work into atomic tasks",
            drafts = List(draft),
            occurredAt = now,
          ),
          PlanEvent.Validated(
            planId = planId,
            result = PlanValidationResult(
              status = PlanValidationStatus.Passed,
              requiredGates = List(GovernanceGate.PlanningReview),
              validatedAt = now.plusSeconds(10),
            ),
            occurredAt = now.plusSeconds(10),
          ),
          PlanEvent.TasksCreated(
            planId = planId,
            issueIds = List(IssueId("issue-a")),
            occurredAt = now.plusSeconds(20),
          ),
          PlanEvent.Revised(
            planId = planId,
            version = 2,
            workspaceId = Some("ws-1"),
            specificationId = Some(SpecificationId("spec-1")),
            summary = "Planner plan revised",
            rationale = "Added rollout work",
            drafts = List(draft, draft.copy(draftId = "issue-2", title = "Rollout", description = "Ship it")),
            occurredAt = now.plusSeconds(30),
          ),
        )

        val rebuilt = Plan.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.summary == "Planner plan revised"),
          rebuilt.exists(_.linkedIssueIds == List(IssueId("issue-a"))),
          rebuilt.exists(_.versions.map(_.version) == List(1, 2)),
          rebuilt.exists(_.drafts.map(_.draftId) == List("issue-1", "issue-2")),
          rebuilt.exists(_.validation.isEmpty),
        )
      },
      test("blocked validation keeps the plan in draft status") {
        val planId = PlanId("plan-2")
        val events = List[PlanEvent](
          PlanEvent.Created(
            planId = planId,
            conversationId = 7L,
            workspaceId = Some("ws-1"),
            specificationId = None,
            summary = "Plan",
            rationale = "Need review",
            drafts = List(draft),
            occurredAt = now,
          ),
          PlanEvent.Validated(
            planId = planId,
            result = PlanValidationResult(
              status = PlanValidationStatus.Blocked,
              requiredGates = List(GovernanceGate.PlanningReview),
              missingGates = List(GovernanceGate.PlanningReview),
              reason = Some("Missing required gates: PlanningReview"),
              validatedAt = now.plusSeconds(10),
            ),
            occurredAt = now.plusSeconds(10),
          ),
        )

        val rebuilt = Plan.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.status == PlanStatus.Draft),
          rebuilt.exists(_.validation.exists(_.status == PlanValidationStatus.Blocked)),
        )
      },
    )
