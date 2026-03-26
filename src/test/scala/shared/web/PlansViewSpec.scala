package shared.web

import java.time.Instant

import zio.test.*

import governance.entity.GovernanceGate
import issues.entity.{ AgentIssue, IssueState }
import plan.entity.*
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }
import specification.entity.*

object PlansViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T10:00:00Z")

  private val plan = Plan(
    id = PlanId("plan-1"),
    conversationId = 42L,
    workspaceId = Some("ws-1"),
    specificationId = Some(SpecificationId("spec-1")),
    summary = "Planner plan",
    rationale = "Break down implementation into atomic tasks.",
    status = PlanStatus.Executing,
    version = 2,
    drafts = List(
      PlanTaskDraft("issue-1", "Model", "Create the model", included = true),
      PlanTaskDraft("issue-2", "UI", "Create the UI", dependencyDraftIds = List("issue-1"), included = true),
    ),
    validation = Some(
      PlanValidationResult(
        status = PlanValidationStatus.Passed,
        requiredGates = List(GovernanceGate.PlanningReview),
        validatedAt = now.minusSeconds(5),
      )
    ),
    linkedIssueIds = List(IssueId("issue-1")),
    versions = List(
      PlanVersion(1, "Planner plan", "Initial", Nil, None, PlanStatus.Draft, now.minusSeconds(60)),
      PlanVersion(2, "Planner plan", "Revised", Nil, None, PlanStatus.Executing, now),
    ),
    createdAt = now.minusSeconds(60),
    updatedAt = now,
  )

  private val specification = Specification(
    id = SpecificationId("spec-1"),
    title = "Planner spec",
    content = "content",
    status = SpecificationStatus.Approved,
    version = 1,
    revisions = Nil,
    linkedIssueIds = List(IssueId("issue-1")),
    linkedPlanRef = Some("plan:plan-1"),
    author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner Agent"),
    reviewComments = Nil,
    createdAt = now.minusSeconds(60),
    updatedAt = now,
  )

  private val issue = AgentIssue(
    id = IssueId("issue-1"),
    runId = None,
    conversationId = None,
    title = "Implement planner",
    description = "Build it",
    issueType = "task",
    priority = "high",
    requiredCapabilities = List("scala"),
    state = IssueState.Backlog(now),
    tags = List("plan:plan-1"),
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
    externalRef = Some("spec:spec-1"),
    externalUrl = Some("/specifications/spec-1"),
  )

  def spec: Spec[Any, Nothing] =
    suite("PlansViewSpec")(
      test("list page renders plan cards") {
        val html = PlansView.page(
          List(
            PlanListItem(
              id = plan.id.value,
              summary = plan.summary,
              status = plan.status,
              version = plan.version,
              specificationId = plan.specificationId.map(_.value),
              linkedIssueCount = plan.linkedIssueIds.size,
              workspaceId = plan.workspaceId,
              updatedAt = plan.updatedAt,
            )
          )
        )
        assertTrue(
          html.contains("Plans"),
          html.contains("/plans/plan-1"),
          html.contains("Spec spec-1"),
          html.contains("1 linked issue(s)"),
        )
      },
      test("detail page renders validation traceability and versions") {
        val html = PlansView.detailPage(plan, Some(specification), List(issue))
        assertTrue(
          html.contains("Decomposition rationale"),
          html.contains("Validation"),
          html.contains("Open linked specification"),
          html.contains("Version history"),
          html.contains("Implement planner"),
        )
      },
    )
