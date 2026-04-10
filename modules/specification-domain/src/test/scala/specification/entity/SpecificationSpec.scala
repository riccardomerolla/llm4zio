package specification.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ IssueId, SpecificationId }

object SpecificationSpec extends ZIOSpecDefault:

  private val author = SpecificationAuthor(SpecificationAuthorKind.Human, "user-1", "User 1")
  private val now    = Instant.parse("2026-03-26T12:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("SpecificationSpec")(
      test("fromEvents rebuilds revisions links and status") {
        val specId = SpecificationId("spec-1")
        val events = List[SpecificationEvent](
          SpecificationEvent.Created(
            specificationId = specId,
            title = "Initial Spec",
            content = "# Spec\n\nInitial",
            author = author,
            status = SpecificationStatus.Draft,
            linkedPlanRef = Some("planner:42"),
            occurredAt = now,
          ),
          SpecificationEvent.ReviewCommentAdded(
            specificationId = specId,
            comment = SpecificationReviewComment("comment-1", author, "Needs more detail", now.plusSeconds(10)),
            occurredAt = now.plusSeconds(10),
          ),
          SpecificationEvent.Revised(
            specificationId = specId,
            version = 2,
            title = "Initial Spec",
            beforeContent = "# Spec\n\nInitial",
            afterContent = "# Spec\n\nUpdated",
            author = author,
            status = SpecificationStatus.InRefinement,
            linkedPlanRef = Some("planner:42"),
            occurredAt = now.plusSeconds(20),
          ),
          SpecificationEvent.IssuesLinked(specId, List(IssueId("issue-1"), IssueId("issue-2")), now.plusSeconds(30)),
          SpecificationEvent.Approved(specId, author, now.plusSeconds(40)),
        )

        val rebuilt = Specification.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.status == SpecificationStatus.Approved),
          rebuilt.exists(_.linkedPlanRef.contains("planner:42")),
          rebuilt.exists(_.linkedIssueIds == List(IssueId("issue-1"), IssueId("issue-2"))),
          rebuilt.exists(_.revisions.map(_.version) == List(1, 2)),
        )
      },
      test("diff returns before and after content for selected versions") {
        val spec = Specification(
          id = SpecificationId("spec-2"),
          title = "Spec",
          content = "after",
          status = SpecificationStatus.InRefinement,
          version = 2,
          revisions = List(
            SpecificationRevision(1, "Spec", "before", author, SpecificationStatus.Draft, now),
            SpecificationRevision(2, "Spec", "after", author, SpecificationStatus.InRefinement, now.plusSeconds(10)),
          ),
          linkedIssueIds = Nil,
          linkedPlanRef = None,
          author = author,
          reviewComments = Nil,
          createdAt = now,
          updatedAt = now.plusSeconds(10),
        )

        val diff = Specification.diff(spec, 1, 2)

        assertTrue(
          diff.exists(_.beforeContent == "before"),
          diff.exists(_.afterContent == "after"),
        )
      },
    )
