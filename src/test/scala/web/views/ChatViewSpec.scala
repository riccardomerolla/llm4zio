package shared.web

import java.time.Instant

import zio.test.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import workspace.entity.{ RunSessionMode, RunStatus }

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

      val html = ChatView.detail(conversation, None, None)
      assertTrue(
        html.contains("Legacy Conversation"),
        html.contains("messages-unknown"),
      )
    },
    test("detail renders run session controls when run metadata is present") {
      val conversation = ChatConversation(
        id = Some("42"),
        runId = Some("run-42"),
        title = "Run Conversation",
        createdAt = Instant.parse("2026-03-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-03-01T10:05:00Z"),
      )
      val runMeta      = RunSessionUiMeta(
        runId = "run-42",
        workspaceId = "ws-1",
        status = RunStatus.Running(RunSessionMode.Autonomous),
        attachedUsersCount = 0,
        parent = Some(RunChainItem("run-41", "41")),
        next = Some(RunChainItem("run-43", "43")),
        breadcrumb = List(RunChainItem("run-41", "41"), RunChainItem("run-42", "42")),
      )

      val html = ChatView.detail(conversation, None, Some(runMeta))
      assertTrue(
        html.contains("Attach"),
        html.contains("Detach"),
        html.contains("Interrupt"),
        html.contains("Continue"),
        html.contains("Cancel"),
        html.contains("Previous run"),
        html.contains("Next run"),
        html.contains("Attach to interact"),
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
