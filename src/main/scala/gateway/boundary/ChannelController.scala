package gateway.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.entity.GatewayConfig
import db.{ ConfigRepository, PersistenceError }
import gateway.control.*
import shared.web.{ ChannelCardData, ChannelView, HtmlViews }

trait ChannelController:
  def routes: Routes[Any, Response]

object ChannelController:

  def routes: ZIO[ChannelController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ChannelController](_.routes)

  val live
    : ZLayer[ChannelRegistry & GatewayService & Ref[GatewayConfig] & ConfigRepository, Nothing, ChannelController] =
    ZLayer.fromFunction(ChannelControllerLive.apply)

final case class ChannelControllerLive(
  channelRegistry: ChannelRegistry,
  gatewayService: GatewayService,
  configRef: Ref[GatewayConfig],
  configRepository: ConfigRepository,
) extends ChannelController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "channels"                                               -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/channels").getOrElse(URL.root))),
      ))
    },
    Method.GET / "channels" / "cards"                                     -> handler {
      buildCards.flatMap(cards =>
        Clock.currentTime(TimeUnit.MILLISECONDS).map(now => htmlFragment(ChannelView.cardsFragment(cards, now).render))
      )
    },
    Method.GET / "channels" / "summary"                                   -> handler {
      buildCards.map(cards => htmlFragment(ChannelView.summaryWidgetFragment(cards).render))
    },
    // Settings tab: channels view
    Method.GET / "settings" / "channels"                                  -> handler {
      buildChannelsTabPage(None)
    },
    Method.GET / "settings" / "channels" / "cards"                        -> handler {
      for
        cards <- buildCards
        now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      yield htmlFragment(ChannelView.cardsFragment(cards, now).render)
    },
    // Per-channel inline config form (HTMX fragment)
    Method.GET / "settings" / "channels" / string("name") / "config-form" -> handler { (name: String, _: Request) =>
      getSettingsMap.map(settings => htmlFragment(ChannelView.channelConfigForm(name, settings).render))
    },
    // Save per-channel config
    Method.POST / "settings" / "channels" / string("name")                -> handler { (name: String, req: Request) =>
      ErrorHandling.fromPersistence {
        for
          form     <- parseForm(req)
          prefixed  = name match
                        case "telegram" => form // telegram keys are already prefixed with "telegram."
                        case _          => form.map { case (k, v) => s"channel.$name.$k" -> v }
          _        <- configRepository.upsertSettings(prefixed)
          settings <- getSettingsMap
        yield htmlFragment(ChannelView.channelConfigForm(name, settings).render)
      }
    },
    Method.GET / "api" / "channels"                                       -> handler {
      buildCards.map(cards => Response.json(cards.toJson))
    },
    Method.GET / "api" / "channels" / "status"                            -> handler {
      probeStatuses.map(items => Response.json(items.toJson))
    },
    Method.GET / "api" / "channels" / "logs"                              -> handler { (req: Request) =>
      val limit = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50).max(1)
      channelLogs(limit).map(entries => Response.json(entries.toJson))
    },
    Method.POST / "api" / "channels" / "add"                              -> handler { (req: Request) =>
      ErrorHandling.fromPersistence {
        for
          form  <- parseForm(req)
          _     <- addChannel(form)
          cards <- buildCards
          now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        yield htmlFragment(ChannelView.cardsFragment(cards, now).render)
      }
    },
    Method.POST / "api" / "channels" / "remove"                           -> handler { (req: Request) =>
      ErrorHandling.fromPersistence {
        for
          form  <- parseForm(req)
          _     <- removeChannel(form.getOrElse("name", ""))
          cards <- buildCards
          now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        yield htmlFragment(ChannelView.cardsFragment(cards, now).render)
      }
    },
  )

  private object ErrorHandling:
    def fromPersistence(effect: IO[PersistenceError, Response]): UIO[Response] =
      effect.catchAll(err => ZIO.succeed(Response.text(err.toString).status(Status.BadRequest)))

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
                        configureUrl = "/settings/channels",
                      )
                    }
                  }
    yield cards.sortBy(_.name)

  private def getSettingsMap: UIO[Map[String, String]] =
    configRepository.getAllSettings
      .map(_.map(r => r.key -> r.value).toMap)
      .catchAll(_ => ZIO.succeed(Map.empty))

  private def buildChannelsTabPage(flash: Option[String]): UIO[Response] =
    for
      cards <- buildCards
      now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
    yield html(HtmlViews.settingsChannelsTab(cards, now, flash))

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

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseChannelForm", Option(err.getMessage).getOrElse(err.toString)))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def addChannel(form: Map[String, String]): IO[PersistenceError, Unit] =
    val rawName = form.getOrElse("name", "").trim.toLowerCase
    for
      name    <- ZIO
                   .fromOption(normalizeSupportedChannel(rawName))
                   .orElseFail(PersistenceError.QueryFailed("addChannel", s"Unsupported channel '$rawName'"))
      cfg      = channelConfig(name, form)
      _       <- configRepository.upsertSettings(cfg)
      channel <- instantiateChannel(name, cfg)
      _       <- channelRegistry.register(channel)
      _       <- channelRegistry.markNotConfigured(name).when(cfg.getOrElse(s"channel.$name.enabled", "false") != "true")
    yield ()

  private def removeChannel(nameRaw: String): IO[PersistenceError, Unit] =
    val nameOpt = normalizeSupportedChannel(nameRaw.trim.toLowerCase)
    for
      name <- ZIO
                .fromOption(nameOpt)
                .orElseFail(PersistenceError.QueryFailed("removeChannel", s"Unsupported channel '$nameRaw'"))
      _    <- channelRegistry.unregister(name)
      _    <- configRepository.deleteSettingsByPrefix(s"channel.$name.")
    yield ()

  private def normalizeSupportedChannel(value: String): Option[String] =
    value match
      case "discord"   => Some("discord")
      case "slack"     => Some("slack")
      case "telegram"  => Some("telegram")
      case "websocket" => Some("websocket")
      case _           => None

  private def channelConfig(name: String, form: Map[String, String]): Map[String, String] =
    val base = Map(
      s"channel.$name.enabled" -> form.get("enabled").exists(v => v == "on" || v.equalsIgnoreCase("true")).toString
    )
    name match
      case "discord" =>
        base ++ Map(
          s"channel.$name.botToken"  -> form.getOrElse("botToken", ""),
          s"channel.$name.guildId"   -> form.getOrElse("guildId", ""),
          s"channel.$name.channelId" -> form.getOrElse("channelId", ""),
        )
      case "slack"   =>
        val token = form.get("appToken").orElse(form.get("botToken")).getOrElse("")
        base ++ Map(
          s"channel.$name.appToken"   -> token,
          s"channel.$name.botToken"   -> form.getOrElse("botToken", ""),
          s"channel.$name.channelId"  -> form.getOrElse("channelId", ""),
          s"channel.$name.socketMode" -> form.getOrElse("socketMode", "true"),
        )
      case _         => base

  private def instantiateChannel(name: String, cfg: Map[String, String]): IO[PersistenceError, MessageChannel] =
    name match
      case "discord"   =>
        val token = cfg.getOrElse("channel.discord.botToken", "").trim
        if token.isEmpty then
          ZIO.fail(PersistenceError.QueryFailed("addChannel", "Discord bot token is required"))
        else
          DiscordChannel
            .make(
              config = DiscordConfig(
                botToken = token,
                guildId = cfg.get("channel.discord.guildId").map(_.trim).filter(_.nonEmpty),
                defaultChannelId = cfg.get("channel.discord.channelId").map(_.trim).filter(_.nonEmpty),
              )
            )
            .map(identity[MessageChannel])
      case "slack"     =>
        val appToken = cfg.getOrElse("channel.slack.appToken", "").trim
        if appToken.isEmpty then
          ZIO.fail(PersistenceError.QueryFailed("addChannel", "Slack app token is required"))
        else
          SlackChannel
            .make(
              config = SlackConfig(
                appToken = appToken,
                botToken = cfg.get("channel.slack.botToken").map(_.trim).filter(_.nonEmpty),
                defaultChannelId = cfg.get("channel.slack.channelId").map(_.trim).filter(_.nonEmpty),
                socketMode = cfg.get("channel.slack.socketMode").exists(_.equalsIgnoreCase("true")),
              )
            )
            .map(identity[MessageChannel])
      case "telegram"  =>
        ZIO.fail(PersistenceError.QueryFailed("addChannel", "Telegram channel is managed from Settings"))
      case "websocket" =>
        WebSocketChannel.make().map(identity[MessageChannel])
      case _           =>
        ZIO.fail(PersistenceError.QueryFailed("addChannel", s"Unsupported channel: $name"))

  private def probeStatuses: UIO[List[gateway.entity.ChannelStatus]] =
    for
      channels <- channelRegistry.list
      runtime  <- channelRegistry.listRuntime
      statuses <- ZIO.foreach(channels) { channel =>
                    channel.activeSessions.map { sessions =>
                      val rt            = runtime.get(channel.name)
                      val runtimeStatus = rt.map(_.status)
                      gateway.entity.ChannelStatus(
                        name = channel.name,
                        reachability = runtimeStatus match
                          case Some(gateway.control.ChannelStatus.Connected) =>
                            gateway.entity.ChannelReachability.Reachable
                          case Some(gateway.control.ChannelStatus.Error(_))  =>
                            gateway.entity.ChannelReachability.Unreachable
                          case Some(
                                 gateway.control.ChannelStatus.Disconnected | gateway.control.ChannelStatus.NotConfigured
                               ) =>
                            if sessions.nonEmpty then gateway.entity.ChannelReachability.Reachable
                            else gateway.entity.ChannelReachability.Unknown
                          case None                                          => gateway.entity.ChannelReachability.Unknown,
                        running = sessions.nonEmpty,
                        activeSessions = sessions.size,
                        lastMessageAt = rt.flatMap(_.lastActivity),
                        lastMessagePreview = None,
                        lastError = rt.flatMap(_.lastError),
                      )
                    }
                  }
    yield statuses.sortBy(_.name)

  private def channelLogs(limit: Int): UIO[List[Map[String, String]]] =
    channelRegistry.listRuntime.map(
      _.toList
        .sortBy(_._1)
        .take(limit)
        .map {
          case (name, rt) =>
            Map(
              "channel"      -> name,
              "status"       -> rt.status.toString,
              "errorCount"   -> rt.errorCount.toString,
              "lastError"    -> rt.lastError.getOrElse(""),
              "lastActivity" -> rt.lastActivity.map(_.toString).getOrElse(""),
            )
        }
    )
