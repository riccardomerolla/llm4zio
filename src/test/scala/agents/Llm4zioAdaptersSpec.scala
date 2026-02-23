package agents

import java.time.Instant

import zio.*
import zio.test.*

import db.*
import llm4zio.agents.*
import llm4zio.core.{ Message, MessageRole }
import models.*

object Llm4zioAdaptersSpec extends ZIOSpecDefault:

  private object InMemoryChatRepo:
    final case class State(
      nextConversationId: Long,
      nextMessageId: Long,
      conversations: Map[Long, ChatConversation],
      messagesByConversation: Map[Long, List[ConversationEntry]],
    )

    val layer: ULayer[ChatRepository] =
      ZLayer.fromZIO(Ref.make(State(1L, 1L, Map.empty, Map.empty)).map(InMemoryChatRepoLive.apply))

    final case class InMemoryChatRepoLive(ref: Ref[State]) extends ChatRepository:
      override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
        ref.modify { state =>
          val id      = state.nextConversationId
          val updated = state.copy(
            nextConversationId = id + 1,
            conversations = state.conversations.updated(id, conversation.copy(id = Some(id.toString))),
          )
          (id, updated)
        }

      override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
        ref.get.map(_.conversations.get(id))

      override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
        ref.get.map(_.conversations.values.toList.sortBy(_.id.getOrElse("")).slice(offset, offset + limit))

      override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
        ZIO
          .fromOption(message.conversationId.toLongOption)
          .orElseFail(PersistenceError.QueryFailed("addMessage", s"Invalid conversationId: ${message.conversationId}"))
          .flatMap { conversationId =>
            ref.modify { state =>
              val id             = state.nextMessageId
              val updatedMessage = message.copy(id = Some(id.toString))
              val existing       = state.messagesByConversation.getOrElse(conversationId, Nil)
              val updated        = state.copy(
                nextMessageId = id + 1,
                messagesByConversation =
                  state.messagesByConversation.updated(conversationId, existing :+ updatedMessage),
              )
              (id, updated)
            }
          }

      override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
        ref.get.map(_.messagesByConversation.getOrElse(conversationId, Nil))

      override def getMessagesSince(conversationId: Long, since: Instant)
        : IO[PersistenceError, List[ConversationEntry]] =
        getMessages(conversationId).map(_.filter(_.createdAt.isAfter(since)))

      override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]]     =
        ZIO.succeed(Nil)
      override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]                =
        ref.get.map(_.conversations.values.toList.filter(_.runId.contains(runId.toString)))
      override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]                   =
        conversation.id match
          case Some(id) if id.toLongOption.isDefined =>
            ref.update(state => state.copy(conversations = state.conversations.updated(id.toLong, conversation)))
          case Some(_)                               => ZIO.fail(PersistenceError.QueryFailed("updateConversation", "Invalid id"))
          case None                                  => ZIO.fail(PersistenceError.QueryFailed("updateConversation", "Missing id"))
      override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                         =
        ref.update(state =>
          state.copy(
            conversations = state.conversations - id,
            messagesByConversation = state.messagesByConversation - id,
          )
        )
      override def createIssue(issue: AgentIssue): IO[PersistenceError, Long]                                       =
        ZIO.fail(PersistenceError.QueryFailed("createIssue", "unused"))
      override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]]                                     =
        ZIO.fail(PersistenceError.QueryFailed("getIssue", "unused"))
      override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]]                      =
        ZIO.fail(PersistenceError.QueryFailed("listIssues", "unused"))
      override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]]                             =
        ZIO.fail(PersistenceError.QueryFailed("listIssuesByRun", "unused"))
      override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]]                  =
        ZIO.fail(PersistenceError.QueryFailed("listIssuesByStatus", "unused"))
      override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]]                        =
        ZIO.fail(PersistenceError.QueryFailed("listUnassignedIssues", "unused"))
      override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit]                                       =
        ZIO.fail(PersistenceError.QueryFailed("updateIssue", "unused"))
      override def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit]                 =
        ZIO.fail(PersistenceError.QueryFailed("assignIssueToAgent", "unused"))
      override def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long]                        =
        ZIO.fail(PersistenceError.QueryFailed("createAssignment", "unused"))
      override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]]                           =
        ZIO.fail(PersistenceError.QueryFailed("getAssignment", "unused"))
      override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]]               =
        ZIO.fail(PersistenceError.QueryFailed("listAssignmentsByIssue", "unused"))
      override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit]                        =
        ZIO.fail(PersistenceError.QueryFailed("updateAssignment", "unused"))
      override def upsertSessionContext(
        channelName: String,
        sessionKey: String,
        contextJson: String,
        updatedAt: Instant,
      ): IO[PersistenceError, Unit] =
        ZIO.fail(PersistenceError.QueryFailed("upsertSessionContext", "unused"))
      override def getSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Option[String]] =
        ZIO.fail(PersistenceError.QueryFailed("getSessionContext", "unused"))
      override def getSessionContextByConversation(conversationId: Long)
        : IO[PersistenceError, Option[SessionContextLink]] =
        ZIO.fail(PersistenceError.QueryFailed("getSessionContextByConversation", "unused"))
      override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]]  =
        ZIO.fail(PersistenceError.QueryFailed("getSessionContextByTaskRunId", "unused"))
      override def deleteSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Unit]        =
        ZIO.fail(PersistenceError.QueryFailed("deleteSessionContext", "unused"))

  private val fixedNow = Instant.parse("2026-02-13T12:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Llm4zioAdapters")(
    test("maps existing AgentRegistry built-ins to llm4zio metadata") {
      val mapped = Llm4zioAgentAdapters.builtInAsLlm4zioAgents

      assertTrue(
        mapped.length == AgentRegistry.builtInAgents.length,
        mapped.forall(_.metadata.capabilities.nonEmpty),
        mapped.exists(_.metadata.name == "code-agent"),
      )
    },
    test("routes existing agent metadata by capability") {
      val agents = Llm4zioAgentAdapters.builtInAsLlm4zioAgents

      for
        selected <- AgentRouter.route("code", agents)
      yield assertTrue(
        selected.metadata.name == "code-agent"
      )
    },
    test("persists and retrieves conversation memory via ChatRepository") {
      (for
        repository <- ZIO.service[ChatRepository]
        store       = ChatRepositoryMemoryStore(repository, now = ZIO.succeed(fixedNow))
        _          <- store.appendEntry(
                        MemoryEntry(
                          threadId = "integration-thread",
                          message = Message(MessageRole.User, "hello from persistence"),
                          recordedAt = fixedNow,
                        )
                      )
        loaded     <- store.loadThread("integration-thread")
        found      <- store.searchEntries("hello", limit = 10)
      yield assertTrue(
        loaded.exists(_.history.exists(_.content.contains("hello"))),
        found.exists(_.message.content.contains("hello")),
      )).provideLayer(InMemoryChatRepo.layer)
    },
  ) @@ TestAspect.sequential
