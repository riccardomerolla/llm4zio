package llm4zio.flow

import zio.Scope
import zio.test.*

import llm4zio.core.TokenUsage

object FlowEventsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowEvent new cases")(
    test("ToolUse carries tool name and summarized args") {
      val e: FlowEvent.ToolUse = FlowEvent.ToolUse("Edit", "(src/lib.rs)")
      assertTrue(e.tool == "Edit", e.args == "(src/lib.rs)")
    },
    test("AssistantMessage carries text") {
      assertTrue(FlowEvent.AssistantMessage("done").text == "done")
    },
    test("TokensUsed carries agent, model and usage") {
      val e: FlowEvent.TokensUsed = FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(10, 5, 15))
      assertTrue(e.agent == "coder", e.model.contains("gemini-2.5-flash"), e.usage.total == 15)
    },
  )
