package gateway

import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import _root_.models.*
import agents.AgentRegistry
import db.*
import gateway.models.*
import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }
import memory.*

object GatewayServiceSpec extends ZIOSpecDefault:

  private def baseLayer: ZLayer[Any, Any, GatewayService & ChannelRegistry & MessageRouter] =
    ZLayer.make[GatewayService & ChannelRegistry & MessageRouter](
      InMemoryChatRepo.layer,
      InMemoryConfigRepo.layer,
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          web      <- WebSocketChannel.make("websocket")
          telegram <- WebSocketChannel.make("telegram")
          _        <- registry.register(web)
          _        <- registry.register(telegram)
        yield ()
      },
      AgentRegistry.live,
      TestLlm.layer,
      EmptyMemoryRepo.layer,
      MessageRouter.live,
      GatewayService.live,
    )

  private def steeringLayer
    : ZLayer[Any, Any, GatewayService & ChannelRegistry & Queue[NormalizedMessage]] =
    ZLayer.make[GatewayService & ChannelRegistry & Queue[NormalizedMessage]](
      InMemoryChatRepo.layer,
      InMemoryConfigRepo.layer,
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          web      <- WebSocketChannel.make("websocket")
          _        <- registry.register(web)
        yield ()
      },
      AgentRegistry.live,
      TestLlm.layer,
      EmptyMemoryRepo.layer,
      MessageRouter.live,
      ZLayer.fromZIO(Queue.unbounded[NormalizedMessage]),
      GatewayService.liveWithSteeringQueue,
    )

  private object TestLlm:
    val layer: ULayer[LlmService] = ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse("""{"agent":"code-agent","confidence":0.93}"""))

        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse("history"))

        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.succeed(ToolCallResponse(None, Nil, "stop"))

        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("unused"))

        override def isAvailable: UIO[Boolean] =
          ZIO.succeed(true)
    )

  private object EmptyMemoryRepo:
    val layer: ULayer[MemoryRepository] = ZLayer.succeed(
      new MemoryRepository:
        override def save(entry: MemoryEntry): IO[Throwable, Unit] = ZIO.unit

        override def searchRelevant(
          userId: UserId,
          query: String,
          limit: Int,
          filter: MemoryFilter,
        ): IO[Throwable, List[ScoredMemory]] = ZIO.succeed(Nil)

        override def listForUser(
          userId: UserId,
          filter: MemoryFilter,
          page: Int,
          pageSize: Int,
        ): IO[Throwable, List[MemoryEntry]] = ZIO.succeed(Nil)

        override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] = ZIO.unit

        override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit] = ZIO.unit
    )

  private def memoryInjectionLayer(
    promptRef: Ref[List[String]]
  ): ZLayer[Any, Any, GatewayService & ChannelRegistry] =
    val llmLayer = ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          if prompt.contains("You are a request router.") then
            ZIO.succeed(LlmResponse("""{"agent":"code-agent","confidence":0.93}"""))
          else promptRef.update(_ :+ prompt).as(LlmResponse("ok"))

        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse("history"))

        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.succeed(ToolCallResponse(None, Nil, "stop"))

        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("unused"))

        override def isAvailable: UIO[Boolean] =
          ZIO.succeed(true)
    )

    val seededMemoryLayer = ZLayer.succeed(
      new MemoryRepository:
        private val seed =
          MemoryEntry(
            id = MemoryId("seed-1"),
            userId = UserId("telegram:conversation:chat-memory-1"),
            sessionId = SessionId("telegram:conversation:chat-memory-1"),
            text = "User prefers concise answers",
            embedding = Vector(0.1f),
            tags = Nil,
            kind = MemoryKind.Preference,
            createdAt = Instant.EPOCH,
            lastAccessedAt = Instant.EPOCH,
          )

        override def save(entry: MemoryEntry): IO[Throwable, Unit] = ZIO.unit

        override def searchRelevant(
          userId: UserId,
          query: String,
          limit: Int,
          filter: MemoryFilter,
        ): IO[Throwable, List[ScoredMemory]] =
          if userId == seed.userId then ZIO.succeed(List(ScoredMemory(seed, 0.99f)).take(limit))
          else ZIO.succeed(Nil)

        override def listForUser(
          userId: UserId,
          filter: MemoryFilter,
          page: Int,
          pageSize: Int,
        ): IO[Throwable, List[MemoryEntry]] = ZIO.succeed(Nil)

        override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] = ZIO.unit

        override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit] = ZIO.unit
    )

    ZLayer.make[GatewayService & ChannelRegistry](
      InMemoryChatRepo.layer,
      InMemoryConfigRepo.layer,
      ZLayer.fromZIO {
        for
          repo <- ZIO.service[ConfigRepository]
          _    <- repo.upsertSetting("memory.enabled", "true")
          _    <- repo.upsertSetting("memory.maxContextMemories", "5")
          _    <- repo.upsertSetting("memory.summarizationThreshold", "1000")
        yield ()
      },
      ChannelRegistry.empty,
      ZLayer.fromZIO {
        for
          registry <- ZIO.service[ChannelRegistry]
          telegram <- WebSocketChannel.make("telegram")
          _        <- registry.register(telegram)
        yield ()
      },
      AgentRegistry.live,
      llmLayer,
      seededMemoryLayer,
      MessageRouter.live,
      GatewayService.live,
    )

  private object InMemoryConfigRepo:
    val layer: ULayer[ConfigRepository] =
      ZLayer.fromZIO(Ref.make(Map.empty[String, String]).map(InMemoryConfigRepoLive.apply))

    final case class InMemoryConfigRepoLive(ref: Ref[Map[String, String]]) extends ConfigRepository:
      override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
        for
          now   <- Clock.instant
          state <- ref.get
        yield state.toList.sortBy(_._1).map { case (k, v) => SettingRow(k, v, now) }

      override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
        for
          now   <- Clock.instant
          state <- ref.get
        yield state.get(key).map(v => SettingRow(key, v, now))

      override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
        ref.update(_.updated(key, value))

      override def deleteSetting(key: String): IO[PersistenceError, Unit] =
        ref.update(_ - key)

      override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
        ref.update(_.filterNot(_._1.startsWith(prefix)))

      override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                =
        ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
      override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 =
        ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
      override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       =
        ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
      override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           =
        ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
      override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                =
        ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
      override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             =
        ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))
      override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
        ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
      override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
        ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
      override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
        ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
      override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
        ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
      override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
        ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
      override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
        ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  private object InMemoryChatRepo:
    final case class State(
      sessionContexts: Map[(String, String), SessionContextLink],
      messagesByConversation: Map[Long, List[ConversationEntry]],
    )

    val layer: ULayer[ChatRepository] =
      ZLayer.fromZIO(
        Ref.make(State(Map.empty, Map.empty)).map(InMemoryChatRepoLive.apply)
      )

    final case class InMemoryChatRepoLive(ref: Ref[State]) extends ChatRepository:
      override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
        ref.get.map(_.messagesByConversation.getOrElse(conversationId, Nil))

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

  private def message(
    id: String,
    channelName: String,
    session: SessionKey,
    content: String,
    direction: MessageDirection = MessageDirection.Outbound,
    role: GatewayMessageRole = GatewayMessageRole.Assistant,
  ): NormalizedMessage =
    NormalizedMessage(
      id = id,
      channelName = channelName,
      sessionKey = session,
      direction = direction,
      role = role,
      content = content,
      timestamp = Instant.EPOCH,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GatewayServiceSpec")(
    test("processOutbound chunks and routes channel-limited responses") {
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-1")
        _        <- channel.open(session)
        outbound  = message("m-1", "telegram", session, "a" * 4100)
        chunks   <- gateway.processOutbound(outbound)
        seen     <- channel.outbound(session).take(2).runCollect
      yield assertTrue(
        chunks.length == 2,
        seen.length == 2,
        chunks.forall(_.metadata.get("chunked").contains("true")),
      )).provideSomeLayer[Scope](baseLayer)
    },
    test("enqueueOutbound processes asynchronously and updates metrics") {
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("websocket")
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-2")
        _        <- channel.open(session)
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- gateway.enqueueOutbound(message("m-2", "websocket", session, "hello async"))
        out      <- fiber.join
        _        <- gateway.metrics.map(_.processed).repeat(Schedule.recurWhile(_ < 1L))
        metrics  <- gateway.metrics
      yield assertTrue(
        out.length == 1,
        metrics.enqueued >= 1L,
        metrics.processed >= 1L,
      )).provideSomeLayer[Scope](baseLayer)
    },
    test("steering mode forwards messages to injected queue") {
      (for
        gateway  <- ZIO.service[GatewayService]
        steering <- ZIO.service[Queue[NormalizedMessage]]
        session   = SessionScopeStrategy.PerConversation.build("websocket", "chat-3")
        inbound   = message(
                      id = "in-1",
                      channelName = "websocket",
                      session = session,
                      content = "steer this",
                      direction = MessageDirection.Inbound,
                    )
        _        <- gateway.processInbound(inbound)
        steered  <- steering.take
        metrics  <- gateway.metrics
      yield assertTrue(
        steered.id == "in-1",
        metrics.steeringForwarded >= 1L,
      )).provideSomeLayer[Scope](steeringLayer)
    },
    test("telegram inbound natural language routes to an agent response") {
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-intent-1")
        _        <- channel.open(session)
        inbound   = message(
                      id = "in-nl-1",
                      channelName = "telegram",
                      session = session,
                      content = "please analyze this cobol program",
                      direction = MessageDirection.Inbound,
                      role = GatewayMessageRole.User,
                    )
        fiber    <- channel.outbound(session).take(1).runCollect.fork
        _        <- gateway.processInbound(inbound)
        out      <- fiber.join
      yield assertTrue(
        out.nonEmpty,
        out.head.metadata.get("intent.agent").contains("code-agent"),
      )).provideSomeLayer[Scope](baseLayer)
    },
    test("telegram multi-turn clarification routes based on follow-up answer") {
      (for
        gateway  <- ZIO.service[GatewayService]
        registry <- ZIO.service[ChannelRegistry]
        channel  <- registry.get("telegram")
        session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-intent-2")
        _        <- channel.open(session)
        first     = message(
                      id = "in-nl-2",
                      channelName = "telegram",
                      session = session,
                      content = "hello, I need some help",
                      direction = MessageDirection.Inbound,
                      role = GatewayMessageRole.User,
                    )
        second    = message(
                      id = "in-nl-3",
                      channelName = "telegram",
                      session = session,
                      content = "2",
                      direction = MessageDirection.Inbound,
                      role = GatewayMessageRole.User,
                    )
        fiber    <- channel.outbound(session).take(2).runCollect.fork
        _        <- gateway.processInbound(first)
        _        <- gateway.processInbound(second)
        out      <- fiber.join
      yield assertTrue(
        out.length == 2,
        out.head.content.contains("Routing request to"),
        out(1).metadata.get("intent.agent").contains("code-agent"),
      )).provideSomeLayer[Scope](baseLayer)
    },
    test("injects memory context into routed agent execution prompt") {
      for
        prompts <- Ref.make(List.empty[String])
        result  <- (for
                     gateway  <- ZIO.service[GatewayService]
                     registry <- ZIO.service[ChannelRegistry]
                     channel  <- registry.get("telegram")
                     session   = SessionScopeStrategy.PerConversation.build("telegram", "chat-memory-1")
                     _        <- channel.open(session)
                     inbound   = message(
                                   id = "in-memory-1",
                                   channelName = "telegram",
                                   session = session,
                                   content = "please help with this migration",
                                   direction = MessageDirection.Inbound,
                                   role = GatewayMessageRole.User,
                                 )
                     fiber    <- channel.outbound(session).take(2).runCollect.fork
                     _        <- gateway.processInbound(inbound)
                     _        <- fiber.join
                     seen     <- prompts.get
                   yield assertTrue(
                     seen.nonEmpty,
                     seen.exists(_.contains("<memory>")),
                     seen.exists(_.contains("User prefers concise answers")),
                   )).provideSomeLayer[Scope](memoryInjectionLayer(prompts))
      yield result
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(20.seconds)
