package llm4zio.providers

import scala.io.Source

import zio.Scope
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.SessionEvent

object ClaudeAgentSessionSpec extends ZIOSpecDefault:
  private def fixtureLines: List[String] =
    val src = Source.fromResource("claude-stream.jsonl")
    try src.getLines().toList
    finally src.close()

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ClaudeAgentSession")(
    test("userMessageJson frames a user turn as stream-json") {
      val js  = ClaudeAgentSession.userMessageJson("do the thing")
      val obj = Json.decoder.decodeJson(js).toOption.flatMap(_.asObject)
      assertTrue(
        obj.flatMap(_.get("type")).contains(Json.Str("user")),
        obj.flatMap(_.get("message")).flatMap(_.asObject).flatMap(_.get("content")).contains(Json.Str("do the thing")),
      )
    },
    test("sessionArgv builds the bidirectional stream-json invocation") {
      val argv = ClaudeAgentSession.sessionArgv(Some("sonnet"), List("--mcp-config", "x.json"))
      assertTrue(
        argv.containsSlice(List("--input-format", "stream-json")),
        argv.containsSlice(List("--output-format", "stream-json")),
        argv.containsSlice(List("--model", "sonnet")),
        argv.containsSlice(List("--mcp-config", "x.json")),
      )
    },
    test("parseSessionLine maps assistant text, tool_use, and result events") {
      val events = fixtureLines.flatMap(ClaudeAgentSession.parseSessionLine)
      assertTrue(
        events.contains(SessionEvent.TextDelta("Editing the file.")),
        events.exists {
          case SessionEvent.ToolUse("Edit", raw, _) => raw.contains("src/lib.rs")
          case _                                    => false
        },
        events.exists {
          case SessionEvent.Usage(u, _) => u.prompt == 1200 && u.completion == 40
          case _                        => false
        },
        events.exists {
          case SessionEvent.Done(r) => r == "Editing the file."
          case _                    => false
        },
      )
    },
    test("initModel reads the model from the system init line") {
      assertTrue(fixtureLines.flatMap(ClaudeAgentSession.initModel).headOption.contains("claude-sonnet-4-6"))
    },
  )
