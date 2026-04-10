package activity.boundary

import java.time.Instant

import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import shared.ids.Ids.{ ConversationId, EventId, TaskRunId }

object ActivityViewSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] = suite("ActivityViewSpec")(
    test("singleEventFragment handles legacy null Option fields") {
      val legacyRunId: Option[TaskRunId]               = Option.empty[Option[TaskRunId]].orNull
      val legacyConversationId: Option[ConversationId] = Option.empty[Option[ConversationId]].orNull
      val legacyAgentName: Option[String]              = Option.empty[Option[String]].orNull
      val event                                        = ActivityEvent(
        id = EventId("evt-1"),
        eventType = ActivityEventType.ConfigChanged,
        source = "settings",
        runId = legacyRunId,
        conversationId = legacyConversationId,
        agentName = legacyAgentName,
        summary = "Settings updated",
        payload = None,
        createdAt = Instant.parse("2026-02-19T17:42:43Z"),
      )

      val html = ActivityView.singleEventFragment(event)
      assertTrue(
        html.contains("Settings updated"),
        html.contains("Config Changed"),
      )
    },
    test("singleEventFragment renders AgentAssigned without MatchError") {
      val event = ActivityEvent(
        id = EventId("evt-assigned"),
        eventType = ActivityEventType.AgentAssigned,
        source = "orchestrator",
        runId = Some(TaskRunId("run-1")),
        conversationId = None,
        agentName = Some("planner"),
        summary = "Agent planner assigned",
        payload = None,
        createdAt = Instant.parse("2026-02-23T12:00:00Z"),
      )

      val html = ActivityView.singleEventFragment(event)
      assertTrue(
        html.contains("Agent planner assigned"),
        html.contains("Agent Assigned"),
        html.contains("data-event-type=\"agent_assigned\""),
      )
    },
    test("singleEventFragment renders all event types") {
      val html = ActivityEventType.values.zipWithIndex.map {
        case (eventType, idx) =>
          ActivityView.singleEventFragment(
            ActivityEvent(
              id = EventId(s"evt-$idx"),
              eventType = eventType,
              source = "test",
              runId = None,
              conversationId = None,
              agentName = None,
              summary = s"summary-$idx",
              payload = None,
              createdAt = Instant.parse("2026-02-23T12:00:00Z"),
            )
          )
      }

      assertTrue(html.forall(_.contains("activity-event")))
    },
  )
