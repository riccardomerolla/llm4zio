package shared.web

import java.time.{ Duration, Instant }

import zio.json.*

import gateway.control.ChannelStatus
import scalatags.Text.all.*

final case class ChannelCardData(
  name: String,
  status: ChannelStatus,
  mode: Option[String] = None,
  botUsername: Option[String] = None,
  activeConnections: Int = 0,
  messagesReceived: Long = 0L,
  messagesSent: Long = 0L,
  errors: Long = 0L,
  lastActivityTs: Option[Long] = None,
  configureUrl: String = "/settings",
) derives JsonCodec

object ChannelView:

  def page(cards: List[ChannelCardData], nowMs: Long): String =
    Layout.page("Channels", "/channels")(
      div(cls := "flex items-center justify-between mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Channels"),
        div(cls := "flex items-center gap-2")(
          a(
            href := "/settings",
            cls  := "inline-flex items-center rounded-md bg-indigo-500/20 px-3 py-2 text-xs font-semibold text-indigo-100 ring-1 ring-indigo-300/30 hover:bg-indigo-500/30",
          )("Global Settings"),
          a(
            href := "/api/channels/status",
            cls  := "inline-flex items-center rounded-md bg-emerald-500/20 px-3 py-2 text-xs font-semibold text-emerald-100 ring-1 ring-emerald-300/30 hover:bg-emerald-500/30",
          )("Status API"),
        ),
      ),
      p(cls := "text-sm text-gray-400 mb-4")(
        "Live channel status and message telemetry. Auto-refresh every 10 seconds."
      ),
      channelActions(),
      div(
        id                   := "channels-cards",
        attr("hx-get")       := "/channels/cards",
        attr("hx-trigger")   := "every 10s",
        attr("hx-swap")      := "innerHTML",
        attr("hx-indicator") := "#channels-refresh-indicator",
      )(cardsFragment(cards, nowMs)),
      div(id := "channels-refresh-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-3")("Refreshing..."),
    )

  private def channelActions(): Frag =
    div(cls := "mb-5 grid grid-cols-1 gap-4 rounded-lg bg-white/5 p-4 ring-1 ring-white/10 lg:grid-cols-2")(
      tag("form")(
        cls                 := "space-y-2",
        attr("hx-post")     := "/api/channels/add",
        attr("hx-target")   := "#channels-cards",
        attr("hx-swap")     := "innerHTML",
        attr("hx-encoding") := "application/x-www-form-urlencoded",
      )(
        p(cls := "text-xs font-semibold uppercase tracking-wide text-gray-400")("Add Channel"),
        div(cls := "flex items-center gap-2")(
          select(name := "name", cls := "rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10")(
            option(value := "discord")("Discord"),
            option(value := "slack")("Slack"),
            option(value := "websocket")("WebSocket"),
          ),
          input(
            `type`      := "password",
            name        := "botToken",
            placeholder := "Token (Discord bot or Slack app token)",
            cls         := "w-full rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10",
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-500",
          )("Add"),
        ),
      ),
      tag("form")(
        cls                 := "space-y-2",
        attr("hx-post")     := "/api/channels/remove",
        attr("hx-target")   := "#channels-cards",
        attr("hx-swap")     := "innerHTML",
        attr("hx-encoding") := "application/x-www-form-urlencoded",
      )(
        p(cls := "text-xs font-semibold uppercase tracking-wide text-gray-400")("Remove Channel"),
        div(cls := "flex items-center gap-2")(
          input(
            `type`      := "text",
            name        := "name",
            placeholder := "discord or slack",
            cls         := "w-full rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10",
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-rose-600 px-3 py-2 text-xs font-semibold text-white hover:bg-rose-500",
          )("Remove"),
        ),
      ),
    )

  def cardsFragment(cards: List[ChannelCardData], nowMs: Long): Frag =
    if cards.isEmpty then Components.emptyState("No channels registered.")
    else
      div(cls := "grid grid-cols-1 gap-4 lg:grid-cols-2")(
        cards.sortBy(_.name).map(card => channelCard(card, nowMs))
      )

  def summaryWidgetFragment(cards: List[ChannelCardData]): Frag =
    val connected     = cards.count(_.status == ChannelStatus.Connected)
    val disconnected  = cards.count(_.status == ChannelStatus.Disconnected)
    val notConfigured = cards.count(_.status == ChannelStatus.NotConfigured)
    val errors        = cards.count {
      case ChannelCardData(_, ChannelStatus.Error(_), _, _, _, _, _, _, _, _) => true
      case _                                                                  => false
    }
    div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
      div(cls := "flex items-center justify-between mb-3")(
        h3(cls := "text-sm font-semibold text-white")("Channels"),
        a(href := "/settings/channels", cls := "text-xs text-indigo-400 hover:text-indigo-300")("Open"),
      ),
      div(cls := "grid grid-cols-2 gap-2 text-xs")(
        summaryChip("Connected", connected.toString, "bg-emerald-500/10 text-emerald-300 ring-emerald-300/30"),
        summaryChip("Disconnected", disconnected.toString, "bg-red-500/10 text-red-300 ring-red-300/30"),
        summaryChip("Not Configured", notConfigured.toString, "bg-gray-500/10 text-gray-300 ring-gray-300/30"),
        summaryChip("Errors", errors.toString, "bg-amber-500/10 text-amber-300 ring-amber-300/30"),
      ),
    )

  private def summaryChip(label: String, value: String, classes: String): Frag =
    div(cls := s"rounded-md px-3 py-2 ring-1 $classes")(
      div(cls := "font-medium")(label),
      div(cls := "text-lg font-semibold leading-none mt-1")(value),
    )

  private def channelCard(card: ChannelCardData, nowMs: Long): Frag =
    val (pillLabel, pillClasses) = statusPill(card.status)
    val configId                 = s"config-panel-${card.name}"
    div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-5")(
      div(cls := "flex items-center justify-between mb-3")(
        h2(cls := "text-lg font-semibold text-white capitalize")(card.name),
        span(
          cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset $pillClasses"
        )(pillLabel),
      ),
      div(cls := "text-sm text-gray-300 space-y-1")(
        card.mode.map(mode => p(span(cls := "text-gray-400")("Mode: "), mode)),
        p(span(cls := "text-gray-400")("Active connections: "), card.activeConnections.toString),
        p(
          span(cls := "text-gray-400")("Messages received: "),
          card.messagesReceived.toString,
          "  ",
          span(cls := "text-gray-400")("Sent: "),
          card.messagesSent.toString,
        ),
        p(
          span(cls := "text-gray-400")("Errors: "),
          card.errors.toString,
          "  ",
          span(cls := "text-gray-400")("Last activity: "),
          relativeTime(card.lastActivityTs, nowMs),
        ),
      ),
      div(cls := "mt-4 flex justify-end")(
        button(
          cls                 := "text-xs font-medium text-indigo-400 hover:text-indigo-300",
          attr("hx-get")      := s"/settings/channels/${card.name}/config-form",
          attr("hx-target")   := s"#$configId",
          attr("hx-swap")     := "innerHTML",
          attr("hx-push-url") := "false",
        )("Configure \u25be")
      ),
      div(id := configId)(),
    )

  private def statusPill(status: ChannelStatus): (String, String) =
    status match
      case ChannelStatus.Connected     => ("Connected", "bg-emerald-500/10 text-emerald-300 ring-emerald-300/30")
      case ChannelStatus.Disconnected  => ("Disconnected", "bg-red-500/10 text-red-300 ring-red-300/30")
      case ChannelStatus.NotConfigured =>
        ("Not Configured", "bg-gray-500/10 text-gray-300 ring-gray-300/30")
      case ChannelStatus.Error(_)      => ("Error", "bg-amber-500/10 text-amber-300 ring-amber-300/30")

  private def relativeTime(lastActivityTs: Option[Long], nowMs: Long): String =
    lastActivityTs match
      case None          => "never"
      case Some(epochMs) =>
        val diff = Duration.between(Instant.ofEpochMilli(epochMs), Instant.ofEpochMilli(nowMs))
        if diff.isNegative || diff.getSeconds < 5 then "just now"
        else if diff.toMinutes < 1 then s"${diff.getSeconds}s ago"
        else if diff.toHours < 1 then s"${diff.toMinutes} min ago"
        else s"${diff.toHours}h ago"

  // Config form for a specific channel, returned as HTML fragment
  def channelConfigForm(name: String, settings: Map[String, String]): Frag =
    val formId = s"config-form-$name"
    div(id := formId, cls := "mt-4 border-t border-white/10 pt-4")(
      name match
        case "telegram"  => telegramConfigForm(settings)
        case "discord"   => discordConfigForm(settings)
        case "slack"     => slackConfigForm(settings)
        case "websocket" => p(cls := "text-xs text-gray-400")("WebSocket is built-in and requires no configuration.")
        case _           => p(cls := "text-xs text-gray-400")(s"No configuration available for $name.")
    )

  private def telegramConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/telegram",
      attr("hx-target")   := "#config-form-telegram",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(
            `type`              := "password",
            name                := "telegram.botToken",
            value               := settings.getOrElse("telegram.botToken", ""),
            attr("placeholder") := "123456:ABC-token",
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 focus:ring-2 focus:ring-indigo-500 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Mode"),
          select(
            name := "telegram.mode",
            id   := "telegram-mode-inline",
            cls  := "block w-full rounded-md bg-gray-900 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          )(
            option(
              value := "Polling",
              if settings.getOrElse("telegram.mode", "Polling") == "Polling" then attr("selected") := "selected" else (),
            )("Polling"),
            option(
              value := "Webhook",
              if settings.getOrElse("telegram.mode", "Polling") == "Webhook" then attr("selected") := "selected" else (),
            )("Webhook"),
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Poll Interval (s)"),
          input(
            `type`      := "number",
            name        := "telegram.polling.interval",
            value       := settings.getOrElse("telegram.polling.interval", "1"),
            attr("min") := "1",
            attr("max") := "60",
            cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Batch Size"),
          input(
            `type`      := "number",
            name        := "telegram.polling.batchSize",
            value       := settings.getOrElse("telegram.polling.batchSize", "100"),
            attr("min") := "1",
            attr("max") := "1000",
            cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Poll Timeout (s)"),
          input(
            `type`      := "number",
            name        := "telegram.polling.timeout",
            value       := settings.getOrElse("telegram.polling.timeout", "30"),
            attr("min") := "1",
            attr("max") := "120",
            cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
      ),
      div(cls := "flex items-center gap-3")(
        button(
          `type` := "submit",
          cls    := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500",
        )("Save"),
        span(id := "save-status-telegram", cls := "text-xs text-gray-400")(""),
      ),
    )

  private def discordConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/discord",
      attr("hx-target")   := "#config-form-discord",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(
            `type`              := "password",
            name                := "botToken",
            value               := settings.getOrElse("channel.discord.botToken", ""),
            attr("placeholder") := "Bot token",
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Guild ID (optional)"),
          input(
            `type`              := "text",
            name                := "guildId",
            value               := settings.getOrElse("channel.discord.guildId", ""),
            attr("placeholder") := "Server ID",
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
      ),
      button(
        `type` := "submit",
        cls    := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500",
      )("Save"),
    )

  private def slackConfigForm(settings: Map[String, String]): Frag =
    tag("form")(
      cls                 := "space-y-4",
      attr("hx-post")     := "/settings/channels/slack",
      attr("hx-target")   := "#config-form-slack",
      attr("hx-swap")     := "outerHTML",
      attr("hx-encoding") := "application/x-www-form-urlencoded",
    )(
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("App Token"),
          input(
            `type`              := "password",
            name                := "appToken",
            value               := settings.getOrElse("channel.slack.appToken", ""),
            attr("placeholder") := "xapp-...",
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
        div(
          label(cls := "block text-xs font-medium text-gray-400 mb-1")("Bot Token"),
          input(
            `type`              := "password",
            name                := "botToken",
            value               := settings.getOrElse("channel.slack.botToken", ""),
            attr("placeholder") := "xoxb-...",
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white text-sm ring-1 ring-white/10 px-3",
          ),
        ),
      ),
      button(
        `type` := "submit",
        cls    := "rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500",
      )("Save"),
    )
