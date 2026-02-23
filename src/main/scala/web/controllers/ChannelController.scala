package web.controllers

import java.util.concurrent.TimeUnit

import zio.*
import zio.http.*
import zio.json.*

import _root_.models.GatewayConfig
import gateway.*
import web.views.{ ChannelCardData, ChannelView }

trait ChannelController:
  def routes: Routes[Any, Response]

object ChannelController:

  def routes: ZIO[ChannelController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ChannelController](_.routes)

  val live: ZLayer[ChannelRegistry & GatewayService & Ref[GatewayConfig], Nothing, ChannelController] =
    ZLayer.fromFunction(ChannelControllerLive.apply)

final case class ChannelControllerLive(
  channelRegistry: ChannelRegistry,
  gatewayService: GatewayService,
  configRef: Ref[GatewayConfig],
) extends ChannelController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "channels"             -> handler {
      buildCards.flatMap(cards =>
        Clock.currentTime(TimeUnit.MILLISECONDS).map(now => html(ChannelView.page(cards, now)))
      )
    },
    Method.GET / "channels" / "cards"   -> handler {
      buildCards.flatMap(cards =>
        Clock.currentTime(TimeUnit.MILLISECONDS).map(now => htmlFragment(ChannelView.cardsFragment(cards, now).render))
      )
    },
    Method.GET / "channels" / "summary" -> handler {
      buildCards.map(cards => htmlFragment(ChannelView.summaryWidgetFragment(cards).render))
    },
    Method.GET / "api" / "channels"     -> handler {
      buildCards.map(cards => Response.json(cards.toJson))
    },
  )

  private def buildCards: UIO[List[ChannelCardData]] =
    for
      channels <- channelRegistry.list
      runtime  <- channelRegistry.listRuntime
      metrics  <- gatewayService.metrics
      config   <- configRef.get
      cards    <- ZIO.foreach(channels) { channel =>
                    channel.activeSessions.map { sessions =>
                      val name       = channel.name
                      val rt         = runtime.getOrElse(name, ChannelRuntime())
                      val perChannel = metrics.perChannel.getOrElse(name, ChannelMetrics())
                      val status     = effectiveStatus(name, sessions.size, rt.status, config)
                      ChannelCardData(
                        name = name,
                        status = status,
                        mode = if name == "telegram" then Some(config.telegram.mode.toString) else None,
                        botUsername = None,
                        activeConnections = sessions.size,
                        messagesReceived = perChannel.inboundProcessed,
                        messagesSent = perChannel.outboundProcessed,
                        errors = rt.errorCount + perChannel.failed,
                        lastActivityTs = rt.lastActivity.map(_.toEpochMilli).orElse(perChannel.lastActivityTs),
                        configureUrl = if name == "telegram" then "/settings" else "/channels",
                      )
                    }
                  }
    yield cards.sortBy(_.name)

  private def effectiveStatus(
    channelName: String,
    activeConnections: Int,
    current: ChannelStatus,
    config: GatewayConfig,
  ): ChannelStatus =
    channelName.toLowerCase match
      case "telegram" if !config.telegram.enabled =>
        ChannelStatus.NotConfigured
      case "websocket" if activeConnections > 0   =>
        ChannelStatus.Connected
      case "websocket"                            =>
        current match
          case error: ChannelStatus.Error => error
          case _                          => ChannelStatus.Disconnected
      case _                                      =>
        current

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def htmlFragment(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
