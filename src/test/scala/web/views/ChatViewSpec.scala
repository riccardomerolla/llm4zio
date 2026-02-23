package web.views

import java.time.Instant

import zio.test.*

import models.ChatConversation

object ChatViewSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("ChatViewSpec")(
    test("detail tolerates missing id and legacy-null optional description") {
      val legacyNullDescription: Option[String] = Option.empty[Option[String]].orNull
      val conversation                          = ChatConversation(
        id = None,
        runId = None,
        title = "Legacy Conversation",
        channel = Some("web"),
        description = legacyNullDescription,
        status = "active",
        messages = Nil,
        createdAt = Instant.parse("2026-02-19T21:00:00Z"),
        updatedAt = Instant.parse("2026-02-19T21:00:00Z"),
        createdBy = None,
      )

      val html = ChatView.detail(conversation, None)
      assertTrue(
        html.contains("Legacy Conversation"),
        html.contains("messages-unknown"),
      )
    }
  )
