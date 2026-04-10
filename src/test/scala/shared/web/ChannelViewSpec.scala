package shared.web

import zio.test.*

import gateway.boundary.ChannelView

object ChannelViewSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("ChannelViewSpec")(
    test("discord config form renders enabled checkbox and setup guidance") {
      val html = ChannelView
        .channelConfigForm("discord", Map("channel.discord.enabled" -> "true"))
        .render

      assertTrue(
        html.contains("name=\"enabled\""),
        html.contains("checked=\"checked\""),
        html.contains("Discord Setup"),
        html.contains("MESSAGE CONTENT"),
      )
    },
    test("telegram config form points users to gateway settings") {
      val html = ChannelView.channelConfigForm("telegram", Map.empty).render

      assertTrue(
        html.contains("managed from Gateway settings"),
        html.contains("/settings/gateway"),
      )
    },
  )
