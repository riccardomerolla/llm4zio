package gateway.boundary.telegram

import zio.test.*

object InlineKeyboardsSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("InlineKeyboardsSpec")(
    test("workflowControls builds required action buttons") {
      val markup  = InlineKeyboards.workflowControls(runId = 55L, paused = false)
      val buttons = markup.inline_keyboard.flatten
      assertTrue(
        buttons.exists(_.text == "View Details"),
        buttons.exists(_.text == "Pause"),
        buttons.exists(_.text == "Cancel"),
        buttons.exists(_.text == "Retry"),
      )
    },
    test("parseCallbackData decodes action, runId, and state") {
      val parsed = InlineKeyboards.parseCallbackData("wf:toggle:99:paused")
      assertTrue(parsed == Right(InlineKeyboardAction("toggle", 99L, paused = true)))
    },
    test("parseCallbackData rejects malformed payloads") {
      val bad1 = InlineKeyboards.parseCallbackData("wf:details:not-a-number:running")
      val bad2 = InlineKeyboards.parseCallbackData("something-else")
      assertTrue(
        bad1.isLeft,
        bad2.isLeft,
      )
    },
    test("parseDecisionCallbackData handles legacy 3-part `escalate` payload") {
      val parsed = InlineKeyboards.parseDecisionCallbackData("decision:escalate:decision-99")
      assertTrue(
        parsed == Right(DecisionKeyboardAction("escalate", "decision-99", None))
      )
    },
    test("parseDecisionCallbackData handles new 4-part `resolve` payload (Phase 3 R8)") {
      val parsed = InlineKeyboards.parseDecisionCallbackData("decision:resolve:decision-42:approve")
      assertTrue(
        parsed == Right(DecisionKeyboardAction("resolve", "decision-42", Some("approve")))
      )
    },
    test("parseDecisionCallbackData rejects empty option keys in resolve payload") {
      val parsed = InlineKeyboards.parseDecisionCallbackData("decision:resolve:decision-1:")
      assertTrue(parsed.isLeft)
    },
  )
