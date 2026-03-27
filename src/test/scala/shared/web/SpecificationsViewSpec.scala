package shared.web

import java.time.Instant

import zio.test.*

import issues.entity.{ AgentIssue, IssueState }
import shared.ids.Ids.{ IssueId, SpecificationId }
import specification.entity.*

object SpecificationsViewSpec extends ZIOSpecDefault:

  private val now    = Instant.parse("2026-03-26T10:00:00Z")
  private val author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner Agent")

  private val specification = Specification(
    id = SpecificationId("spec-1"),
    title = "Planner specification",
    content = "# Spec\n\nCurrent body",
    status = SpecificationStatus.InRefinement,
    version = 2,
    revisions = List(
      SpecificationRevision(
        1,
        "Planner specification",
        "before",
        author,
        SpecificationStatus.Draft,
        now.minusSeconds(60),
      ),
      SpecificationRevision(2, "Planner specification", "after", author, SpecificationStatus.InRefinement, now),
    ),
    linkedIssueIds = List(IssueId("issue-1")),
    linkedPlanRef = Some("planner:42"),
    author = author,
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
    tags = List("spec:spec-1"),
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
    externalRef = Some("spec:spec-1"),
    externalUrl = Some("/specifications/spec-1"),
  )

  def spec: Spec[Any, Nothing] =
    suite("SpecificationsViewSpec")(
      test("list page renders specification cards") {
        val html = SpecificationsView.page(
          List(
            SpecificationListItem(
              id = specification.id.value,
              title = specification.title,
              status = specification.status,
              version = specification.version,
              linkedPlanRef = specification.linkedPlanRef,
              linkedIssueCount = specification.linkedIssueIds.size,
              updatedAt = specification.updatedAt,
            )
          )
        )
        assertTrue(
          html.contains("Specifications"),
          html.contains("/specifications/spec-1"),
          html.contains("planner:42"),
          html.contains("1 linked issue(s)"),
        )
      },
      test("detail page renders revision form and linked issues") {
        val html = SpecificationsView.detailPage(
          specification = specification,
          linkedIssues = List(issue),
          diff = Some(SpecificationDiff(1, 2, "before", "after")),
        )
        assertTrue(
          html.contains("Revise specification"),
          html.contains("Open planner conversation"),
          html.contains("/issues/issue-1"),
          html.contains("Version history"),
          html.contains("Version 1"),
          html.contains("Version 2"),
        )
      },
    )
