package web.controllers

import zio.*
import zio.http.*
import zio.json.*

import _root_.models.{ MigrationConfig, TelegramMode }
import gateway.*
import gateway.telegram.{ TelegramChannel, TelegramUpdate }

trait TelegramController:
  def routes: Routes[Any, Response]

object TelegramController:
  private val ProcessedUpdatesCapacity      = 10000
  private[controllers] val SecretHeaderName = "X-Telegram-Bot-Api-Secret-Token"

  def routes: ZIO[TelegramController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[TelegramController](_.routes)

  def make(
    gatewayService: GatewayService,
    channelRegistry: ChannelRegistry,
    expectedBotToken: Option[String],
    expectedSecretToken: Option[String],
    enabled: Boolean,
    mode: TelegramMode,
  ): UIO[TelegramController] =
    Ref
      .make((Set.empty[Long], List.empty[Long]))
      .map(processedUpdates =>
        TelegramControllerLive(
          gatewayService = gatewayService,
          channelRegistry = channelRegistry,
          expectedBotToken = expectedBotToken.map(_.trim).filter(_.nonEmpty),
          expectedSecretToken = expectedSecretToken.map(_.trim).filter(_.nonEmpty),
          enabled = enabled,
          mode = mode,
          processedUpdatesRef = processedUpdates,
          processedUpdatesCapacity = ProcessedUpdatesCapacity,
        )
      )

  val live: ZLayer[GatewayService & ChannelRegistry & MigrationConfig, Nothing, TelegramController] =
    ZLayer.fromZIO {
      for
        gateway    <- ZIO.service[GatewayService]
        registry   <- ZIO.service[ChannelRegistry]
        config     <- ZIO.service[MigrationConfig]
        telegramCfg = config.telegram
        controller <- make(
                        gatewayService = gateway,
                        channelRegistry = registry,
                        expectedBotToken = telegramCfg.botToken,
                        expectedSecretToken = telegramCfg.secretToken,
                        enabled = telegramCfg.enabled,
                        mode = telegramCfg.mode,
                      )
      yield controller
    }

final case class TelegramControllerLive(
  gatewayService: GatewayService,
  channelRegistry: ChannelRegistry,
  expectedBotToken: Option[String],
  expectedSecretToken: Option[String],
  enabled: Boolean,
  mode: TelegramMode,
  processedUpdatesRef: Ref[(Set[Long], List[Long])],
  processedUpdatesCapacity: Int,
) extends TelegramController:

  override val routes: Routes[Any, Response] = Routes(
    Method.POST / "webhook" / "telegram" / string("botToken") -> handler { (botToken: String, req: Request) =>
      handleWebhook(botToken, req)
    }
  )

  private def handleWebhook(botToken: String, req: Request): UIO[Response] =
    verifyAvailability.orElse(verifyTokens(botToken, req)) match
      case Some(response) => ZIO.succeed(response)
      case None           =>
        for
          body     <- req.body.asString.orElseSucceed("")
          response <- parseUpdate(body) match
                        case Left(error)   =>
                          ZIO.succeed(
                            Response.text(s"invalid telegram update payload: $error").status(Status.BadRequest)
                          )
                        case Right(update) =>
                          for
                            firstSeen <- registerUpdate(update.update_id)
                            resp      <-
                              if !firstSeen then
                                ZIO.succeed(Response.text("duplicate update ignored").status(Status.Ok))
                              else routeUpdate(update)
                          yield resp
        yield response

  private def verifyTokens(botToken: String, req: Request): Option[Response] =
    val expectedPathToken = expectedBotToken
    val providedSecret    = headerValue(req, TelegramController.SecretHeaderName).map(_.trim).filter(_.nonEmpty)

    if expectedPathToken.exists(_ != botToken.trim) then
      Some(Response.text("invalid bot token").status(Status.Unauthorized))
    else if expectedSecretToken.exists(secret => providedSecret.forall(_ != secret)) then
      Some(Response.text("invalid secret token").status(Status.Forbidden))
    else None

  private def verifyAvailability: Option[Response] =
    if !enabled then Some(Response.text("telegram integration disabled").status(Status.NotFound))
    else
      mode match
        case TelegramMode.Webhook => None
        case TelegramMode.Polling =>
          Some(Response.text("telegram webhook disabled while polling mode is active").status(Status.NotFound))

  private def parseUpdate(rawBody: String): Either[String, TelegramUpdate] =
    rawBody.fromJson[TelegramUpdate]

  private def routeUpdate(update: TelegramUpdate): UIO[Response] =
    (for
      channel    <- channelRegistry.get("telegram").mapError(err =>
                      s"telegram channel unavailable: $err"
                    )
      normalized <- channel match
                      case telegramChannel: TelegramChannel =>
                        telegramChannel.ingestUpdate(update).mapError(err =>
                          s"telegram normalization failed: $err"
                        )
                      case other                            =>
                        ZIO.fail(s"channel ${other.name} is not a TelegramChannel")
      _          <- ZIO.foreachDiscard(normalized)(message =>
                      gatewayService.processInbound(message).mapError(err => s"gateway routing failed: $err")
                    )
    yield Response.text("ok").status(Status.Ok))
      .catchAll(error => ZIO.logWarning(s"telegram webhook failed: $error").as(Response.text(error).status(Status.Ok)))

  private def registerUpdate(updateId: Long): UIO[Boolean] =
    processedUpdatesRef.modify {
      case (seen, ordered) =>
        if seen.contains(updateId) then (false, (seen, ordered))
        else
          val appended = ordered :+ updateId
          if appended.length <= processedUpdatesCapacity then (true, (seen + updateId, appended))
          else
            val oldest = appended.head
            (true, (seen - oldest + updateId, appended.tail))
    }

  private def headerValue(req: Request, name: String): Option[String] =
    req.headers.headers
      .find(_.headerName.toString.equalsIgnoreCase(name))
      .map(_.renderedValue)
