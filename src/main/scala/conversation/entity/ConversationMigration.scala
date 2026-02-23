package conversation.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids
import shared.store.{ ChatMessageRow, ConversationRow, DataStoreModule }

object ConversationMigration:

  def migrateLegacyRows: ZIO[DataStoreModule.DataStoreService & ConversationRepository, PersistenceError, Int] =
    for
      dataStore <- ZIO.service[DataStoreModule.DataStoreService]
      repo      <- ZIO.service[ConversationRepository]
      keys      <- dataStore.rawStore
                     .streamKeys[String]
                     .filter(_.startsWith("conv:"))
                     .runCollect
                     .mapError(err => PersistenceError.QueryFailed("migrateConversationLegacyRows", err.toString))
      count     <- ZIO.foreach(keys.toList) { key =>
                     dataStore.store
                       .fetch[String, ConversationRow](key)
                       .mapError(err => PersistenceError.QueryFailed("migrateConversationLegacyRows", err.toString))
                       .flatMap {
                         case Some(row) =>
                           messagesFor(row.id, dataStore).flatMap { messages =>
                             ZIO.foreachDiscard(toEvents(row, messages))(repo.append) *> ZIO.succeed(1)
                           }
                         case None      => ZIO.succeed(0)
                       }
                   }
    yield count.sum

  private def messagesFor(id: String, dataStore: DataStoreModule.DataStoreService)
    : IO[PersistenceError, List[ChatMessageRow]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith("msg:"))
      .runCollect
      .mapError(err => PersistenceError.QueryFailed("migrateConversationMessages", err.toString))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key =>
          dataStore.store.fetch[String, ChatMessageRow](key).mapError(err =>
            PersistenceError.QueryFailed("migrateConversationMessages", err.toString)
          )
        )
      )
      .map(_.flatten.filter(_.conversationId == id).sortBy(_.createdAt))

  private def toChannel(row: ConversationRow): ChannelInfo =
    row.channelName.map(_.trim).filter(_.nonEmpty) match
      case Some(channel) if channel.equalsIgnoreCase("telegram") => ChannelInfo.Telegram(channel)
      case Some(channel)                                         => ChannelInfo.Web(channel)
      case None                                                  => ChannelInfo.Internal

  private def toMessage(row: ChatMessageRow): Message =
    val senderType  = row.senderType.trim.toLowerCase match
      case "user"      => SenderType.User()
      case "assistant" => SenderType.Assistant()
      case "system"    => SenderType.System()
      case other       => SenderType.Unknown(other)
    val messageType = row.messageType.trim.toLowerCase match
      case "text"   => MessageType.Text()
      case "code"   => MessageType.Code()
      case "error"  => MessageType.Error()
      case "status" => MessageType.Status()
      case other    => MessageType.Unknown(other)
    Message(
      id = Ids.MessageId(row.id),
      sender = row.sender,
      senderType = senderType,
      content = row.content,
      messageType = messageType,
      createdAt = row.createdAt,
      metadata = row.metadata.map(raw => Map("legacy" -> raw)).getOrElse(Map.empty),
    )

  private def toEvents(row: ConversationRow, messages: List[ChatMessageRow]): List[ConversationEvent] =
    val conversationId = Ids.ConversationId(row.id)
    val runId          = row.runId.map(Ids.TaskRunId.apply)
    val created        = ConversationEvent.Created(
      conversationId = conversationId,
      channel = toChannel(row),
      title = row.title,
      description = row.description.getOrElse(""),
      runId = runId,
      createdBy = row.createdBy,
      occurredAt = row.createdAt,
    )

    val sentEvents = messages.map { message =>
      ConversationEvent.MessageSent(
        conversationId = conversationId,
        message = toMessage(message),
        occurredAt = message.createdAt,
      )
    }

    val closeEvent =
      if row.status.equalsIgnoreCase("closed") then
        List(ConversationEvent.Closed(conversationId, row.updatedAt, row.updatedAt))
      else Nil

    created :: (sentEvents ++ closeEvent)
