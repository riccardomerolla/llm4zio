package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.AIProviderConfig
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import conversation.entity.api.*
import db.*
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import workspace.entity.*

object PlannerAgentServiceSpec extends ZIOSpecDefault:

  final case class InMemoryChatRepo(
    conversations: Ref[Map[Long, ChatConversation]],
    messages: Ref[Map[Long, List[ConversationEntry]]],
    seq: Ref[Long],
  ) extends ChatRepository:
    override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long] =
      seq.updateAndGet(_ + 1).flatMap { id =>
        conversations.update(_ + (id -> conversation.copy(id = Some(id.toString)))) *> ZIO.succeed(id)
      }

    override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] =
      conversations.get.map(_.get(id))

    override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] =
      conversations.get.map(_.values.toList.sortBy(_.id).slice(offset, offset + limit))

    override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] =
      ZIO.succeed(Nil)
    override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]            = ZIO.succeed(Nil)
    override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]               =
      conversation.id.flatMap(_.toLongOption) match
        case Some(id) => conversations.update(_.updated(id, conversation))
        case None     => ZIO.unit
    override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                     = ZIO.unit

    override def addMessage(message: ConversationEntry): IO[PersistenceError, Long] =
      ZIO.succeed(message.conversationId.toLongOption.getOrElse(0L)).flatMap { id =>
        messages.update(current => current.updated(id, current.getOrElse(id, Nil) :+ message)).as(id)
      }

    override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] =
      messages.get.map(_.getOrElse(conversationId, Nil))

    override def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] =
      messages.get.map(_.getOrElse(conversationId, Nil).filterNot(_.createdAt.isBefore(since)))

  object InMemoryChatRepo:
    val layer: ULayer[ChatRepository] =
      ZLayer.fromZIO(
        for
          conversations <- Ref.make(Map.empty[Long, ChatConversation])
          messages      <- Ref.make(Map.empty[Long, List[ConversationEntry]])
          seq           <- Ref.make(0L)
        yield InMemoryChatRepo(conversations, messages, seq)
      )

  final case class RecordingIssueRepo(ref: Ref[Vector[IssueEvent]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[shared.errors.PersistenceError, Unit]                           =
      ref.update(_ :+ event)
    override def get(id: shared.ids.Ids.IssueId): IO[shared.errors.PersistenceError, issues.entity.AgentIssue] =
      ZIO.dieMessage("unused")
    override def history(id: shared.ids.Ids.IssueId): IO[shared.errors.PersistenceError, List[IssueEvent]]     =
      ZIO.succeed(Nil)
    override def list(filter: issues.entity.IssueFilter)
      : IO[shared.errors.PersistenceError, List[issues.entity.AgentIssue]] =
      ZIO.succeed(Nil)
    override def delete(id: shared.ids.Ids.IssueId): IO[shared.errors.PersistenceError, Unit]                  =
      ZIO.unit

  object RecordingIssueRepo:
    val refLayer: ULayer[Ref[Vector[IssueEvent]]]                        = ZLayer.fromZIO(Ref.make(Vector.empty[IssueEvent]))
    val layer: ZLayer[Ref[Vector[IssueEvent]], Nothing, IssueRepository] =
      ZLayer.fromFunction(RecordingIssueRepo.apply)

  private val plannerStructuredResponseJson =
    """{"summary":"Generated plan","issues":[{"draftId":"issue-1","title":"Design data model","description":"Define planner data structures","issueType":"task","priority":"high","estimate":"M","requiredCapabilities":["scala","zio"],"dependencyDraftIds":[],"acceptanceCriteria":"Model compiles","promptTemplate":"Implement the data model","kaizenSkills":["task-planning"],"proofOfWorkRequirements":["tests pass","coverage > 80%"],"included":true},{"draftId":"issue-2","title":"Wire controller","description":"Expose planner routes","issueType":"task","priority":"medium","estimate":"S","requiredCapabilities":[],"dependencyDraftIds":["issue-1"],"acceptanceCriteria":"Routes are reachable","promptTemplate":"Wire the planner controller","kaizenSkills":[],"proofOfWorkRequirements":[],"included":false}]}"""

  private val testWorkspace = Workspace(
    id = "ws-1",
    name = "Planner Workspace",
    localPath = "/tmp/planner-workspace",
    defaultAgent = Some("task-planner"),
    description = Some("planner repo"),
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "gemini",
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

  final case class StubWorkspaceRepository(workspaces: Map[String, Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[shared.errors.PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[shared.errors.PersistenceError, List[Workspace]]                                    =
      ZIO.succeed(workspaces.values.toList)
    override def get(id: String): IO[shared.errors.PersistenceError, Option[Workspace]]                       =
      ZIO.succeed(workspaces.get(id))
    override def delete(id: String): IO[shared.errors.PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[shared.errors.PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[shared.errors.PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  private val testWorkspaceRepository: ULayer[WorkspaceRepository] =
    ZLayer.succeed(StubWorkspaceRepository(Map(testWorkspace.id -> testWorkspace)))

  private val testConfigResolver: ULayer[AgentConfigResolver] =
    ZLayer.succeed(new AgentConfigResolver:
      override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
        ZIO.succeed(AIProviderConfig.withDefaults(AIProviderConfig())))

  private val failingConfigResolver: ULayer[AgentConfigResolver] =
    ZLayer.succeed(new AgentConfigResolver:
      override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
        ZIO.fail(PersistenceError.ConnectionFailed("resolver unavailable")))

  private val testConfigRepository: ULayer[ConfigRepository] =
    ZLayer.succeed(new ConfigRepository:
      override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(
        List(
          SettingRow("ai.provider", "GeminiCli", Instant.EPOCH),
          SettingRow("ai.model", "gemini-2.5-flash", Instant.EPOCH),
        )
      )
      override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
        ZIO.succeed(
          if key == "planner.create.initialStatus" then Some(SettingRow(key, "todo", Instant.EPOCH))
          else None
        )
      override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit
      override def deleteSetting(key: String): IO[PersistenceError, Unit]                           = ZIO.unit
      override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]               = ZIO.unit
      override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
      override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
      override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.dieMessage("unused")
      override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.dieMessage("unused")
      override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.dieMessage("unused")
      override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = ZIO.dieMessage("unused")
      override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.dieMessage("unused")
      override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
      override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
        ZIO.dieMessage("unused")
      override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.dieMessage("unused")
      override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
      override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = ZIO.dieMessage("unused"))

  private val noopActivityHub: ULayer[ActivityHub] =
    ZLayer.succeed(new ActivityHub:
      override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
      override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent])

  private val stubHttpClient: ULayer[HttpClient] = ZLayer.succeed(new HttpClient:
    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
      : IO[LlmError, String] =
      ZIO.fail(LlmError.ProviderError("unused", None)))

  private val cliContextRefLayer: ULayer[Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]] =
    ZLayer.fromZIO(Ref.make(Vector.empty[llm4zio.providers.GeminiCliExecutionContext]))

  private val startupAiConfigLayer: ULayer[AIProviderConfig] =
    ZLayer.succeed(AIProviderConfig.withDefaults(AIProviderConfig()))

  private val stubCliExecutorLayer
    : ZLayer[Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]], Nothing, GeminiCliExecutor] =
    ZLayer.fromFunction { (contextRef: Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]) =>
      new GeminiCliExecutor:
        override def checkGeminiInstalled: IO[LlmError, Unit] = ZIO.unit
        override def runGeminiProcess(
          prompt: String,
          config: LlmConfig,
          executionContext: llm4zio.providers.GeminiCliExecutionContext,
        ): IO[LlmError, String] =
          contextRef.update(_ :+ executionContext) *> ZIO.fail(LlmError.ProviderError("unused", None))
        override def runGeminiProcessStream(
          prompt: String,
          config: LlmConfig,
          executionContext: llm4zio.providers.GeminiCliExecutionContext,
        ): zio.stream.ZStream[Any, LlmError, llm4zio.providers.GeminiCliStreamEvent] =
          zio.stream.ZStream.fromZIO(contextRef.update(_ :+ executionContext)).drain ++
            zio.stream.ZStream.fromIterable(
              List(
                llm4zio.providers.GeminiCliStreamEvent.Message(
                  role = Some("assistant"),
                  content = Some(plannerStructuredResponseJson),
                  delta = true,
                ),
                llm4zio.providers.GeminiCliStreamEvent.Result(
                  status = Some("success"),
                  errorMessage = None,
                  stats = None,
                ),
              )
            )
    }

  private val stubCliExecutor: ULayer[GeminiCliExecutor] = ZLayer.succeed(new GeminiCliExecutor:
    override def checkGeminiInstalled: IO[LlmError, Unit] = ZIO.unit
    override def runGeminiProcess(
      prompt: String,
      config: LlmConfig,
      executionContext: llm4zio.providers.GeminiCliExecutionContext,
    ): IO[LlmError, String] = ZIO.fail(LlmError.ProviderError("unused", None))
    override def runGeminiProcessStream(
      prompt: String,
      config: LlmConfig,
      executionContext: llm4zio.providers.GeminiCliExecutionContext,
    ): zio.stream.ZStream[Any, LlmError, llm4zio.providers.GeminiCliStreamEvent] =
      zio.stream.ZStream.fail(LlmError.ProviderError("unused", None)))

  private val plannerLayer
    : ZLayer[
      Any,
      Nothing,
      PlannerAgentService & ChatRepository & Ref[Vector[IssueEvent]] &
        Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]],
    ] =
    ZLayer.make[
      PlannerAgentService & ChatRepository & Ref[Vector[IssueEvent]] &
        Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]
    ](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      stubCliExecutorLayer,
      startupAiConfigLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithFailingLlm: ZLayer[Any, Nothing, PlannerAgentService & ChatRepository] =
    ZLayer.make[PlannerAgentService & ChatRepository](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      stubCliExecutor,
      startupAiConfigLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithResolverFallback
    : ZLayer[
      Any,
      Nothing,
      PlannerAgentService & ChatRepository & Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]],
    ] =
    ZLayer.make[
      PlannerAgentService & ChatRepository & Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]
    ](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      failingConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      stubCliExecutorLayer,
      startupAiConfigLayer,
      PlannerAgentService.live,
    )

  private def awaitSettledPreview(
    service: PlannerAgentService,
    conversationId: Long,
    attempts: Int = 50,
  ): IO[PlannerAgentError, PlannerPreviewState] =
    service.getPreview(conversationId).flatMap { state =>
      if !state.isGenerating || attempts <= 0 then ZIO.succeed(state)
      else Live.live(ZIO.sleep(10.millis)) *> awaitSettledPreview(service, conversationId, attempts - 1)
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PlannerAgentServiceSpec")(
      test("startSession creates a planner conversation and preview") {
        for
          service <- ZIO.service[PlannerAgentService]
          chat    <- ZIO.service[ChatRepository]
          start   <- service.startSession("Plan a new planner feature", Some("ws-1"))
          state   <- awaitSettledPreview(service, start.conversationId)
          conv    <- chat.getConversation(start.conversationId)
        yield assertTrue(
          start.warning.isEmpty,
          conv.exists(_.title.startsWith("Planner:")),
          conv.flatMap(_.description).contains("planner-session|workspace:ws-1"),
          state.preview.issues.size == 2,
        )
      },
      test("planner Gemini CLI execution includes the selected workspace directory") {
        for
          service    <- ZIO.service[PlannerAgentService]
          contextRef <- ZIO.service[Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]]
          start      <- service.startSession("Plan inside workspace context", Some("ws-1"))
          _          <- awaitSettledPreview(service, start.conversationId)
          contexts   <- contextRef.get
        yield assertTrue(
          contexts.nonEmpty,
          contexts.exists(_.cwd.contains("/tmp/planner-workspace")),
          contexts.exists(_.includeDirectories.contains("/tmp/planner-workspace")),
        )
      }.provideLayer(plannerLayer),
      test("planner global config fallback keeps the selected workspace directory") {
        for
          service    <- ZIO.service[PlannerAgentService]
          contextRef <- ZIO.service[Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]]]
          start      <- service.startSession("Plan using fallback config", Some("ws-1"))
          _          <- awaitSettledPreview(service, start.conversationId)
          contexts   <- contextRef.get
        yield assertTrue(
          contexts.nonEmpty,
          contexts.exists(_.cwd.contains("/tmp/planner-workspace")),
          contexts.exists(_.includeDirectories.contains("/tmp/planner-workspace")),
        )
      }.provideLayer(plannerLayerWithResolverFallback),
      test("confirmPlan emits issue, prompt, acceptance, tag, workspace, and dependency events") {
        for
          service <- ZIO.service[PlannerAgentService]
          ref     <- ZIO.service[Ref[Vector[IssueEvent]]]
          start   <- service.startSession("Plan a new planner feature", Some("ws-1"))
          _       <- awaitSettledPreview(service, start.conversationId)
          result  <- service.confirmPlan(start.conversationId)
          events  <- ref.get
        yield assertTrue(
          result.issueIds.size == 1,
          events.count(_.isInstanceOf[IssueEvent.Created]) == 1,
          !events.exists(_.isInstanceOf[IssueEvent.DependencyLinked]),
          events.exists(_.isInstanceOf[IssueEvent.PromptTemplateUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.AcceptanceCriteriaUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.EstimateUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.KaizenSkillUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.ProofOfWorkRequirementsUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
          events.count(_.isInstanceOf[IssueEvent.WorkspaceLinked]) == 1,
          events.exists {
            case IssueEvent.TagsUpdated(_, tags, _) => tags.contains("skill:task-planning")
            case _                                  => false
          },
        )
      },
      test("updatePreview validates blank titles") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan a new planner feature", None)
          _       <- awaitSettledPreview(service, start.conversationId)
          exit    <-
            service
              .updatePreview(start.conversationId, PlannerPlanPreview("x", List(PlannerIssueDraft("i1", "", "desc"))))
              .exit
        yield assertTrue(exit.isFailure)
      },
      test("updatePreview rejects invalid estimates") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan a new planner feature", None)
          _       <- awaitSettledPreview(service, start.conversationId)
          exit    <- service
                       .updatePreview(
                         start.conversationId,
                         PlannerPlanPreview("x", List(PlannerIssueDraft("i1", "Title", "desc", estimate = Some("XXL")))),
                       )
                       .exit
        yield assertTrue(exit.isFailure)
      },
      test("startSession keeps the conversation available when the first preview generation fails") {
        for
          service  <- ZIO.service[PlannerAgentService]
          chatRepo <- ZIO.service[ChatRepository]
          start    <- service.startSession("Plan a risky planner feature", Some("ws-9"))
          state    <- awaitSettledPreview(service, start.conversationId)
          messages <- chatRepo.getMessages(start.conversationId)
        yield assertTrue(
          start.warning.isEmpty,
          state.preview.issues.isEmpty,
          state.workspaceId.contains("ws-9"),
          state.lastError.exists(_.contains("Planner agent failed")),
          messages.exists(_.content.contains("Planner preview generation failed")),
        )
      }.provideLayer(plannerLayerWithFailingLlm),
    ).provideLayerShared(plannerLayer)
