package shared.web

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
    }
  )
