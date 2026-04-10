package decision.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.IssueId

object DecisionSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("DecisionSpec")(
      test("fromEvents rebuilds resolved decision state") {
        val events = List[DecisionEvent](
          DecisionEvent.Created(
            decisionId = shared.ids.Ids.DecisionId("decision-1"),
            title = "Review issue #1",
            context = "Check the proposed changes",
            action = DecisionAction.ReviewIssue,
            source = DecisionSource(
              kind = DecisionSourceKind.IssueReview,
              referenceId = "issue-1",
              summary = "Review required",
              workspaceId = Some("workspace-a"),
              issueId = Some(IssueId("issue-1")),
            ),
            urgency = DecisionUrgency.High,
            deadlineAt = Some(now.plusSeconds(600)),
            occurredAt = now,
          ),
          DecisionEvent.Resolved(
            decisionId = shared.ids.Ids.DecisionId("decision-1"),
            resolution = DecisionResolution(
              kind = DecisionResolutionKind.Approved,
              actor = "reviewer",
              summary = "Looks good",
              respondedAt = now.plusSeconds(120),
            ),
            occurredAt = now.plusSeconds(120),
          ),
        )

        val result = Decision.fromEvents(events)

        assertTrue(
          result.exists(_.status == DecisionStatus.Resolved),
          result.map(_.resolution.map(_.kind)) == Right(Some(DecisionResolutionKind.Approved)),
          result.flatMap(_.responseTimeMillis.toRight("missing response")) == Right(120000L),
        )
      },
      test("fromEvents keeps expired and escalated timestamps") {
        val id     = shared.ids.Ids.DecisionId("decision-2")
        val events = List[DecisionEvent](
          DecisionEvent.Created(
            decisionId = id,
            title = "Review issue #2",
            context = "Pending approval",
            action = DecisionAction.ReviewIssue,
            source = DecisionSource(
              DecisionSourceKind.IssueReview,
              "issue-2",
              "Review required",
              issueId = Some(IssueId("issue-2")),
            ),
            urgency = DecisionUrgency.Medium,
            deadlineAt = Some(now.plusSeconds(60)),
            occurredAt = now,
          ),
          DecisionEvent.Expired(id, now.plusSeconds(61)),
          DecisionEvent.Escalated(id, "Deadline exceeded", now.plusSeconds(62)),
        )

        val result = Decision.fromEvents(events)

        assertTrue(
          result.exists(_.status == DecisionStatus.Escalated),
          result.exists(_.expiredAt.contains(now.plusSeconds(61))),
          result.exists(_.escalatedAt.contains(now.plusSeconds(62))),
        )
      },
    )
