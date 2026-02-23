package gateway

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.test.*

import _root_.models.*
import db.*
import gateway.models.*
import orchestration.OrchestratorControlPlane

object MessageRouterSpec extends ZIOSpecDefault:

  private def appLayer
    : ZLayer[Any, Any, MessageRouter & ChannelRegistry & ChatRepository & OrchestratorControlPlane] =
    ZLayer.make[MessageRouter & ChannelRegistry & ChatRepository & OrchestratorControlPlane](
      InMemoryChatRepo.layer,
      ChannelRegistry.empty,
      MessageRouter.live,
      ZLayer.succeed(MigrationConfig(Paths.get("/tmp/src"), Paths.get("/tmp/out"))),
      OrchestratorControlPlane.live,
    )

  private object InMemoryChatRepo:
    final case class State(sessionContexts: Map[(String, String), SessionContextLink])

    val layer: ULayer[ChatRepository] =
      ZLayer.fromZIO(Ref.make(State(Map.empty)).map(InMemoryChatRepoLive.apply))

    final case class InMemoryChatRepoLive(ref: Ref[State]) extends ChatRepository:
      override def upsertSessionContext(
        channelName: String,
        sessionKey: String,
        contextJson: String,
        updatedAt: Instant,
      ): IO[PersistenceError, Unit] =
        ref.update(state =>
          state.copy(
            sessionContexts = state.sessionContexts.updated(
              (channelName, sessionKey),
              SessionContextLink(channelName, sessionKey, contextJson, updatedAt),
            )
          )
        )

      override def getSessionContext(
        channelName: String,
        sessionKey: String,
      ): IO[PersistenceError, Option[String]] =
        ref.get.map(_.sessionContexts.get((channelName, sessionKey)).map(_.contextJson))

      override def getSessionContextByConversation(conversationId: Long)
        : IO[PersistenceError, Option[SessionContextLink]] =
        ref.get.map(_.sessionContexts.values.find(_.contextJson.contains(s""""conversationId":$conversationId""")))

      override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]] =
        ref.get.map(_.sessionContexts.values.find(_.contextJson.contains(s""""runId":$taskRunId""")))

      override def deleteSessionContext(
        channelName: String,
        sessionKey: String,
      ): IO[PersistenceError, Unit] =
        ref.update(state => state.copy(sessionContexts = state.sessionContexts - ((channelName, sessionKey))))

      override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]               =
        ZIO.fail(PersistenceError.QueryFailed("createConversation", "unused"))
      override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                    =
        ZIO.fail(PersistenceError.QueryFailed("getConversation", "unused"))
      override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]     =
        ZIO.fail(PersistenceError.QueryFailed("listConversations", "unused"))
      override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
        ZIO.fail(PersistenceError.QueryFailed("getConversationsByChannel", "unused"))
      override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]            =
        ZIO.fail(PersistenceError.QueryFailed("listConversationsByRun", "unused"))
      override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]               =
        ZIO.fail(PersistenceError.QueryFailed("updateConversation", "unused"))
      override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                     =
        ZIO.fail(PersistenceError.QueryFailed("deleteConversation", "unused"))
      override def addMessage(message: ConversationEntry): IO[PersistenceError, Long]                           =
        ZIO.fail(PersistenceError.QueryFailed("addMessage", "unused"))
      override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]]             =
        ZIO.fail(PersistenceError.QueryFailed("getMessages", "unused"))
      override def getMessagesSince(conversationId: Long, since: Instant)
        : IO[PersistenceError, List[ConversationEntry]] =
        ZIO.fail(PersistenceError.QueryFailed("getMessagesSince", "unused"))
      override def createIssue(issue: AgentIssue): IO[PersistenceError, Long]                                   =
        ZIO.fail(PersistenceError.QueryFailed("createIssue", "unused"))
      override def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]]                                 =
        ZIO.fail(PersistenceError.QueryFailed("getIssue", "unused"))
      override def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]]                  =
        ZIO.fail(PersistenceError.QueryFailed("listIssues", "unused"))
      override def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]]                         =
        ZIO.fail(PersistenceError.QueryFailed("listIssuesByRun", "unused"))
      override def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]]              =
        ZIO.fail(PersistenceError.QueryFailed("listIssuesByStatus", "unused"))
      override def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]]                    =
        ZIO.fail(PersistenceError.QueryFailed("listUnassignedIssues", "unused"))
      override def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit]                                   =
        ZIO.fail(PersistenceError.QueryFailed("updateIssue", "unused"))
      override def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit]             =
        ZIO.fail(PersistenceError.QueryFailed("assignIssueToAgent", "unused"))
      override def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long]                    =
        ZIO.fail(PersistenceError.QueryFailed("createAssignment", "unused"))
      override def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]]                       =
        ZIO.fail(PersistenceError.QueryFailed("getAssignment", "unused"))
      override def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]]           =
        ZIO.fail(PersistenceError.QueryFailed("listAssignmentsByIssue", "unused"))
      override def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit]                    =
        ZIO.fail(PersistenceError.QueryFailed("updateAssignment", "unused"))

  private def makeMessage(
    id: String,
    session: SessionKey,
    direction: MessageDirection,
    role: GatewayMessageRole,
    content: String,
    metadata: Map[String, String] = Map.empty,
  ): NormalizedMessage =
    NormalizedMessage(
      id = id,
      channelName = "websocket",
      sessionKey = session,
      direction = direction,
      role = role,
      content = content,
      metadata = metadata,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MessageRouterSpec")(
    test("routeInbound stores session context with conversation and run metadata") {
      (for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-42")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        _        <- MessageRouter.routeInbound(
                      makeMessage(
                        id = "in-1",
                        session = session,
                        direction = MessageDirection.Inbound,
                        role = GatewayMessageRole.User,
                        content = "hello",
                        metadata = Map("conversationId" -> "42", "runId" -> "99"),
                      )
                    )
        context  <- MessageRouter.sessionContext(session)
      yield assertTrue(
        context.flatMap(_.conversationId).contains(42L),
        context.flatMap(_.runId).contains(99L),
        context.flatMap(_.lastInboundMessageId).contains("in-1"),
      )).provideSomeLayer[Scope](appLayer)
    },
    test("routeOutbound publishes to channel and updates session context") {
      (for
        registry <- ZIO.service[ChannelRegistry]
        channel  <- WebSocketChannel.make()
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-7")
        _        <- channel.open(session)
        _        <- registry.register(channel)
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- MessageRouter.routeOutbound(
                      makeMessage(
                        id = "out-1",
                        session = session,
                        direction = MessageDirection.Outbound,
                        role = GatewayMessageRole.Assistant,
                        content = "response",
                      )
                    )
        out      <- fiber.join
        context  <- MessageRouter.sessionContext(session)
      yield assertTrue(
        out.length == 1,
        out.head.content == "response",
        context.flatMap(_.lastOutboundMessageId).contains("out-1"),
      )).provideSomeLayer[Scope](appLayer)
    },
    test("resolveSession validates non-empty session id") {
      (for
        ok  <- MessageRouter.resolveSession("websocket", "55", SessionScopeStrategy.PerRun)
        bad <- MessageRouter.resolveSession("websocket", "   ", SessionScopeStrategy.PerRun).either
      yield assertTrue(
        ok.value == "run:55",
        bad == Left(MessageRouterError.InvalidSession("Session id cannot be empty")),
      )).provideSomeLayer[Scope](appLayer)
    },
    test("control plane middleware forwards events as outbound normalized messages") {
      (for
        registry <- ZIO.service[ChannelRegistry]
        _        <- ZIO.service[OrchestratorControlPlane]
        channel  <- WebSocketChannel.make()
        _        <- registry.register(channel)
        _        <- MessageRouter.attachControlPlaneRouting("55", "websocket")
        session   = SessionScopeStrategy.PerRun.build("websocket", "55")
        stream   <- channel.outbound(session).take(1).runCollect.fork
        _        <- OrchestratorControlPlane.publishEvent(
                      WorkflowFailed(
                        correlationId = "corr-1",
                        runId = "55",
                        error = "boom",
                        timestamp = Instant.EPOCH,
                      )
                    )
        messages <- stream.join
      yield assertTrue(
        messages.length == 1,
        messages.head.metadata.get("runId").contains("55"),
        messages.head.content.contains("\"error\":\"boom\""),
      )).provideSomeLayer[Scope](appLayer)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
