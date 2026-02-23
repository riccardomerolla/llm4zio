package agents

import java.time.Instant

import zio.*

import db.{ ChatRepository, PersistenceError }
import gateway.models.SessionKey
import llm4zio.agents.*
import llm4zio.core.{ Message, MessageRole }
import memory.{ MemoryFilter, ScoredMemory, SessionId as MemorySessionId, UserId as MemoryUserId }
import models.{ AgentInfo, ChatConversation, ConversationEntry, MessageType, SenderType }

object Llm4zioAgentAdapters:
  def metadataFromAgentInfo(info: AgentInfo, defaultVersion: String = "1.0.0"): AgentMetadata =
    AgentMetadata(
      name = info.name,
      capabilities = info.tags.toSet,
      version = defaultVersion,
      description = info.description,
      priority = if info.agentType.toString == "BuiltIn" then 100 else 10,
    )

  def stubAgent(info: AgentInfo, defaultVersion: String = "1.0.0"): Agent =
    val md = metadataFromAgentInfo(info, defaultVersion)

    new Agent:
      override def metadata: AgentMetadata = md

      override def execute(input: String, context: AgentContext): IO[AgentError, AgentResult] =
        ZIO.fail(AgentError.ExecutionError(md.name, s"No llm4zio adapter execution is registered for '${md.name}'"))

  def builtInAsLlm4zioAgents: List[Agent] =
    AgentRegistry.builtInAgents.map(stubAgent(_))

object ConversationMemory:
  final case class Settings(
    enabled: Boolean = true,
    maxContextMemories: Int = 5,
    summarizationThreshold: Int = 20,
    retentionDays: Int = 90,
  )

  def fromSettingsMap(settings: Map[String, String]): Settings =
    Settings(
      enabled = settings.get("memory.enabled").forall(parseBoolean(_, default = true)),
      maxContextMemories = settings
        .get("memory.maxContextMemories")
        .flatMap(_.toIntOption)
        .filter(_ > 0)
        .getOrElse(5),
      summarizationThreshold = settings
        .get("memory.summarizationThreshold")
        .flatMap(_.toIntOption)
        .filter(_ > 0)
        .getOrElse(20),
      retentionDays = settings
        .get("memory.retentionDays")
        .flatMap(_.toIntOption)
        .filter(_ > 0)
        .getOrElse(90),
    )

  def memoryFilter(userId: MemoryUserId): MemoryFilter =
    MemoryFilter(userId = Some(userId))

  def userIdFromSession(sessionKey: SessionKey): MemoryUserId =
    val raw = sessionKey.value.trim
    if raw.startsWith("user:") then MemoryUserId(raw.stripPrefix("user:"))
    else MemoryUserId(sessionKey.asString)

  def sessionIdFromSession(sessionKey: SessionKey): MemorySessionId =
    MemorySessionId(sessionKey.asString)

  def memoryContextBlock(memories: List[ScoredMemory]): String =
    if memories.isEmpty then ""
    else
      val body = memories.map(_.entry.text.trim).filter(_.nonEmpty).mkString("\n")
      if body.isEmpty then ""
      else s"\n\n<memory>\n$body\n</memory>"

  private def parseBoolean(raw: String, default: Boolean): Boolean =
    raw.trim.toLowerCase match
      case "true" | "1" | "yes" | "on"  => true
      case "false" | "0" | "no" | "off" => false
      case _                            => default

final case class ChatRepositoryMemoryStore(
  repository: ChatRepository,
  now: UIO[Instant] = Clock.instant,
) extends PersistentMemoryStore:

  override def upsertThread(thread: ConversationThread): IO[llm4zio.agents.MemoryError, Unit] =
    for
      conversationId <- ensureConversation(thread.threadId)
      existing       <- repository.getMessages(conversationId).mapError(toMemoryError)
      existingCount   = existing.length
      tail            = thread.history.drop(existingCount)
      _              <- ZIO.foreachDiscard(tail) { message =>
                          for
                            timestamp <- now
                            _         <- repository.addMessage(
                                           ConversationEntry(
                                             conversationId = conversationId.toString,
                                             sender = senderFor(message.role),
                                             senderType = senderTypeFor(message.role),
                                             content = message.content,
                                             messageType = MessageType.Text,
                                             createdAt = timestamp,
                                             updatedAt = timestamp,
                                           )
                                         ).mapError(toMemoryError)
                          yield ()
                        }
    yield ()

  override def loadThread(threadId: String): IO[llm4zio.agents.MemoryError, Option[ConversationThread]] =
    for
      maybeConversation <- resolveConversation(threadId)
      thread            <- ZIO.foreach(maybeConversation) { conv =>
                             val conversationId = conv.id.getOrElse("0")
                             repository.getMessages(conversationId.toLongOption.getOrElse(0L)).mapError(toMemoryError).map {
                               messages =>
                                 val resolvedThreadId =
                                   if isManagedTitle(conv.title) then managedTitleToThreadId(conv.title)
                                   else normalizedThreadId(conversationId)

                                 ConversationThread(
                                   threadId = resolvedThreadId,
                                   history = messages.map(toLlmMessage).toVector,
                                   metadata = Map("title" -> conv.title, "status" -> conv.status),
                                   updatedAt = conv.updatedAt,
                                 )
                             }
                           }
    yield thread

  override def appendEntry(entry: MemoryEntry): IO[llm4zio.agents.MemoryError, Unit] =
    for
      conversationId <- ensureConversation(entry.threadId)
      _              <- repository
                          .addMessage(
                            ConversationEntry(
                              conversationId = conversationId.toString,
                              sender = senderFor(entry.message.role),
                              senderType = senderTypeFor(entry.message.role),
                              content = entry.message.content,
                              messageType = MessageType.Text,
                              createdAt = entry.recordedAt,
                              updatedAt = entry.recordedAt,
                            )
                          )
                          .mapError(toMemoryError)
                          .unit
    yield ()

  override def searchEntries(query: String, limit: Int): IO[llm4zio.agents.MemoryError, List[MemoryEntry]] =
    if query.trim.isEmpty then ZIO.fail(llm4zio.agents.MemoryError.InvalidInput("Search query must be non-empty"))
    else
      for
        conversations <- repository.listConversations(offset = 0, limit = 500).mapError(toMemoryError)
        normalized     = query.toLowerCase
        entries       <- ZIO.foreach(conversations) { conversation =>
                           val conversationId = conversation.id.getOrElse("0")
                           val threadId       = normalizedThreadId(conversationId)
                           repository.getMessages(conversationId.toLongOption.getOrElse(0L)).mapError(toMemoryError).map {
                             messages =>
                               messages.collect {
                                 case message if message.content.toLowerCase.contains(normalized) =>
                                   MemoryEntry(
                                     threadId = threadId,
                                     message = toLlmMessage(message),
                                     recordedAt = message.createdAt,
                                   )
                               }
                           }
                         }
      yield entries.flatten.sortBy(_.recordedAt).reverse.take(limit)

  private def ensureConversation(threadId: String): IO[llm4zio.agents.MemoryError, Long] =
    parseConversationId(threadId) match
      case Some(id) => ZIO.succeed(id)
      case None     =>
        for
          existing <- findConversationByThreadId(threadId)
          id       <- existing match
                        case Some(conversation) => ZIO.succeed(conversation.id.flatMap(_.toLongOption).getOrElse(0L))
                        case None               =>
                          for
                            timestamp <- now
                            newId     <- repository
                                           .createConversation(
                                             ChatConversation(
                                               title = threadIdToManagedTitle(threadId),
                                               status = "active",
                                               createdAt = timestamp,
                                               updatedAt = timestamp,
                                             )
                                           )
                                           .mapError(toMemoryError)
                          yield newId
        yield id

  private def parseConversationId(threadId: String): Option[Long] =
    val raw = threadId.trim
    if raw.startsWith("conversation:") then raw.stripPrefix("conversation:").toLongOption
    else raw.toLongOption

  private def resolveConversation(threadId: String): IO[llm4zio.agents.MemoryError, Option[ChatConversation]] =
    parseConversationId(threadId) match
      case Some(id) => repository.getConversation(id).mapError(toMemoryError)
      case None     => findConversationByThreadId(threadId)

  private def findConversationByThreadId(threadId: String): IO[llm4zio.agents.MemoryError, Option[ChatConversation]] =
    repository
      .listConversations(offset = 0, limit = 500)
      .mapError(toMemoryError)
      .map(_.find(_.title == threadIdToManagedTitle(threadId)))

  private def threadIdToManagedTitle(threadId: String): String =
    s"llm4zio:$threadId"

  private def isManagedTitle(title: String): Boolean =
    title.startsWith("llm4zio:")

  private def managedTitleToThreadId(title: String): String =
    title.stripPrefix("llm4zio:")

  private def normalizedThreadId(conversationId: String): String =
    s"conversation:$conversationId"

  private def toLlmMessage(message: ConversationEntry): Message =
    Message(
      role = message.senderType match
        case SenderType.System    => MessageRole.System
        case SenderType.User      => MessageRole.User
        case SenderType.Assistant => MessageRole.Assistant,
      content = message.content,
    )

  private def senderFor(role: MessageRole): String =
    role match
      case MessageRole.System    => "system"
      case MessageRole.User      => "user"
      case MessageRole.Assistant => "assistant"
      case MessageRole.Tool      => "tool"

  private def senderTypeFor(role: MessageRole): SenderType =
    role match
      case MessageRole.System    => SenderType.System
      case MessageRole.User      => SenderType.User
      case MessageRole.Assistant => SenderType.Assistant
      case MessageRole.Tool      => SenderType.Assistant

  private def toMemoryError(error: PersistenceError): llm4zio.agents.MemoryError =
    llm4zio.agents.MemoryError.PersistenceFailed(error.toString)
