package gateway.boundary.telegram

import scala.concurrent.{ ExecutionContext, Future }

import zio.*

import com.bot4s.telegram.clients.FutureSttpClient
import gateway.control.TelegramClient
import gateway.entity.*

final case class ConfigAwareTelegramClient(
  configRef: Ref[_root_.config.entity.GatewayConfig],
  clientsRef: Ref.Synchronized[Map[String, TelegramClient]],
  backend: sttp.client4.WebSocketBackend[Future],
) extends TelegramClient:

  private given ExecutionContext = ExecutionContext.global

  override def getUpdates(
    offset: Option[Long],
    limit: Int,
    timeoutSeconds: Int,
    timeout: Duration,
  ): IO[TelegramClientError, List[TelegramUpdate]] =
    currentClient.flatMap(_.getUpdates(offset, limit, timeoutSeconds, timeout))

  override def sendMessage(
    request: TelegramSendMessage,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    currentClient.flatMap(_.sendMessage(request, timeout))

  override def sendDocument(
    request: TelegramSendDocument,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    currentClient.flatMap(_.sendDocument(request, timeout))

  private def currentClient: IO[TelegramClientError, TelegramClient] =
    for
      config <- configRef.get
      token  <- ZIO
                  .fromOption(config.telegram.botToken.map(_.trim).filter(_.nonEmpty))
                  .orElseFail(
                    TelegramClientError.InvalidConfig(
                      "telegram bot token is not configured; set telegram.botToken in Settings"
                    )
                  )
      client <- clientsRef.modifyZIO { current =>
                  current.get(token) match
                    case Some(existing) =>
                      ZIO.succeed((existing, current))
                    case None           =>
                      ZIO
                        .attempt {
                          val handler = FutureSttpClient(
                            token = token,
                            telegramHost = "api.telegram.org",
                          )(using backend, summon[ExecutionContext])
                          TelegramClient.fromRequestHandler(handler)
                        }
                        .mapError(err =>
                          TelegramClientError.InvalidConfig(
                            s"failed to initialize telegram client: ${Option(err.getMessage).getOrElse(err.toString)}"
                          )
                        )
                        .map(created => (created, current + (token -> created)))
                }
    yield client
