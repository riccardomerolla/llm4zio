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
  )
