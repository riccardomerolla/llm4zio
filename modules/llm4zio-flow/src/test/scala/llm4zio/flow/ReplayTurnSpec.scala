package llm4zio.flow

import zio.Scope
import zio.test.*

import llm4zio.core.TokenUsage

object ReplayTurnSpec extends ZIOSpecDefault:
  private def tl(kind: String, fields: (String, String)*): TraceLine =
    TraceLine(0L, "t", "rid", kind, fields.toMap)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ReplayTurn.segment")(
    test("a TokensUsed then AssistantMessage becomes a Success carrying that usage; StreamError becomes a Failure") {
      val lines = List(
        tl("StageStarted", "stage"    -> "build"),
        tl(
          "TokensUsed",
          "agent"                     -> "coder",
          "model"                     -> "gemini-2.5-pro",
          "prompt"                    -> "10",
          "completion"                -> "2",
          "total"                     -> "12",
        ),
        tl("AssistantMessage", "text" -> "done"),
        tl("Info", "message"          -> "noise"),
        tl("StreamError", "provider"  -> "gemini-cli", "message" -> "Invalid stream: empty response"),
      )
      val turns = ReplayTurn.segment(lines)
      assertTrue(
        turns == List(
          ReplayTurn.Success("done", Some(TokenUsage(10, 2, 12)), Some("gemini-2.5-pro")),
          ReplayTurn.Failure("Invalid stream: empty response", None),
        )
      )
    },
    test("an AssistantMessage with no preceding TokensUsed has no usage") {
      assertTrue(
        ReplayTurn.segment(List(tl("AssistantMessage", "text" -> "hi"))) ==
          List(ReplayTurn.Success("hi", None, None))
      )
    },
    test("pending usage does not leak across turns") {
      val lines = List(
        tl("TokensUsed", "prompt"     -> "1", "completion" -> "1", "total" -> "2"),
        tl("AssistantMessage", "text" -> "first"),
        tl("AssistantMessage", "text" -> "second"),
      )
      assertTrue(
        ReplayTurn.segment(lines) ==
          List(ReplayTurn.Success("first", Some(TokenUsage(1, 1, 2)), None), ReplayTurn.Success("second", None, None))
      )
    },
  )
