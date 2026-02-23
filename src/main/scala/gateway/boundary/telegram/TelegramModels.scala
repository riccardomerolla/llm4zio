package gateway.boundary.telegram

import zio.json.*

case class TelegramUser(
  id: Long,
  is_bot: Boolean,
  first_name: String,
  username: Option[String] = None,
) derives JsonCodec

case class TelegramChat(
  id: Long,
  `type`: String,
  title: Option[String] = None,
  username: Option[String] = None,
) derives JsonCodec

case class TelegramMessage(
  message_id: Long,
  date: Long,
  chat: TelegramChat,
  text: Option[String] = None,
  caption: Option[String] = None,
  document: Option[TelegramDocument] = None,
  from: Option[TelegramUser] = None,
) derives JsonCodec

case class TelegramDocument(
  file_id: String,
  file_unique_id: String,
  file_name: Option[String] = None,
  mime_type: Option[String] = None,
  file_size: Option[Long] = None,
) derives JsonCodec

case class TelegramCallbackQuery(
  id: String,
  from: TelegramUser,
  message: Option[TelegramMessage] = None,
  data: Option[String] = None,
) derives JsonCodec

case class TelegramUpdate(
  update_id: Long,
  message: Option[TelegramMessage] = None,
  edited_message: Option[TelegramMessage] = None,
  callback_query: Option[TelegramCallbackQuery] = None,
) derives JsonCodec

case class TelegramInlineKeyboardButton(
  text: String,
  callback_data: Option[String] = None,
  url: Option[String] = None,
) derives JsonCodec

case class TelegramInlineKeyboardMarkup(
  inline_keyboard: List[List[TelegramInlineKeyboardButton]]
) derives JsonCodec

case class TelegramSendMessage(
  chat_id: Long,
  text: String,
  parse_mode: Option[String] = None,
  disable_web_page_preview: Option[Boolean] = None,
  reply_to_message_id: Option[Long] = None,
  reply_markup: Option[TelegramInlineKeyboardMarkup] = None,
) derives JsonCodec

case class TelegramSendDocument(
  chat_id: Long,
  document_path: String,
  file_name: Option[String] = None,
  caption: Option[String] = None,
  parse_mode: Option[String] = None,
  reply_to_message_id: Option[Long] = None,
) derives JsonCodec

case class TelegramFile(
  file_id: String,
  file_unique_id: String,
  file_size: Option[Long] = None,
  file_path: Option[String] = None,
) derives JsonCodec

case class TelegramApiErrorParameters(
  retry_after: Option[Int] = None
) derives JsonCodec

case class TelegramApiResponse[T](
  ok: Boolean,
  result: Option[T] = None,
  description: Option[String] = None,
  error_code: Option[Int] = None,
  parameters: Option[TelegramApiErrorParameters] = None,
)

object TelegramApiResponse:
  given [T: JsonDecoder]: JsonDecoder[TelegramApiResponse[T]] = DeriveJsonDecoder.gen[TelegramApiResponse[T]]
