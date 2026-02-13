package agents

import java.time.Instant

import zio.*

import db.{ ChatRepository, PersistenceError }
import llm4zio.agents.*
import llm4zio.core.{ Message, MessageRole }
import models.{ AgentInfo, ChatConversation, ConversationMessage, MessageType, SenderType }

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
                                           ConversationMessage(
                                             conversationId = conversationId,
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
                             val conversationId = conv.id.getOrElse(0L)
                             repository.getMessages(conversationId).mapError(toMemoryError).map { messages =>
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
                            ConversationMessage(
                              conversationId = conversationId,
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
                           val threadId = normalizedThreadId(conversation.id.getOrElse(0L))
                           repository.getMessages(conversation.id.getOrElse(0L)).mapError(toMemoryError).map { messages =>
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
                        case Some(conversation) => ZIO.succeed(conversation.id.getOrElse(0L))
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

  private def normalizedThreadId(conversationId: Long): String =
    s"conversation:$conversationId"

  private def toLlmMessage(message: ConversationMessage): Message =
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
