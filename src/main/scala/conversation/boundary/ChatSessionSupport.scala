package conversation.boundary

import java.time.Instant

import zio.*

import conversation.entity.ChatRepository
import conversation.entity.api.*
import conversation.entity.ChatRepository
import gateway.control.{ ChannelRegistry, MessageChannelError }
import gateway.entity.{ ChatSession, SessionKey }
import shared.errors.PersistenceError

final private[boundary] case class ChatSessionSupport(
  chatRepository: ChatRepository,
  channelRegistry: ChannelRegistry,
  sanitizeString: String => Option[String],
  resolveAgentName: Map[String, String] => Option[String],
):

  def parseCurrentConversationIdFromPath(path: String): Option[String] =
    if path.startsWith("/chat/") then
      val id = path.stripPrefix("/chat/").takeWhile(_ != '/').takeWhile(_ != '?')
      sanitizeString(id)
    else None

  def listChatSessions: IO[PersistenceError, List[ChatSession]] =
    for
      links         <- chatRepository.listSessionContextStates
      conversations <- chatRepository.listConversations(0, Int.MaxValue)
      convById       = conversations.flatMap(conversation => safeConversationId(conversation).map(_ -> conversation)).toMap
      sessions      <- ZIO.foreach(links)(buildChatSession(_, convById))
    yield sessions
      .filterNot(_.state.equalsIgnoreCase("closed"))
      .sortBy(_.lastActivity)(using Ordering[Instant].reverse)

  def getChatSession(sessionId: String): IO[PersistenceError, ChatSession] =
    listChatSessions.flatMap { sessions =>
      sessions
        .find(_.sessionId == sessionId)
        .fold[IO[PersistenceError, ChatSession]](
          ZIO.fail(PersistenceError.QueryFailed("get_session", s"Session not found: $sessionId"))
        )(ZIO.succeed(_))
    }

  def endSession(sessionId: String): IO[PersistenceError, Unit] =
    for
      ids      <- parseSessionId(sessionId)
      (ch, key) = ids
      now      <- Clock.instant
      context  <- chatRepository.getSessionContextState(ch, key)
      _        <- ZIO.foreachDiscard(context.flatMap(_.conversationId)) { conversationId =>
                    chatRepository.getConversation(conversationId).flatMap {
                      case Some(conversation) =>
                        chatRepository.updateConversation(conversation.copy(status = "closed", updatedAt = now))
                      case None               => ZIO.unit
                    }
                  }
      _        <- chatRepository.deleteSessionContext(ch, key)
      _        <- closeSessionChannel(ch, key)
    yield ()

  private def buildChatSession(
    link: StoredSessionContextLink,
    convById: Map[Long, ChatConversation],
  ): UIO[ChatSession] =
    val context               = link.context
    val conversationFromStore = safeOption(context.conversationId) match
      case Some(conversationId) => convById.get(conversationId)
      case None                 => None
    val sessionId             = s"${link.channelName.trim}:${link.sessionKey.trim}"
    val runIdFromConversation = conversationFromStore match
      case Some(conversation) => safeLongFromStringOption(safeOption(conversation.runId))
      case None               => None
    val resolvedRunId         = safeOption(context.runId).orElse(runIdFromConversation)
    val resolvedState         = conversationFromStore.map(_.status).getOrElse("active")
    val resolvedCount         = conversationFromStore.map(_.messages.length).getOrElse(0)
    val resolvedConversation  =
      safeOption(context.conversationId).orElse {
        conversationFromStore match
          case Some(conversation) => safeConversationId(conversation)
          case None               => None
      }
    ZIO.succeed(
      ChatSession(
        sessionId = sessionId,
        channel = sanitizeString(link.channelName).getOrElse("unknown"),
        sessionKey = link.sessionKey,
        agentName = resolveAgentName(context.metadata),
        messageCount = resolvedCount,
        lastActivity = link.updatedAt,
        state = resolvedState,
        conversationId = resolvedConversation,
        runId = resolvedRunId,
      )
    )

  private def closeSessionChannel(channelName: String, sessionKey: String): UIO[Unit] =
    channelRegistry
      .get(channelName)
      .flatMap(_.close(SessionKey(channelName, sessionKey)))
      .catchAll {
        case MessageChannelError.ChannelNotFound(_) =>
          ZIO.unit
        case other                                  =>
          ZIO.logWarning(s"Failed to close session $channelName:$sessionKey: $other")
      }

  private def parseSessionId(sessionId: String): IO[PersistenceError, (String, String)] =
    val normalized = sessionId.trim
    val idx        = normalized.indexOf(':')
    if idx <= 0 || idx >= normalized.length - 1 then
      ZIO.fail(PersistenceError.QueryFailed("parse_session_id", s"Invalid session id '$sessionId'"))
    else
      val channel    = normalized.take(idx).trim
      val sessionKey = normalized.drop(idx + 1).trim
      if channel.isEmpty || sessionKey.isEmpty then
        ZIO.fail(PersistenceError.QueryFailed("parse_session_id", s"Invalid session id '$sessionId'"))
      else ZIO.succeed((channel, sessionKey))

  private def safeOption[A](value: Option[A]): Option[A] =
    try
      value match
        case Some(inner) => Option(inner)
        case None        => None
    catch
      case _: Throwable => None

  private def safeLongFromStringOption(value: Option[String]): Option[Long] =
    safeOption(value) match
      case Some(raw) => raw.toLongOption
      case None      => None

  private def safeConversationId(conversation: ChatConversation): Option[Long] =
    Option(conversation) match
      case Some(conv) => safeLongFromStringOption(safeOption(conv.id))
      case None       => None
