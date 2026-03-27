package gateway.control

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse, WebSocket }
import java.util.concurrent.{ CompletableFuture, CompletionStage, LinkedBlockingQueue }

import zio.*
import zio.json.*
import zio.json.ast.Json

import db.ConfigRepository
import gateway.entity.{ NormalizedMessage, SessionKey, SessionScopeStrategy }

sealed trait DiscordRuntimeError extends Product with Serializable
object DiscordRuntimeError:
  final case class InvalidConfiguration(message: String)       extends DiscordRuntimeError
  final case class DecodeFailure(message: String)              extends DiscordRuntimeError
  final case class HttpFailure(statusCode: Int, body: String)  extends DiscordRuntimeError
  final case class TransportFailure(message: String)           extends DiscordRuntimeError
  final case class ConnectionClosed(code: Int, reason: String) extends DiscordRuntimeError

final case class DiscordRuntimeConfig(
  enabled: Boolean,
  token: Option[String],
  guildId: Option[String],
  defaultChannelId: Option[String],
  sessionScopeStrategy: SessionScopeStrategy,
  intents: Int = DiscordRuntimeConfig.DefaultIntents,
)

object DiscordRuntimeConfig:
  val DefaultIntents: Int = 512 + 4096 + 32768 // GUILD_MESSAGES + DIRECT_MESSAGES + MESSAGE_CONTENT

trait DiscordGatewayService:
  def config: UIO[DiscordRuntimeConfig]
  def runOnce: UIO[Int]
  def runLoop: UIO[Nothing]

object DiscordGatewayService:

  val live: ZLayer[ChannelRegistry & GatewayService & ConfigRepository, Nothing, DiscordGatewayService] =
    ZLayer.scoped {
      for
        channelRegistry <- ZIO.service[ChannelRegistry]
        gatewayService  <- ZIO.service[GatewayService]
        configRepo      <- ZIO.service[ConfigRepository]
        httpClient      <- ZIO.attempt(HttpClient.newHttpClient()).orDie
        service          = DiscordGatewayServiceLive(channelRegistry, gatewayService, configRepo, httpClient)
        _               <- service.runLoop.forkScoped
      yield service
    }

  final private[control] case class GatewayBotResponse(url: String) derives JsonDecoder
  final private[control] case class GatewayEnvelope(
    op: Int,
    d: Json = Json.Null,
    s: Option[Int] = None,
    t: Option[String] = None,
  ) derives JsonDecoder

  final private[control] case class HelloPayload(heartbeat_interval: Long) derives JsonDecoder

  final private[control] case class AuthorPayload(
    id: String,
    bot: Option[Boolean] = None,
    username: Option[String] = None,
  ) derives JsonDecoder

  final private[control] case class MessageCreatePayload(
    id: String,
    channel_id: String,
    guild_id: Option[String] = None,
    content: Option[String] = None,
    mentions: List[AuthorPayload] = Nil,
    author: AuthorPayload,
  ) derives JsonDecoder

  final private[control] case class OutboundMessage(content: String) derives JsonEncoder
  final private[control] case class ReadyPayload(user: AuthorPayload) derives JsonDecoder

  sealed private[control] trait WsEvent
  private[control] object WsEvent:
    final case class Text(payload: String)             extends WsEvent
    final case class Closed(code: Int, reason: String) extends WsEvent
    final case class Error(message: String)            extends WsEvent

  final private[control] class DiscordWebSocketListener(queue: LinkedBlockingQueue[WsEvent])
    extends WebSocket.Listener:
    override def onOpen(webSocket: WebSocket): Unit =
      webSocket.request(1)

    override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      val payload = data.toString
      val _       = queue.offer(WsEvent.Text(payload))
      webSocket.request(1)
      CompletableFuture.completedFuture(())

    override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
      val _ = queue.offer(WsEvent.Closed(statusCode, reason))
      CompletableFuture.completedFuture(())

    override def onError(webSocket: WebSocket, error: Throwable): Unit =
      val _ = queue.offer(WsEvent.Error(Option(error.getMessage).getOrElse(error.toString)))

final case class DiscordGatewayServiceLive(
  channelRegistry: ChannelRegistry,
  gatewayService: GatewayService,
  configRepository: ConfigRepository,
  httpClient: HttpClient,
) extends DiscordGatewayService:

  import DiscordGatewayService.*

  override def config: UIO[DiscordRuntimeConfig] =
    configRepository
      .getAllSettings
      .map(rows => rows.map(row => row.key -> row.value).toMap)
      .map(fromSettings)
      .catchAll(_ =>
        ZIO.succeed(DiscordRuntimeConfig(
          enabled = false,
          token = None,
          guildId = None,
          defaultChannelId = None,
          sessionScopeStrategy = SessionScopeStrategy.PerConversation,
        ))
      )

  override def runOnce: UIO[Int] =
    config.flatMap { cfg =>
      if !cfg.enabled then channelRegistry.markNotConfigured("discord").as(0)
      else
        runConnectedSession(cfg)
          .tapError(err => channelRegistry.markError("discord", err.toString))
          .tapError(err => ZIO.logWarning(s"discord runtime iteration failed: $err"))
          .as(1)
          .catchAll(_ => ZIO.succeed(0))
    }

  override def runLoop: UIO[Nothing] =
    runOnce *> ZIO.sleep(5.seconds) *> runLoop

  private def runConnectedSession(cfg: DiscordRuntimeConfig): IO[DiscordRuntimeError, Unit] =
    for
      token         <- ZIO
                         .fromOption(cfg.token.map(_.trim).filter(_.nonEmpty))
                         .orElseFail(DiscordRuntimeError.InvalidConfiguration("Discord bot token is missing"))
      channel       <- channelRegistry
                         .get("discord")
                         .mapError(err => DiscordRuntimeError.TransportFailure(err.toString))
      discord       <- channel match
                         case d: DiscordChannel => ZIO.succeed(d)
                         case other             =>
                           ZIO.fail(
                             DiscordRuntimeError.TransportFailure(
                               s"discord channel has unexpected type: ${other.getClass.getName}"
                             )
                           )
      gatewayUrl    <- fetchGatewayUrl(token)
      queue         <- ZIO.succeed(new LinkedBlockingQueue[WsEvent]())
      ws            <- connectGateway(gatewayUrl, queue)
      seqRef        <- Ref.make(Option.empty[Int])
      selfUserIdRef <- Ref.make(Option.empty[String])
      sessionToChan <- Ref.make(Map.empty[SessionKey, String])
      outboundPumps <- Ref.make(Map.empty[SessionKey, Fiber.Runtime[Nothing, Unit]])
      hello         <- awaitHello(queue)
      _             <- sendIdentify(ws, token, cfg.intents)
      heartbeat     <- heartbeatLoop(ws, hello.heartbeat_interval.millis, seqRef).forkDaemon
      _             <- processGatewayEvents(
                         queue = queue,
                         ws = ws,
                         discord = discord,
                         cfg = cfg,
                         token = token,
                         seqRef = seqRef,
                         selfUserIdRef = selfUserIdRef,
                         sessionToChan = sessionToChan,
                         outboundPumps = outboundPumps,
                       )
                         .ensuring(heartbeat.interrupt.ignore)
                         .ensuring(stopOutboundPumps(outboundPumps))
                         .ensuring(closeSocket(ws))
    yield ()

  private def fetchGatewayUrl(token: String): IO[DiscordRuntimeError, String] =
    ZIO
      .attemptBlocking {
        val request = HttpRequest
          .newBuilder(URI.create("https://discord.com/api/v10/gateway/bot"))
          .header("Authorization", s"Bot $token")
          .GET()
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }
      .mapError(err => DiscordRuntimeError.TransportFailure(Option(err.getMessage).getOrElse(err.toString)))
      .flatMap { response =>
        if response.statusCode() / 100 != 2 then
          ZIO.fail(DiscordRuntimeError.HttpFailure(response.statusCode(), response.body()))
        else
          ZIO
            .fromEither(response.body().fromJson[GatewayBotResponse].left.map(DiscordRuntimeError.DecodeFailure.apply))
            .map(decoded => s"${decoded.url}?v=10&encoding=json")
      }

  private def connectGateway(
    url: String,
    queue: LinkedBlockingQueue[WsEvent],
  ): IO[DiscordRuntimeError, WebSocket] =
    ZIO
      .attemptBlocking {
        val listener = new DiscordWebSocketListener(queue)
        httpClient.newWebSocketBuilder().buildAsync(URI.create(url), listener).join()
      }
      .mapError(err => DiscordRuntimeError.TransportFailure(Option(err.getMessage).getOrElse(err.toString)))

  private def awaitHello(queue: LinkedBlockingQueue[WsEvent]): IO[DiscordRuntimeError, HelloPayload] =
    takeWsEvent(queue).flatMap {
      case WsEvent.Text(payload)        =>
        decodeEnvelope(payload).flatMap { env =>
          if env.op == 10 then decodePayload[HelloPayload](env.d)
          else awaitHello(queue)
        }
      case WsEvent.Closed(code, reason) => ZIO.fail(DiscordRuntimeError.ConnectionClosed(code, reason))
      case WsEvent.Error(message)       => ZIO.fail(DiscordRuntimeError.TransportFailure(message))
    }

  private def sendIdentify(ws: WebSocket, token: String, intents: Int): IO[DiscordRuntimeError, Unit] =
    val payload =
      s"""{"op":2,"d":{"token":"${escapeJson(
          token
        )}","intents":$intents,"properties":{"os":"llm4zio","browser":"llm4zio","device":"llm4zio"}}}"""
    sendWsText(ws, payload)

  private def heartbeatLoop(
    ws: WebSocket,
    interval: Duration,
    seqRef: Ref[Option[Int]],
  ): UIO[Nothing] =
    val beat =
      seqRef.get.flatMap { seq =>
        val seqPayload = seq.map(_.toString).getOrElse("null")
        sendWsText(ws, s"""{"op":1,"d":$seqPayload}""")
          .ignore
      }
    beat *> ZIO.sleep(interval) *> heartbeatLoop(ws, interval, seqRef)

  private def processGatewayEvents(
    queue: LinkedBlockingQueue[WsEvent],
    ws: WebSocket,
    discord: DiscordChannel,
    cfg: DiscordRuntimeConfig,
    token: String,
    seqRef: Ref[Option[Int]],
    selfUserIdRef: Ref[Option[String]],
    sessionToChan: Ref[Map[SessionKey, String]],
    outboundPumps: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]],
  ): IO[DiscordRuntimeError, Unit] =
    takeWsEvent(queue).flatMap {
      case WsEvent.Text(payload)        =>
        handleGatewayPayload(
          payload = payload,
          discord = discord,
          cfg = cfg,
          token = token,
          seqRef = seqRef,
          selfUserIdRef = selfUserIdRef,
          sessionToChan = sessionToChan,
          outboundPumps = outboundPumps,
        ) *> processGatewayEvents(queue, ws, discord, cfg, token, seqRef, selfUserIdRef, sessionToChan, outboundPumps)
      case WsEvent.Closed(code, reason) => ZIO.fail(DiscordRuntimeError.ConnectionClosed(code, reason))
      case WsEvent.Error(message)       => ZIO.fail(DiscordRuntimeError.TransportFailure(message))
    }

  private def handleGatewayPayload(
    payload: String,
    discord: DiscordChannel,
    cfg: DiscordRuntimeConfig,
    token: String,
    seqRef: Ref[Option[Int]],
    selfUserIdRef: Ref[Option[String]],
    sessionToChan: Ref[Map[SessionKey, String]],
    outboundPumps: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]],
  ): IO[DiscordRuntimeError, Unit] =
    for
      env <- decodeEnvelope(payload)
      _   <- seqRef.update(seq => env.s.orElse(seq))
      _   <- env.op match
               case 0  => handleDispatch(env, discord, cfg, token, selfUserIdRef, sessionToChan, outboundPumps)
               case 7  => ZIO.fail(DiscordRuntimeError.TransportFailure("Discord gateway requested reconnect"))
               case 9  => ZIO.fail(DiscordRuntimeError.TransportFailure("Discord invalid session"))
               case 11 => channelRegistry.markConnected("discord")
               case _  => ZIO.unit
    yield ()

  private def handleDispatch(
    env: GatewayEnvelope,
    discord: DiscordChannel,
    cfg: DiscordRuntimeConfig,
    token: String,
    selfUserIdRef: Ref[Option[String]],
    sessionToChan: Ref[Map[SessionKey, String]],
    outboundPumps: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]],
  ): IO[DiscordRuntimeError, Unit] =
    env.t match
      case Some("READY")          =>
        for
          ready <- decodePayload[ReadyPayload](env.d)
          _     <- selfUserIdRef.set(Some(ready.user.id))
          _     <- channelRegistry.markConnected("discord")
        yield ()
      case Some("MESSAGE_CREATE") =>
        for
          msg <- decodePayload[MessageCreatePayload](env.d)
          _   <- if msg.author.bot.getOrElse(false) then ZIO.unit
                 else
                   handleInboundMessage(
                     discord = discord,
                     cfg = cfg,
                     token = token,
                     selfUserIdRef = selfUserIdRef,
                     message = msg,
                     sessionToChan = sessionToChan,
                     outboundPumps = outboundPumps,
                   )
        yield ()
      case _                      => ZIO.unit

  private def handleInboundMessage(
    discord: DiscordChannel,
    cfg: DiscordRuntimeConfig,
    token: String,
    selfUserIdRef: Ref[Option[String]],
    message: MessageCreatePayload,
    sessionToChan: Ref[Map[SessionKey, String]],
    outboundPumps: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]],
  ): IO[DiscordRuntimeError, Unit] =
    for
      selfUserId <- selfUserIdRef.get
      contentRaw  = message.content.getOrElse("").trim
      isDm        = message.guild_id.isEmpty
      mentionsBot = selfUserId.exists(id => message.mentions.exists(_.id == id))
      content     = stripBotMentionPrefix(contentRaw)
      _          <- if content.isEmpty then ZIO.unit
                    else
                      for
                        now        <- Clock.instant
                        sessionKey  = cfg.sessionScopeStrategy.build("discord", message.author.id)
                        _          <- discord.open(sessionKey).ignore
                        _          <- ensureOutboundPump(
                                        sessionKey = sessionKey,
                                        discord = discord,
                                        token = token,
                                        cfg = cfg,
                                        sessionToChan = sessionToChan,
                                        outboundPumps = outboundPumps,
                                      )
                        _          <- sessionToChan.update(_.updated(sessionKey, message.channel_id))
                        normalized <- NormalizedMessage.userInbound(
                                        id = message.id,
                                        channelName = "discord",
                                        sessionKey = sessionKey,
                                        content = content,
                                        metadata = Map(
                                          "discord.message_id"   -> message.id,
                                          "discord.channel_id"   -> message.channel_id,
                                          "discord.user_id"      -> message.author.id,
                                          "discord.is_dm"        -> isDm.toString,
                                          "discord.mentions_bot" -> mentionsBot.toString,
                                        ) ++ message.guild_id.map("discord.guild_id" -> _),
                                      )
                        _          <- gatewayService
                                        .processInbound(normalized)
                                        .mapError(err => DiscordRuntimeError.TransportFailure(err.toString))
                        _          <- channelRegistry.markActivity("discord", now)
                        _          <- channelRegistry.markConnected("discord")
                      yield ()
    yield ()

  private def ensureOutboundPump(
    sessionKey: SessionKey,
    discord: DiscordChannel,
    token: String,
    cfg: DiscordRuntimeConfig,
    sessionToChan: Ref[Map[SessionKey, String]],
    outboundPumps: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]],
  ): IO[DiscordRuntimeError, Unit] =
    outboundPumps.get.flatMap { current =>
      if current.contains(sessionKey) then ZIO.unit
      else
        discord
          .outbound(sessionKey)
          .mapZIO { message =>
            resolveOutboundChannelId(message, sessionKey, cfg, sessionToChan)
              .flatMap(channelId => sendChannelMessage(token, channelId, message.content))
              .tapBoth(
                err => channelRegistry.markError("discord", err.toString),
                _ => Clock.instant.flatMap(channelRegistry.markActivity("discord", _)),
              )
              .ignore
          }
          .runDrain
          .catchAll(_ => ZIO.unit)
          .forkDaemon
          .flatMap(fiber => outboundPumps.update(_ + (sessionKey -> fiber)))
    }

  private def resolveOutboundChannelId(
    message: NormalizedMessage,
    sessionKey: SessionKey,
    cfg: DiscordRuntimeConfig,
    sessionToChan: Ref[Map[SessionKey, String]],
  ): IO[DiscordRuntimeError, String] =
    sessionToChan.get.flatMap { mapping =>
      val chosen =
        message.metadata.get("discord.channel_id")
          .orElse(mapping.get(sessionKey))
          .orElse(cfg.defaultChannelId)
          .map(_.trim)
          .filter(_.nonEmpty)
      ZIO
        .fromOption(chosen)
        .orElseFail(DiscordRuntimeError.InvalidConfiguration("No Discord channel id available for outbound message"))
    }

  private def sendChannelMessage(token: String, channelId: String, content: String): IO[DiscordRuntimeError, Unit] =
    ZIO
      .attemptBlocking {
        val body    = OutboundMessage(content).toJson
        val request = HttpRequest
          .newBuilder(URI.create(s"https://discord.com/api/v10/channels/$channelId/messages"))
          .header("Authorization", s"Bot $token")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }
      .mapError(err => DiscordRuntimeError.TransportFailure(Option(err.getMessage).getOrElse(err.toString)))
      .flatMap { response =>
        if response.statusCode() / 100 == 2 then ZIO.unit
        else ZIO.fail(DiscordRuntimeError.HttpFailure(response.statusCode(), response.body()))
      }

  private def sendWsText(ws: WebSocket, payload: String): IO[DiscordRuntimeError, Unit] =
    ZIO
      .attemptBlocking(ws.sendText(payload, true).join())
      .mapError(err => DiscordRuntimeError.TransportFailure(Option(err.getMessage).getOrElse(err.toString)))
      .unit

  private def closeSocket(ws: WebSocket): UIO[Unit] =
    ZIO.attemptBlocking(ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join()).unit.orDie.ignore

  private def stopOutboundPumps(ref: Ref[Map[SessionKey, Fiber.Runtime[Nothing, Unit]]]): UIO[Unit] =
    ref.get.flatMap(fibers => ZIO.foreachDiscard(fibers.values)(_.interrupt))

  private def decodeEnvelope(payload: String): IO[DiscordRuntimeError, GatewayEnvelope] =
    ZIO
      .fromEither(payload.fromJson[GatewayEnvelope].left.map(DiscordRuntimeError.DecodeFailure.apply))

  private def decodePayload[A: JsonDecoder](payload: Json): IO[DiscordRuntimeError, A] =
    ZIO
      .fromEither(payload.toJson.fromJson[A].left.map(DiscordRuntimeError.DecodeFailure.apply))

  private def takeWsEvent(queue: LinkedBlockingQueue[WsEvent]): IO[DiscordRuntimeError, WsEvent] =
    ZIO
      .attemptBlocking(queue.take())
      .mapError(err => DiscordRuntimeError.TransportFailure(Option(err.getMessage).getOrElse(err.toString)))

  private def fromSettings(settings: Map[String, String]): DiscordRuntimeConfig =
    DiscordRuntimeConfig(
      enabled = settings.get("channel.discord.enabled").exists(_.equalsIgnoreCase("true")),
      token = settings.get("channel.discord.botToken").map(_.trim).filter(_.nonEmpty),
      guildId = settings.get("channel.discord.guildId").map(_.trim).filter(_.nonEmpty),
      defaultChannelId = settings.get("channel.discord.channelId").map(_.trim).filter(_.nonEmpty),
      sessionScopeStrategy = settings
        .get("channel.discord.sessionScopeStrategy")
        .flatMap(SessionScopeStrategy.fromString)
        .getOrElse(SessionScopeStrategy.PerConversation),
    )

  private def escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def stripBotMentionPrefix(content: String): String =
    content
      .replaceFirst("^<@!?\\d+>\\s*", "")
      .trim
