package gateway.control

import java.time.Instant

import zio.test.*

import gateway.entity.{ GatewayMessageRole, MessageDirection, NormalizedMessage, SessionKey }

/** Locks the branch predicate that decides whether `processInbound` routes
  * to `TelegramIntake` or to the legacy `handleIntentRouting`. The full
  * `processInbound` flow has eight dependencies; this spec covers the
  * decision point that matters — Safeguard #1 in the canvas
  * (`spdd/TG-INTAKE-202605161555-…`): web chat must NOT enter the intake.
  */
object GatewayServiceBranchSpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-05-16T10:00:00Z")

  private def msg(channelName: String, content: String): NormalizedMessage =
    NormalizedMessage(
      id = "m",
      channelName = channelName,
      sessionKey = SessionKey(channelName, "s"),
      direction = MessageDirection.Inbound,
      role = GatewayMessageRole.User,
      content = content,
      metadata = Map.empty,
      timestamp = now,
    )

  def spec: Spec[TestEnvironment, Any] = suite("GatewayService.isTelegramFreeText")(
    test("true for telegram free-text") {
      assertTrue(GatewayService.isTelegramFreeText(msg("telegram", "add README")))
    },
    test("false for telegram slash commands") {
      assertTrue(!GatewayService.isTelegramFreeText(msg("telegram", "/help"))) &&
      assertTrue(!GatewayService.isTelegramFreeText(msg("telegram", "  /tasks ")))
    },
    test("false for empty / whitespace-only telegram messages") {
      assertTrue(!GatewayService.isTelegramFreeText(msg("telegram", ""))) &&
      assertTrue(!GatewayService.isTelegramFreeText(msg("telegram", "   \n\t  ")))
    },
    test("false for non-telegram channels — web chat must keep the legacy path") {
      assertTrue(!GatewayService.isTelegramFreeText(msg("web", "hi"))) &&
      assertTrue(!GatewayService.isTelegramFreeText(msg("discord", "hi")))
    },
  )
