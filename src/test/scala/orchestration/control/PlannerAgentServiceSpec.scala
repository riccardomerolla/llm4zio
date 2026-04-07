package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import _root_.config.entity.{ AIProviderConfig, ConfigRepository }
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import board.entity.*
import _root_.config.entity.{ CustomAgentRow, SettingRow, WorkflowRow }
import conversation.entity.api.*
import db.*
import governance.control.{ GovernanceEvaluationContext, GovernancePolicyService, GovernanceTransitionDecision }
import governance.entity.{ GovernanceGate, GovernancePolicy }
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import plan.entity.*
import project.control.ProjectStorageService
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, IssueId, PlanId, SpecificationId }
import specification.entity.*
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

  final case class RecordingBoardRepo(ref: Ref[Map[String, Map[BoardIssueId, BoardIssue]]]) extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit] =
      ref.update(current => current.updatedWith(workspacePath)(_.orElse(Some(Map.empty)))).unit

    override def readBoard(workspacePath: String): IO[BoardError, Board] =
      ref.get.flatMap { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        ZIO.succeed(
          Board(
            workspacePath,
            BoardColumn.values.map(column => column -> byId.values.filter(_.column == column).toList).toMap,
          )
        )
      }

    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      ref.get.flatMap { state =>
        ZIO
          .fromOption(state.getOrElse(workspacePath, Map.empty).get(issueId))
          .orElseFail(BoardError.IssueNotFound(issueId.value))
      }

    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        byId.get(issue.frontmatter.id) match
          case Some(_) =>
            (Left(BoardError.IssueAlreadyExists(issue.frontmatter.id.value)), state)
          case None    =>
            val created = issue.copy(column = column)
            (Right(created), state.updated(workspacePath, byId.updated(issue.frontmatter.id, created)))
      }.absolve

    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        byId.get(issueId) match
          case None        => (Left(BoardError.IssueNotFound(issueId.value)), state)
          case Some(issue) =>
            val moved = issue.copy(column = toColumn)
            (Right(moved), state.updated(workspacePath, byId.updated(issueId, moved)))
      }.absolve

    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        byId.get(issueId) match
          case None        => (Left(BoardError.IssueNotFound(issueId.value)), state)
          case Some(issue) =>
            val updated = issue.copy(frontmatter = update(issue.frontmatter))
            (Right(updated), state.updated(workspacePath, byId.updated(issueId, updated)))
      }.absolve

    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      ref.update(state => state.updated(workspacePath, state.getOrElse(workspacePath, Map.empty) - issueId)).unit

    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      ref.get.map(_.getOrElse(workspacePath, Map.empty).values.filter(_.column == column).toList)

    override def invalidateWorkspace(workspacePath: String): UIO[Unit] = ZIO.unit

  object RecordingBoardRepo:
    val refLayer: ULayer[Ref[Map[String, Map[BoardIssueId, BoardIssue]]]]                        =
      ZLayer.fromZIO(Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]]))
    val layer: ZLayer[Ref[Map[String, Map[BoardIssueId, BoardIssue]]], Nothing, BoardRepository] =
      ZLayer.fromFunction(RecordingBoardRepo.apply)

  final case class InMemorySpecificationRepo(
    histories: Ref[Map[SpecificationId, List[SpecificationEvent]]]
  ) extends SpecificationRepository:
    override def append(event: SpecificationEvent): IO[shared.errors.PersistenceError, Unit] =
      histories.update { current =>
        current.updated(event.specificationId, current.getOrElse(event.specificationId, Nil) :+ event)
      }

    override def get(id: SpecificationId): IO[shared.errors.PersistenceError, Specification] =
      histories.get.flatMap { current =>
        current.get(id) match
          case Some(events) =>
            ZIO
              .fromEither(Specification.fromEvents(events))
              .mapError(err => shared.errors.PersistenceError.SerializationFailed(s"specification:${id.value}", err))
          case None         =>
            ZIO.fail(shared.errors.PersistenceError.NotFound("specification", id.value))
      }

    override def history(id: SpecificationId): IO[shared.errors.PersistenceError, List[SpecificationEvent]] =
      histories.get.map(_.getOrElse(id, Nil))

    override def list: IO[shared.errors.PersistenceError, List[Specification]] =
      histories.get.flatMap(current => ZIO.foreach(current.keys.toList)(get))

    override def diff(
      id: SpecificationId,
      fromVersion: Int,
      toVersion: Int,
    ): IO[shared.errors.PersistenceError, SpecificationDiff] =
      get(id).flatMap(spec =>
        ZIO
          .fromEither(Specification.diff(spec, fromVersion, toVersion))
          .mapError(err => shared.errors.PersistenceError.QueryFailed("specification_diff", err))
      )

  object InMemorySpecificationRepo:
    val refLayer: ULayer[Ref[Map[SpecificationId, List[SpecificationEvent]]]]                                =
      ZLayer.fromZIO(Ref.make(Map.empty[SpecificationId, List[SpecificationEvent]]))
    val layer: ZLayer[Ref[Map[SpecificationId, List[SpecificationEvent]]], Nothing, SpecificationRepository] =
      ZLayer.fromFunction(InMemorySpecificationRepo.apply)

  final case class InMemoryPlanRepo(histories: Ref[Map[PlanId, List[PlanEvent]]]) extends PlanRepository:
    override def append(event: PlanEvent): IO[shared.errors.PersistenceError, Unit] =
      histories.update(current => current.updated(event.planId, current.getOrElse(event.planId, Nil) :+ event))

    override def get(id: PlanId): IO[shared.errors.PersistenceError, Plan] =
      histories.get.flatMap { current =>
        current.get(id) match
          case Some(events) =>
            ZIO
              .fromEither(Plan.fromEvents(events))
              .mapError(err => shared.errors.PersistenceError.SerializationFailed(s"plan:${id.value}", err))
          case None         =>
            ZIO.fail(shared.errors.PersistenceError.NotFound("plan", id.value))
      }

    override def history(id: PlanId): IO[shared.errors.PersistenceError, List[PlanEvent]] =
      histories.get.map(_.getOrElse(id, Nil))

    override def list: IO[shared.errors.PersistenceError, List[Plan]] =
      histories.get.flatMap(current => ZIO.foreach(current.keys.toList)(get))

  object InMemoryPlanRepo:
    val refLayer: ULayer[Ref[Map[PlanId, List[PlanEvent]]]]                       =
      ZLayer.fromZIO(Ref.make(Map.empty[PlanId, List[PlanEvent]]))
    val layer: ZLayer[Ref[Map[PlanId, List[PlanEvent]]], Nothing, PlanRepository] =
      ZLayer.fromFunction(InMemoryPlanRepo.apply)

  private val noOpGovernancePolicyService: ULayer[GovernancePolicyService] =
    ZLayer.succeed(new GovernancePolicyService:
      override def resolvePolicyForWorkspace(workspaceId: String)
        : IO[shared.errors.PersistenceError, GovernancePolicy] =
        ZIO.succeed(GovernancePolicy.noOp)

      override def evaluateForWorkspace(
        workspaceId: String,
        context: GovernanceEvaluationContext,
      ): IO[shared.errors.PersistenceError, GovernanceTransitionDecision] =
        ZIO.succeed(
          GovernanceTransitionDecision(
            allowed = true,
            requiredGates = Set.empty,
            missingGates = Set.empty,
            humanApprovalRequired = false,
            daemonTriggers = Nil,
            escalationRules = Nil,
            completionCriteria = None,
            reason = None,
          )
        ))

  private val blockingGovernancePolicyService: ULayer[GovernancePolicyService] =
    ZLayer.succeed(new GovernancePolicyService:
      override def resolvePolicyForWorkspace(workspaceId: String)
        : IO[shared.errors.PersistenceError, GovernancePolicy] =
        ZIO.succeed(GovernancePolicy.noOp)

      override def evaluateForWorkspace(
        workspaceId: String,
        context: GovernanceEvaluationContext,
      ): IO[shared.errors.PersistenceError, GovernanceTransitionDecision] =
        ZIO.succeed(
          GovernanceTransitionDecision(
            allowed = false,
            requiredGates = Set(GovernanceGate.PlanningReview),
            missingGates = Set(GovernanceGate.PlanningReview),
            humanApprovalRequired = false,
            daemonTriggers = Nil,
            escalationRules = Nil,
            completionCriteria = None,
            reason = Some("Missing required gates: PlanningReview"),
          )
        ))

  private val plannerStructuredResponseJson =
    """{"summary":"Generated plan","issues":[{"draftId":"issue-1","title":"Design data model","description":"Define planner data structures","issueType":"task","priority":"high","estimate":"M","requiredCapabilities":["scala","zio"],"dependencyDraftIds":[],"acceptanceCriteria":"Model compiles","promptTemplate":"Implement the data model","kaizenSkills":["task-planning"],"proofOfWorkRequirements":["tests pass","coverage > 80%"],"included":true},{"draftId":"issue-2","title":"Wire controller","description":"Expose planner routes","issueType":"task","priority":"medium","estimate":"S","requiredCapabilities":[],"dependencyDraftIds":["issue-1"],"acceptanceCriteria":"Routes are reachable","promptTemplate":"Wire the planner controller","kaizenSkills":[],"proofOfWorkRequirements":[],"included":false}]}"""

  private val plannerGeminiStyleResponseJson =
    """```json
      |{
      |  "issues": [
      |    {
      |      "issue_id": "issue-1",
      |      "description": "Analyze the existing Rust code (`src/main.rs`) to identify testable components.",
      |      "acceptance_criteria": "A report listing identified testable components from `src/main.rs` is generated.",
      |      "required_capabilities": ["Rust analysis", "Codebase inspection"],
      |      "prompt_template": "Use `read_file` to inspect `src/main.rs` and list components that would benefit from unit testing.",
      |      "estimate": "XS",
      |      "priority": "high",
      |      "kaizen_references": [],
      |      "proof_of_work": "Provide the content of `src/main.rs` and the list of identified testable components as plain text."
      |    }
      |  ]
      |}
      |```""".stripMargin

  private val plannerPlainTextResponse =
    """```text
      |The `greet` function, implemented as `get_greeting` in `src/main.rs`, already supports multi-language greetings for English ('en'), Italian ('it'), and German ('de'), with English as the default. The existing tests also cover these functionalities. No changes are needed.
      |```""".stripMargin

  private val testWorkspace = Workspace(
    id = "ws-1",
    projectId = shared.ids.Ids.ProjectId("test-project"),
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
    override def listByProject(projectId: shared.ids.Ids.ProjectId)
      : IO[shared.errors.PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaces.values.filter(_.projectId == projectId).toList)
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
        ZIO.fail(PersistenceError.StoreUnavailable("resolver unavailable")))

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

  private val geminiStyleCliExecutorLayer
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
                  content = Some(plannerGeminiStyleResponseJson),
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

  private val plainTextCliExecutorLayer
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
                  content = Some(plannerPlainTextResponse),
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

  private object StubProjectStorageService extends ProjectStorageService:
    override def initProjectStorage(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def projectRoot(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                         =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}"))
    override def boardPath(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]                           =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/.board"))
    override def workspaceAnalysisPath(
      projectId: shared.ids.Ids.ProjectId,
      workspaceId: String,
    ): UIO[java.nio.file.Path] =
      ZIO.succeed(
        java.nio.file.Paths.get(s"/tmp/projects/${projectId.value}/workspaces/$workspaceId/.llm4zio/analysis")
      )

  private val stubProjectStorageServiceLayer: ULayer[ProjectStorageService] =
    ZLayer.succeed(StubProjectStorageService)

  private val plannerLayer
    : ZLayer[
      Any,
      Nothing,
      PlannerAgentService & ChatRepository & BoardRepository & SpecificationRepository & Ref[Vector[IssueEvent]] &
        Ref[Map[String, Map[BoardIssueId, BoardIssue]]] & Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]] &
        Ref[Map[SpecificationId, List[SpecificationEvent]]] & PlanRepository & Ref[Map[PlanId, List[PlanEvent]]],
    ] =
    ZLayer.make[
      PlannerAgentService & ChatRepository & BoardRepository & SpecificationRepository & Ref[Vector[IssueEvent]] &
        Ref[Map[String, Map[BoardIssueId, BoardIssue]]] & Ref[Vector[llm4zio.providers.GeminiCliExecutionContext]] &
        Ref[Map[SpecificationId, List[SpecificationEvent]]] & PlanRepository & Ref[Map[PlanId, List[PlanEvent]]]
    ](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      stubCliExecutorLayer,
      startupAiConfigLayer,
      PromptLoader.reloading,
      noOpGovernancePolicyService,
      stubProjectStorageServiceLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithFailingLlm: ZLayer[Any, Nothing, PlannerAgentService & ChatRepository] =
    ZLayer.make[PlannerAgentService & ChatRepository](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      stubCliExecutor,
      startupAiConfigLayer,
      PromptLoader.reloading,
      noOpGovernancePolicyService,
      stubProjectStorageServiceLayer,
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
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      failingConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      stubCliExecutorLayer,
      startupAiConfigLayer,
      PromptLoader.reloading,
      noOpGovernancePolicyService,
      stubProjectStorageServiceLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithGeminiStyleResponse
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
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      geminiStyleCliExecutorLayer,
      startupAiConfigLayer,
      PromptLoader.reloading,
      noOpGovernancePolicyService,
      stubProjectStorageServiceLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithPlainTextResponse
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
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      plainTextCliExecutorLayer,
      startupAiConfigLayer,
      PromptLoader.reloading,
      noOpGovernancePolicyService,
      stubProjectStorageServiceLayer,
      PlannerAgentService.live,
    )

  private val plannerLayerWithBlockingGovernance
    : ZLayer[
      Any,
      Nothing,
      PlannerAgentService & ChatRepository & PlanRepository,
    ] =
    ZLayer.make[
      PlannerAgentService & ChatRepository & PlanRepository
    ](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      RecordingBoardRepo.refLayer,
      RecordingBoardRepo.layer,
      InMemorySpecificationRepo.refLayer,
      InMemorySpecificationRepo.layer,
      InMemoryPlanRepo.refLayer,
      InMemoryPlanRepo.layer,
      testWorkspaceRepository,
      testConfigRepository,
      noopActivityHub,
      testConfigResolver,
      stubHttpClient,
      cliContextRefLayer,
      stubCliExecutorLayer,
      startupAiConfigLayer,
      PromptLoader.reloading,
      blockingGovernancePolicyService,
      stubProjectStorageServiceLayer,
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
          service  <- ZIO.service[PlannerAgentService]
          chat     <- ZIO.service[ChatRepository]
          specHist <- ZIO.service[Ref[Map[SpecificationId, List[SpecificationEvent]]]]
          start    <- service.startSession("Plan a new planner feature", Some("ws-1"))
          state    <- awaitSettledPreview(service, start.conversationId)
          conv     <- chat.getConversation(start.conversationId)
          history  <- state.specificationId match
                        case Some(specId) => specHist.get.map(_.getOrElse(specId, Nil))
                        case None         => ZIO.succeed(Nil)
        yield assertTrue(
          start.warning.isEmpty,
          conv.exists(_.title.startsWith("Planner:")),
          conv.flatMap(_.description).contains("planner-session|workspace:ws-1"),
          state.preview.issues.size == 2,
          state.specificationId.isDefined,
          history.exists {
            case SpecificationEvent.Created(_, _, _, _, _, linkedPlanRef, _) =>
              linkedPlanRef.contains(s"planner:${start.conversationId}")
            case _                                                           => false
          },
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
      test("startSession tolerates Gemini planner payload variants") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan Rust unit tests", Some("ws-1"))
          state   <- awaitSettledPreview(service, start.conversationId)
        yield assertTrue(
          state.lastError.isEmpty,
          state.preview.summary == "Generated planner preview with 1 issue.",
          state.preview.issues.size == 1,
          state.preview.issues.head.draftId == "issue-1",
          state.preview.issues.head.title.startsWith("Analyze the existing Rust code"),
          state.preview.issues.head.acceptanceCriteria.contains("testable components"),
          state.preview.issues.head.requiredCapabilities == List("Rust analysis", "Codebase inspection"),
          state.preview.issues.head.promptTemplate.contains("read_file"),
          state.preview.issues.head.proofOfWorkRequirements.nonEmpty,
        )
      }.provideLayer(plannerLayerWithGeminiStyleResponse),
      test("startSession tolerates plain text planner responses by synthesizing an empty preview") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan Rust unit tests", Some("ws-1"))
          state   <- awaitSettledPreview(service, start.conversationId)
        yield assertTrue(
          state.lastError.isEmpty,
          state.preview.issues.isEmpty,
          state.preview.summary.contains("No changes are needed"),
        )
      }.provideLayer(plannerLayerWithPlainTextResponse),
      test("confirmPlan creates board issue for workspace plans and skips event-store creation") {
        for
          service   <- ZIO.service[PlannerAgentService]
          boardRepo <- ZIO.service[BoardRepository]
          ref       <- ZIO.service[Ref[Vector[IssueEvent]]]
          start     <- service.startSession("Plan a new planner feature", Some("ws-1"))
          state     <- awaitSettledPreview(service, start.conversationId)
          result    <- service.confirmPlan(start.conversationId)
          events    <- ref.get
          todo      <- boardRepo.listIssues("/tmp/projects/test-project", BoardColumn.Todo)
        yield assertTrue(
          result.issueIds.size == 1,
          events.isEmpty,
          todo.size == 1,
          todo.head.frontmatter.id.value == result.issueIds.head.value,
          todo.head.frontmatter.tags.contains("skill:task-planning"),
          state.specificationId.exists(specId => todo.head.frontmatter.tags.contains(s"spec:${specId.value}")),
          todo.head.frontmatter.tags.exists(_.startsWith("plan:")),
          todo.head.frontmatter.acceptanceCriteria.nonEmpty,
          todo.head.frontmatter.estimate.contains(IssueEstimate.M),
          todo.head.frontmatter.proofOfWork.nonEmpty,
        )
      }.provideLayer(plannerLayer),
      test("confirmPlan fails with IssueDraftInvalid when no workspace is set") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan a new planner feature", None)
          _       <- awaitSettledPreview(service, start.conversationId)
          result  <- service.confirmPlan(start.conversationId).exit
        yield assert(result)(fails(isSubtype[PlannerAgentError.IssueDraftInvalid](anything)))
      }.provideLayer(plannerLayer),
      test("confirmPlan persists a plan and links the specification to it") {
        for
          service  <- ZIO.service[PlannerAgentService]
          planRepo <- ZIO.service[PlanRepository]
          specRepo <- ZIO.service[SpecificationRepository]
          start    <- service.startSession("Plan a new planner feature", Some("ws-1"))
          _        <- awaitSettledPreview(service, start.conversationId)
          result   <- service.confirmPlan(start.conversationId)
          plans    <- planRepo.list
          plan     <- ZIO.fromOption(plans.headOption).orElseFail(new RuntimeException("expected persisted plan"))
          spec     <- ZIO
                        .fromOption(plan.specificationId)
                        .orElseFail(new RuntimeException("expected specification id"))
                        .flatMap(specRepo.get)
        yield assertTrue(
          result.issueIds.nonEmpty,
          plans.size == 1,
          plan.status == PlanStatus.Executing,
          plan.linkedIssueIds == result.issueIds,
          plan.versions.map(_.version) == List(1),
          spec.linkedPlanRef.contains(s"plan:${plan.id.value}"),
        )
      }.provideLayer(plannerLayer),
      test("reconfirming after preview changes revises the persisted plan without losing history") {
        for
          service  <- ZIO.service[PlannerAgentService]
          planRepo <- ZIO.service[PlanRepository]
          start    <- service.startSession("Plan a new planner feature", Some("ws-1"))
          _        <- awaitSettledPreview(service, start.conversationId)
          _        <- service.confirmPlan(start.conversationId)
          _        <- service.updatePreview(
                        start.conversationId,
                        PlannerPlanPreview(
                          summary = "Updated generated plan",
                          issues = List(
                            PlanTaskDraft(
                              draftId = "issue-1",
                              title = "Design data model",
                              description = "Define planner data structures",
                              issueType = "task",
                              priority = "high",
                              estimate = Some("M"),
                              requiredCapabilities = List("scala", "zio"),
                              acceptanceCriteria = "Model compiles",
                              promptTemplate = "Implement the data model",
                              kaizenSkills = List("task-planning"),
                              proofOfWorkRequirements = List("tests pass", "coverage > 80%"),
                            ),
                            PlanTaskDraft(
                              draftId = "issue-3",
                              title = "Ship rollout",
                              description = "Prepare rollout notes",
                              issueType = "task",
                              priority = "medium",
                              dependencyDraftIds = List("issue-1"),
                            ),
                          ),
                        ),
                      )
          _        <- service.confirmPlan(start.conversationId)
          plans    <- planRepo.list
          plan     <- ZIO.fromOption(plans.headOption).orElseFail(new RuntimeException("expected persisted plan"))
        yield assertTrue(
          plans.size == 1,
          plan.version == 2,
          plan.versions.map(_.version) == List(1, 2),
          plan.summary == "Updated generated plan",
          plan.drafts.exists(_.draftId == "issue-3"),
        )
      }.provideLayer(plannerLayer),
      test("confirmPlan records blocked validation results when governance requires review") {
        for
          service  <- ZIO.service[PlannerAgentService]
          planRepo <- ZIO.service[PlanRepository]
          start    <- service.startSession("Plan a new planner feature", Some("ws-1"))
          _        <- awaitSettledPreview(service, start.conversationId)
          exit     <- service.confirmPlan(start.conversationId).exit
          plans    <- planRepo.list
          plan     <- ZIO.fromOption(plans.headOption).orElseFail(new RuntimeException("expected persisted plan"))
        yield assertTrue(
          exit.isFailure,
          plan.status == PlanStatus.Draft,
          plan.validation.exists(_.status == PlanValidationStatus.Blocked),
          plan.validation.exists(_.missingGates.contains(GovernanceGate.PlanningReview)),
        )
      }.provideLayer(plannerLayerWithBlockingGovernance),
      test("regenerating a changed preview revises the linked specification") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan a new planner feature", Some("ws-1"))
          state1  <- awaitSettledPreview(service, start.conversationId)
          _       <- service.appendUserMessage(start.conversationId, "Also include rollout and migration notes")
          state2  <- awaitSettledPreview(service, start.conversationId)
          specId  <- ZIO
                       .fromOption(state2.specificationId)
                       .orElseFail(new RuntimeException("expected specification id"))
          repo    <- ZIO.service[SpecificationRepository]
          spec    <- repo.get(specId)
          history <- repo.history(specId)
        yield assertTrue(
          state1.specificationId.contains(specId),
          state2.specificationId.contains(specId),
          spec.version >= 2,
          spec.content.contains("migration notes"),
          history.exists(_.isInstanceOf[SpecificationEvent.Revised]),
        )
      }.provideLayer(plannerLayer),
      test("updatePreview validates blank titles") {
        for
          service <- ZIO.service[PlannerAgentService]
          start   <- service.startSession("Plan a new planner feature", None)
          _       <- awaitSettledPreview(service, start.conversationId)
          exit    <-
            service
              .updatePreview(start.conversationId, PlannerPlanPreview("x", List(PlanTaskDraft("i1", "", "desc"))))
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
                         PlannerPlanPreview("x", List(PlanTaskDraft("i1", "Title", "desc", estimate = Some("XXL")))),
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
