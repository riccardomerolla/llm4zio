package web.views

import java.time.Instant

import zio.test.*

import models.{ ActivityEvent, ActivityEventType }

object ActivityViewSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] = suite("ActivityViewSpec")(
    test("singleEventFragment handles legacy null Option fields") {
      val legacyNull: Option[String] = Option.empty[Option[String]].orNull
      val event                      = ActivityEvent(
        id = Some("evt-1"),
        eventType = ActivityEventType.ConfigChanged,
        source = "settings",
        runId = legacyNull,
        conversationId = legacyNull,
        agentName = legacyNull,
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
