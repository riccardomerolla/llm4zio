package llm4zio.flow

import zio.test.*

import llm4zio.core.TokenUsage
import zio.Scope

object FlowTraceSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowTrace")(
    test("maps FlowEvent cases to a kind + fields") {
      val started = TraceEvent.fromFlow(FlowEvent.StageStarted("branch"))
      val tokens  = TraceEvent.fromFlow(FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), TokenUsage(10, 2, 12)))
      assertTrue(
        started.kind == "StageStarted",
        started.fields("stage") == "branch",
        tokens.kind == "TokensUsed",
        tokens.fields("agent") == "coder",
        tokens.fields("model") == "gemini-2.5-pro",
        tokens.fields("total") == "12",
      )
    },
    test("low-level RawLine and StreamError carry their payload") {
      val raw = TraceEvent.RawLine("gemini-cli", Some("gemini-2.5-pro"), """{"type":"error"}""")
      val err = TraceEvent.StreamError("gemini-cli", None, "Invalid stream: empty response")
      assertTrue(
        raw.kind == "RawLine",
        raw.fields("provider") == "gemini-cli",
        raw.fields("line") == """{"type":"error"}""",
        err.kind == "StreamError",
        err.fields("message") == "Invalid stream: empty response",
        !err.fields.contains("model"),
      )
    },
    test("TraceLine.toJson is one valid object with the envelope fields") {
      val line = TraceLine(7L, "2026-06-19T10:00:00Z", "rid", "Info", Map("message" -> "hi")).toJson
      assertTrue(
        line.startsWith("{") && line.endsWith("}"),
        line.contains("\"seq\":7"),
        line.contains("\"runId\":\"rid\""),
        line.contains("\"kind\":\"Info\""),
        line.contains("\"message\":\"hi\""),
        !line.contains("\n"),
      )
    },
  )
