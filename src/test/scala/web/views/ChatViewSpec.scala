package shared.web

import java.time.Instant

import zio.test.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }

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
    },
    test("messageCard renders ToolCall entry with tool-call-block class") {
      val entry = ConversationEntry(
        conversationId = "1",
        sender = "assistant",
        senderType = SenderType.Assistant,
        content = """{"tool":"read_file","args":{"path":"/tmp/x.txt"}}""",
        messageType = MessageType.ToolCall,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
      )
      val html  = ChatView.messagesFragment(List(entry))
      assertTrue(html.contains("tool-call-block"))
    },
    test("messageCard renders ToolResult entry with tool-result-block class") {
      val entry = ConversationEntry(
        conversationId = "1",
        sender = "assistant",
        senderType = SenderType.Assistant,
        content = """{"result":"file content here"}""",
        messageType = MessageType.ToolResult,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
      )
      val html  = ChatView.messagesFragment(List(entry))
      assertTrue(html.contains("tool-result-block"))
    },
  )
