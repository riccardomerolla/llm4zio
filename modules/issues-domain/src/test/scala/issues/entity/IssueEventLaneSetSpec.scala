package issues.entity

import java.time.Instant

import zio.Scope
import zio.test.*

import shared.ids.Ids.IssueId

object IssueEventLaneSetSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-15T10:00:00Z")

  private def created(id: String): IssueEvent.Created =
    IssueEvent.Created(
      issueId = IssueId(id),
      title = "Some ticket",
      description = "Description",
      issueType = "feature",
      priority = "medium",
      occurredAt = now,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("IssueEvent.LaneSet")(
    test("freshly-created issue defaults to TicketLane.Custom") {
      val issue = AgentIssue.fromEvents(List(created("issue-1"))).toOption.get
      assertTrue(issue.lane == TicketLane.Custom)
    },
    test("LaneSet event updates the lane on replay") {
      val events =
        List(
          created("issue-2"),
          IssueEvent.LaneSet(IssueId("issue-2"), TicketLane.Frontend, setBy = "pat", occurredAt = now.plusSeconds(60)),
        )
      val issue = AgentIssue.fromEvents(events).toOption.get
      assertTrue(issue.lane == TicketLane.Frontend)
    },
    test("a later LaneSet overrides an earlier LaneSet") {
      val events =
        List(
          created("issue-3"),
          IssueEvent.LaneSet(IssueId("issue-3"), TicketLane.Frontend, "pat", now.plusSeconds(60)),
          IssueEvent.LaneSet(IssueId("issue-3"), TicketLane.Backend, "pat", now.plusSeconds(120)),
        )
      val issue = AgentIssue.fromEvents(events).toOption.get
      assertTrue(issue.lane == TicketLane.Backend)
    },
  )
