package llm4zio.core

import zio.Scope
import zio.test.*

object AgentSessionSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AgentSession protocol")(
    test("SessionEvent cases carry their fields") {
      assertTrue(
        SessionEvent.TextDelta("hi").text == "hi",
        SessionEvent.ToolUse("Edit", """{"file_path":"a.rs"}""", "t1").tool == "Edit",
        SessionEvent.ToolResult("t1", "success", "ok").status == "success",
        SessionEvent.AskUser("which?").question == "which?",
        SessionEvent.ApprovalRequest("Bash", "{}", "a1").tool == "Bash",
        SessionEvent.Usage(TokenUsage(1, 2, 3), Some("m")).model.contains("m"),
        SessionEvent.Done("final").result == "final",
      )
    },
    test("SessionResult carries text, usage, model") {
      val r = SessionResult("done", Some(TokenUsage(1, 1, 2)), Some("claude"))
      assertTrue(r.text == "done", r.usage.exists(_.total == 2), r.model.contains("claude"))
    },
  )
