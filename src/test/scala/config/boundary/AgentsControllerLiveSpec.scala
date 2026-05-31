package config.boundary

import java.time.Instant

import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import shared.ids.Ids.EventId
import workspace.entity.RunStatus

object AgentsControllerLiveSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-26T13:00:00Z")

  private def triageEvent(
    agent: String,
    eventType: ActivityEventType,
    summary: String,
  ): ActivityEvent =
    ActivityEvent(
      id = EventId.generate,
      eventType = eventType,
      source = "triage",
      agentName = Some(agent),
      summary = summary,
      createdAt = now,
    )

  def spec: Spec[TestEnvironment, Any] = suite("AgentsControllerLive helpers")(
    test("isDaemonRunEvent accepts triage RunCompleted with matching agent") {
      val event = triageEvent("pat", ActivityEventType.RunCompleted, "Triaged issue abc-123")
      assertTrue(AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("isDaemonRunEvent accepts triage RunFailed with matching agent") {
      val event = triageEvent("pat", ActivityEventType.RunFailed, "Triage failed for abc-123: bad json")
      assertTrue(AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("isDaemonRunEvent ignores RunStarted (only terminal events count as runs)") {
      val event = triageEvent("pat", ActivityEventType.RunStarted, "Triaging issue abc-123")
      assertTrue(!AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("isDaemonRunEvent ignores events with a different source") {
      val event = ActivityEvent(
        id = EventId.generate,
        eventType = ActivityEventType.RunCompleted,
        source = "workspace-run",
        agentName = Some("pat"),
        summary = "Run completed",
        createdAt = now,
      )
      assertTrue(!AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("isDaemonRunEvent ignores events tagged for a different agent") {
      val event = triageEvent("alex", ActivityEventType.RunCompleted, "Triaged issue abc-123")
      assertTrue(!AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("isDaemonRunEvent matches agentName case-insensitively") {
      val event = triageEvent("Pat", ActivityEventType.RunCompleted, "Triaged issue abc-123")
      assertTrue(AgentsControllerLive.isDaemonRunEvent(event, "pat"))
    },
    test("synthesizeRunFromActivity maps RunCompleted to a Completed WorkspaceRun with extracted issue ref") {
      val event = triageEvent("pat", ActivityEventType.RunCompleted, "Triaged issue 6781e286")
      val run   = AgentsControllerLive.synthesizeRunFromActivity(event, "pat")
      assertTrue(
        run.status == RunStatus.Completed,
        run.agentName == "pat",
        run.issueRef == "#6781e286",
        run.createdAt == now,
        run.updatedAt == now,
        run.id == s"triage:${event.id.value}",
      )
    },
    test("synthesizeRunFromActivity maps RunFailed to a Failed WorkspaceRun with extracted issue ref") {
      val event = triageEvent("pat", ActivityEventType.RunFailed, "Triage failed for 6781e286: parse error")
      val run   = AgentsControllerLive.synthesizeRunFromActivity(event, "pat")
      assertTrue(run.status == RunStatus.Failed, run.issueRef == "#6781e286")
    },
    test("extractIssueRef handles both `Triaged` and `Triage failed` summary formats") {
      assertTrue(
        AgentsControllerLive.extractIssueRef("Triaged issue foo-1") == "#foo-1",
        AgentsControllerLive.extractIssueRef("Triage failed for foo-2: detail") == "#foo-2",
        AgentsControllerLive.extractIssueRef("unrelated summary") == "",
      )
    },
  )
