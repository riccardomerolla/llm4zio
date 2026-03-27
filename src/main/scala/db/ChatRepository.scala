package db

import java.time.Instant

import zio.*
import zio.json.*

import conversation.entity.api.*
import shared.errors.PersistenceError

trait ChatRepository:
  // Chat Conversations
  def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]
  def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]
  def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]
  def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]]
  def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]
  def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]
  def deleteConversation(id: Long): IO[PersistenceError, Unit]

  // Chat Messages
  def addMessage(message: ConversationEntry): IO[PersistenceError, Long]
  def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]]
  def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]]

  // Channel session context storage (gateway integration)
  def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("upsertSessionContext", "Not implemented"))

  def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[String]] =
    ZIO.fail(PersistenceError.QueryFailed("getSessionContext", "Not implemented"))

  def getSessionContextByConversation(conversationId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    ZIO.fail(PersistenceError.QueryFailed("getSessionContextByConversation", "Not implemented"))

  def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
    ZIO.fail(PersistenceError.QueryFailed("getSessionContextByTaskRunId", "Not implemented"))

  def listSessionContexts: IO[PersistenceError, List[SessionContextLink]] =
    ZIO.fail(PersistenceError.QueryFailed("listSessionContexts", "Not implemented"))

  def upsertSessionContextState(
    channelName: String,
    sessionKey: String,
    context: StoredSessionContext,
    updatedAt: Instant,
  ): IO[PersistenceError, Unit] =
    upsertSessionContext(channelName, sessionKey, context.toJson, updatedAt)

  def getSessionContextState(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[StoredSessionContext]] =
    getSessionContext(channelName, sessionKey).flatMap {
      case None      => ZIO.none
      case Some(raw) =>
        ChatRepository.decodeStoredSessionContext(raw).map(_.map(identity[StoredSessionContext]))
    }

  def getSessionContextStateLink(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Option[StoredSessionContextLink]] =
    listSessionContexts.flatMap { links =>
      links.find(link => link.channelName == channelName && link.sessionKey == sessionKey) match
        case None       => ZIO.none
        case Some(link) =>
          ChatRepository.decodeStoredSessionContext(link.contextJson).map(
            _.map(context => StoredSessionContextLink(link.channelName, link.sessionKey, context, link.updatedAt))
          )
    }

  def getSessionContextStateByConversation(
    conversationId: Long
  ): IO[PersistenceError, Option[StoredSessionContextLink]] =
    getSessionContextByConversation(conversationId).flatMap {
      case None       => ZIO.none
      case Some(link) =>
        ChatRepository.decodeStoredSessionContext(link.contextJson).map(
          _.map(context => StoredSessionContextLink(link.channelName, link.sessionKey, context, link.updatedAt))
        )
    }

  def getSessionContextStateByTaskRunId(
    taskRunId: Long
  ): IO[PersistenceError, Option[StoredSessionContextLink]] =
    getSessionContextByTaskRunId(taskRunId).flatMap {
      case None       => ZIO.none
      case Some(link) =>
        ChatRepository.decodeStoredSessionContext(link.contextJson).map(
          _.map(context => StoredSessionContextLink(link.channelName, link.sessionKey, context, link.updatedAt))
        )
    }

  def listSessionContextStates: IO[PersistenceError, List[StoredSessionContextLink]] =
    listSessionContexts.flatMap { links =>
      ZIO.foreach(links) { link =>
        ChatRepository
          .decodeStoredSessionContext(link.contextJson)
          .map(_.map(context => StoredSessionContextLink(link.channelName, link.sessionKey, context, link.updatedAt)))
      }.map(_.flatten)
    }

  def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("deleteSessionContext", "Not implemented"))

import shared.store.DataStoreModule

object ChatRepository:
  private def decodeStoredSessionContext(raw: String): UIO[Option[StoredSessionContext]] =
    val payload = Option(raw).map(_.trim).filter(_.nonEmpty)
    payload match
      case None          => ZIO.none
      case Some(content) =>
        ZIO
          .attempt(content.fromJson[StoredSessionContext])
          .fold(
            _ => None,
            {
              case Right(value) => Some(value)
              case Left(_)      => None
            },
          )

  def createConversation(conversation: ChatConversation): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.createConversation(conversation))

  def getConversation(id: Long): ZIO[ChatRepository, PersistenceError, Option[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.getConversation(id))

  def listConversations(offset: Int, limit: Int): ZIO[ChatRepository, PersistenceError, List[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.listConversations(offset, limit))

  def getConversationsByChannel(channelName: String): ZIO[ChatRepository, PersistenceError, List[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.getConversationsByChannel(channelName))

  def listConversationsByRun(runId: Long): ZIO[ChatRepository, PersistenceError, List[ChatConversation]] =
    ZIO.serviceWithZIO[ChatRepository](_.listConversationsByRun(runId))

  def updateConversation(conversation: ChatConversation): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.updateConversation(conversation))

  def deleteConversation(id: Long): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.deleteConversation(id))

  def addMessage(message: ConversationEntry): ZIO[ChatRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ChatRepository](_.addMessage(message))

  def getMessages(conversationId: Long): ZIO[ChatRepository, PersistenceError, List[ConversationEntry]] =
    ZIO.serviceWithZIO[ChatRepository](_.getMessages(conversationId))

  def getMessagesSince(conversationId: Long, since: Instant)
    : ZIO[ChatRepository, PersistenceError, List[ConversationEntry]] =
    ZIO.serviceWithZIO[ChatRepository](_.getMessagesSince(conversationId, since))

  def upsertSessionContext(
    channelName: String,
    sessionKey: String,
    contextJson: String,
    updatedAt: Instant,
  ): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.upsertSessionContext(channelName, sessionKey, contextJson, updatedAt))

  def getSessionContext(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Option[String]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContext(channelName, sessionKey))

  def getSessionContextByConversation(conversationId: Long)
    : ZIO[ChatRepository, PersistenceError, Option[SessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextByConversation(conversationId))

  def getSessionContextByTaskRunId(taskRunId: Long): ZIO[ChatRepository, PersistenceError, Option[SessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextByTaskRunId(taskRunId))

  def listSessionContexts: ZIO[ChatRepository, PersistenceError, List[SessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.listSessionContexts)

  def upsertSessionContextState(
    channelName: String,
    sessionKey: String,
    context: StoredSessionContext,
    updatedAt: Instant,
  ): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.upsertSessionContextState(channelName, sessionKey, context, updatedAt))

  def getSessionContextState(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Option[StoredSessionContext]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextState(channelName, sessionKey))

  def getSessionContextStateLink(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Option[StoredSessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextStateLink(channelName, sessionKey))

  def getSessionContextStateByConversation(
    conversationId: Long
  ): ZIO[ChatRepository, PersistenceError, Option[StoredSessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextStateByConversation(conversationId))

  def getSessionContextStateByTaskRunId(
    taskRunId: Long
  ): ZIO[ChatRepository, PersistenceError, Option[StoredSessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.getSessionContextStateByTaskRunId(taskRunId))

  def listSessionContextStates: ZIO[ChatRepository, PersistenceError, List[StoredSessionContextLink]] =
    ZIO.serviceWithZIO[ChatRepository](_.listSessionContextStates)

  def deleteSessionContext(
    channelName: String,
    sessionKey: String,
  ): ZIO[ChatRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ChatRepository](_.deleteSessionContext(channelName, sessionKey))

  val live
    : ZLayer[
      DataStoreModule.DataStoreService,
      Nothing,
      ChatRepository,
    ] =
    ChatRepositoryLive.live
