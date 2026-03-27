package gateway.control

import scala.concurrent.{ ExecutionContext, Future }

import zio.*

import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.methods.{ EditMessageReplyMarkup, GetUpdates, ParseMode, SendDocument, SendMessage }
import com.bot4s.telegram.models.{ Chat as BotChat, Message as BotMessage, Update as BotUpdate, User as BotUser, * }
import gateway.entity.*
import io.circe.Decoder

trait TelegramClient:
  def getUpdates(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): IO[TelegramClientError, List[TelegramUpdate]]

  def sendMessage(
    request: TelegramSendMessage,
    timeout: Duration = 30.seconds,
  ): IO[TelegramClientError, TelegramMessage]

  def sendDocument(
    request: TelegramSendDocument,
    timeout: Duration = 120.seconds,
  ): IO[TelegramClientError, TelegramMessage]

  def editMessageReplyMarkup(
    chatId: Long,
    messageId: Long,
    replyMarkup: Option[TelegramInlineKeyboardMarkup],
    timeout: Duration = 30.seconds,
  ): IO[TelegramClientError, Unit] =
    sendMessage(
      TelegramSendMessage(
        chat_id = chatId,
        text = "Updated controls.",
        reply_to_message_id = Some(messageId),
        reply_markup = replyMarkup,
      ),
      timeout,
    ).unit

object TelegramClient:
  def getUpdates(
    offset: Option[Long] = None,
    limit: Int = 100,
    timeoutSeconds: Int = 30,
    timeout: Duration = 60.seconds,
  ): ZIO[TelegramClient, TelegramClientError, List[TelegramUpdate]] =
    ZIO.serviceWithZIO[TelegramClient](_.getUpdates(offset, limit, timeoutSeconds, timeout))

  def sendMessage(
    request: TelegramSendMessage,
    timeout: Duration = 30.seconds,
  ): ZIO[TelegramClient, TelegramClientError, TelegramMessage] =
    ZIO.serviceWithZIO[TelegramClient](_.sendMessage(request, timeout))

  def sendDocument(
    request: TelegramSendDocument,
    timeout: Duration = 120.seconds,
  ): ZIO[TelegramClient, TelegramClientError, TelegramMessage] =
    ZIO.serviceWithZIO[TelegramClient](_.sendDocument(request, timeout))

  def fromRequestHandler(
    requestHandler: RequestHandler[Future]
  )(using ec: ExecutionContext
  ): TelegramClient =
    TelegramClientLive(requestHandler)

  val live: ZLayer[Bot4sRequestHandler & ExecutionContext, Nothing, TelegramClient] =
    ZLayer.fromFunction((handler: Bot4sRequestHandler, ec: ExecutionContext) =>
      fromRequestHandler(handler.value)(using ec)
    )

final case class Bot4sRequestHandler(value: RequestHandler[Future])

final case class TelegramClientLive(
  requestHandler: RequestHandler[Future]
)(using ExecutionContext
) extends TelegramClient:
  private given Decoder[Either[Boolean, BotMessage]] =
    Decoder.instance { cursor =>
      Decoder[Boolean]
        .tryDecode(cursor)
        .map(Left(_))
        .orElse(Decoder[BotMessage].tryDecode(cursor).map(Right(_)))
    }

  override def getUpdates(
    offset: Option[Long],
    limit: Int,
    timeoutSeconds: Int,
    timeout: Duration,
  ): IO[TelegramClientError, List[TelegramUpdate]] =
    val method: GetUpdates = GetUpdates(
      offset = offset,
      limit = Some(limit),
      timeout = Some(timeoutSeconds),
    )
    executeFuture(requestHandler.sendRequest(method), timeout).map { parsed =>
      parsed.toList.collect {
        case success: ParsedUpdate.Success => fromBotUpdate(success.update)
      }
    }

  override def sendMessage(
    request: TelegramSendMessage,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    val method: SendMessage = SendMessage(
      chatId = ChatId(request.chat_id),
      text = request.text,
      parseMode = request.parse_mode.flatMap(parseModeFromString),
      disableWebPagePreview = request.disable_web_page_preview,
      replyToMessageId = request.reply_to_message_id.flatMap(longToInt),
      replyMarkup = request.reply_markup.map(toBotReplyMarkup),
    )
    executeFuture(requestHandler.sendRequest(method), timeout).map(fromBotMessage)

  override def sendDocument(
    request: TelegramSendDocument,
    timeout: Duration,
  ): IO[TelegramClientError, TelegramMessage] =
    val method: SendDocument = SendDocument(
      chatId = ChatId(request.chat_id),
      document = InputFile(java.nio.file.Paths.get(request.document_path)),
      caption = request.caption,
      parseMode = request.parse_mode.flatMap(parseModeFromString),
      replyToMessageId = request.reply_to_message_id,
    )
    executeFuture(requestHandler.sendRequest(method), timeout).map(fromBotMessage)

  override def editMessageReplyMarkup(
    chatId: Long,
    messageId: Long,
    replyMarkup: Option[TelegramInlineKeyboardMarkup],
    timeout: Duration,
  ): IO[TelegramClientError, Unit] =
    val method = EditMessageReplyMarkup(
      chatId = Some(ChatId(chatId)),
      messageId = longToInt(messageId),
      replyMarkup = replyMarkup.map(toBotReplyMarkup),
    )
    executeFuture(requestHandler.sendRequest(method), timeout).unit

  private def executeFuture[A](
    future: => Future[A],
    timeout: Duration,
  ): IO[TelegramClientError, A] =
    ZIO
      .fromFuture(_ => future)
      .timeoutFail(TelegramClientError.Timeout(timeout))(timeout)
      .mapError {
        case err: TelegramClientError => err
        case throwable: Throwable     => mapThrowable(throwable)
      }

  private def mapThrowable(throwable: Throwable): TelegramClientError =
    val message = Option(throwable.getMessage).getOrElse(throwable.toString)
    if message.contains("429") || message.toLowerCase.contains("too many requests") then
      TelegramClientError.RateLimited(None, message)
    else if message.toLowerCase.contains("json") || message.toLowerCase.contains("parse") then
      TelegramClientError.ParseError(message, "")
    else TelegramClientError.Network(message)

  private def fromBotUpdate(update: BotUpdate): TelegramUpdate =
    TelegramUpdate(
      update_id = update.updateId,
      message = update.message.map(fromBotMessage),
      edited_message = update.editedMessage.map(fromBotMessage),
      callback_query = update.callbackQuery.map(fromBotCallbackQuery),
    )

  private def fromBotMessage(message: BotMessage): TelegramMessage =
    TelegramMessage(
      message_id = message.messageId,
      date = message.date.toLong,
      chat = fromBotChat(message.chat),
      text = message.text,
      caption = message.caption,
      document = message.document.map(d =>
        gateway.entity.TelegramDocument(
          file_id = d.fileId,
          file_unique_id = d.fileUniqueId,
          file_name = d.fileName,
          mime_type = d.mimeType,
          file_size = d.fileSize.map(_.toLong),
        )
      ),
      from = message.from.map(fromBotUser),
    )

  private def fromBotChat(chat: BotChat): TelegramChat =
    TelegramChat(
      id = chat.id,
      `type` = chat.`type`.toString,
      title = chat.title,
      username = chat.username,
    )

  private def fromBotUser(user: BotUser): TelegramUser =
    TelegramUser(
      id = user.id,
      is_bot = user.isBot,
      first_name = user.firstName,
      username = user.username,
    )

  private def fromBotCallbackQuery(cq: CallbackQuery): TelegramCallbackQuery =
    TelegramCallbackQuery(
      id = cq.id,
      from = fromBotUser(cq.from),
      message = cq.message.map(fromBotMessage),
      data = cq.data,
    )

  private def toBotReplyMarkup(markup: TelegramInlineKeyboardMarkup): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
      markup.inline_keyboard.map(row =>
        row.map(btn =>
          InlineKeyboardButton(
            text = btn.text,
            callbackData = btn.callback_data,
            url = btn.url,
          )
        )
      )
    )

  private def parseModeFromString(s: String): Option[ParseMode.Value] =
    s.trim.toLowerCase match
      case "markdown"   => Some(ParseMode.Markdown)
      case "markdownv2" => Some(ParseMode.MarkdownV2)
      case "html"       => Some(ParseMode.HTML)
      case _            => None

  private def longToInt(l: Long): Option[Int] =
    if l >= Int.MinValue && l <= Int.MaxValue then Some(l.toInt) else None
