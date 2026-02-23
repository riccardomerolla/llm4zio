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
        a(
          href := "/settings",
          cls  := "inline-flex items-center rounded-md bg-indigo-500/20 px-3 py-2 text-xs font-semibold text-indigo-100 ring-1 ring-indigo-300/30 hover:bg-indigo-500/30",
        )("Configure"),
      ),
      p(cls := "text-sm text-gray-400 mb-4")(
        "Live channel status and message telemetry. Auto-refresh every 10 seconds."
      ),
      div(
        id                   := "channels-cards",
        attr("hx-get")       := "/channels/cards",
        attr("hx-trigger")   := "every 10s",
        attr("hx-swap")      := "innerHTML",
        attr("hx-indicator") := "#channels-refresh-indicator",
      )(cardsFragment(cards, nowMs)),
      div(id := "channels-refresh-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-3")("Refreshing..."),
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
        a(href := "/channels", cls := "text-xs text-indigo-400 hover:text-indigo-300")("Open"),
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
    div(cls := "rounded-lg bg-white/5 ring-1 ring-white/10 p-5")(
      div(cls := "flex items-center justify-between mb-3")(
        h2(cls := "text-lg font-semibold text-white capitalize")(card.name),
        span(
          cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset $pillClasses"
        )(
          pillLabel
        ),
      ),
      div(cls := "text-sm text-gray-300 space-y-1")(
        card.mode.map(mode => p(span(cls := "text-gray-400")("Mode: "), mode)),
        if card.name == "telegram" then p(span(cls := "text-gray-400")("Bot: "), card.botUsername.getOrElse("unknown"))
        else frag(),
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
      div(cls := "mt-4")(
        a(
          href := card.configureUrl,
          cls  := "text-xs font-medium text-indigo-400 hover:text-indigo-300",
        )("Configure ->")
      ),
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
