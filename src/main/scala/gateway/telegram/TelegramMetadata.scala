package gateway.telegram

import zio.json.*

case class TelegramMessageMetadata(
  chatId: Long,
  telegramMessageId: Long,
  updateId: Option[Long] = None,
  fromUserId: Option[Long] = None,
  fromUsername: Option[String] = None,
) derives JsonCodec

case class TelegramRoutingState(
  lastInboundMessage: Option[TelegramMessageMetadata] = None,
  lastOutboundMessage: Option[TelegramMessageMetadata] = None,
) derives JsonCodec

object TelegramMetadata:
  private val ChatIdKey           = "telegram.chat_id"
  private val MessageIdKey        = "telegram.message_id"
  private val UpdateIdKey         = "telegram.update_id"
  private val FromUserIdKey       = "telegram.from_user_id"
  private val FromUsernameKey     = "telegram.from_username"
  val ReplyToMessageIdKey: String = "telegram.reply_to_message_id"

  def inboundToMetadata(update: TelegramUpdate, message: TelegramMessage): TelegramMessageMetadata =
    TelegramMessageMetadata(
      chatId = message.chat.id,
      telegramMessageId = message.message_id,
      updateId = Some(update.update_id),
      fromUserId = message.from.map(_.id),
      fromUsername = message.from.flatMap(_.username),
    )

  def outboundToMetadata(message: TelegramMessage): TelegramMessageMetadata =
    TelegramMessageMetadata(
      chatId = message.chat.id,
      telegramMessageId = message.message_id,
      updateId = None,
      fromUserId = message.from.map(_.id),
      fromUsername = message.from.flatMap(_.username),
    )

  def toMap(metadata: TelegramMessageMetadata): Map[String, String] =
    Map(
      ChatIdKey    -> metadata.chatId.toString,
      MessageIdKey -> metadata.telegramMessageId.toString,
    ) ++
      metadata.updateId.map(UpdateIdKey -> _.toString) ++
      metadata.fromUserId.map(FromUserIdKey -> _.toString) ++
      metadata.fromUsername.map(FromUsernameKey -> _)

  def fromMap(metadata: Map[String, String]): Option[TelegramMessageMetadata] =
    for
      chatId    <- metadata.get(ChatIdKey).flatMap(_.toLongOption)
      messageId <- metadata.get(MessageIdKey).flatMap(_.toLongOption)
    yield TelegramMessageMetadata(
      chatId = chatId,
      telegramMessageId = messageId,
      updateId = metadata.get(UpdateIdKey).flatMap(_.toLongOption),
      fromUserId = metadata.get(FromUserIdKey).flatMap(_.toLongOption),
      fromUsername = metadata.get(FromUsernameKey),
    )
