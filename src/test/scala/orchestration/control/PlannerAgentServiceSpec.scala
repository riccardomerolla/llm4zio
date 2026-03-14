package orchestration.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import _root_.config.entity.AIProviderConfig
import conversation.entity.api.*
import db.{ ChatRepository, PersistenceError }
import issues.entity.{ IssueEvent, IssueRepository }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }

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

  private val testConfigResolver: ULayer[AgentConfigResolver] =
    ZLayer.succeed(new AgentConfigResolver:
      override def resolveConfig(agentName: String): IO[PersistenceError, AIProviderConfig] =
        ZIO.succeed(AIProviderConfig.withDefaults(AIProviderConfig())))

  private val testLlm: ULayer[LlmService] =
    ZLayer.succeed(new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse]                                       =
        ZIO.succeed(LlmResponse(prompt))
      override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk]                     =
        zio.stream.ZStream.empty
      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]                   =
        ZIO.succeed(LlmResponse("history"))
      override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
        zio.stream.ZStream.empty
      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse]   =
        ZIO.succeed(ToolCallResponse(Some("ok"), Nil, "stop"))
      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]     =
        val payload =
          PlannerStructuredResponse(
            summary = "Generated plan",
            issues = List(
              PlannerIssueDraft(
                draftId = "issue-1",
                title = "Design data model",
                description = "Define planner data structures",
                priority = "high",
                requiredCapabilities = List("scala", "zio"),
                acceptanceCriteria = "Model compiles",
                promptTemplate = "Implement the data model",
                kaizenSkills = List("task-planning"),
                proofOfWorkRequirements = List("tests pass", "coverage > 80%"),
              ),
              PlannerIssueDraft(
                draftId = "issue-2",
                title = "Wire controller",
                description = "Expose planner routes",
                priority = "medium",
                dependencyDraftIds = List("issue-1"),
                acceptanceCriteria = "Routes are reachable",
                promptTemplate = "Wire the planner controller",
              ),
            ),
          ).toJson
        ZIO.fromEither(payload.fromJson[A]).mapError(err => LlmError.ParseError(err, payload))
      override def isAvailable: UIO[Boolean]                                                                = ZIO.succeed(true))

  private val stubHttpClient: ULayer[HttpClient] = ZLayer.succeed(new HttpClient:
    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
      : IO[LlmError, String] =
      ZIO.fail(LlmError.ProviderError("unused", None)))

  private val stubCliExecutor: ULayer[GeminiCliExecutor] = ZLayer.succeed(new GeminiCliExecutor:
    override def checkGeminiInstalled: IO[LlmError, Unit] = ZIO.unit
    override def runGeminiProcess(
      prompt: String,
      config: LlmConfig,
      executionContext: llm4zio.providers.GeminiCliExecutionContext,
    ): IO[LlmError, String] = ZIO.fail(LlmError.ProviderError("unused", None)))

  private val plannerLayer
    : ZLayer[Any, Nothing, PlannerAgentService & ChatRepository & Ref[Vector[IssueEvent]]] =
    ZLayer.make[PlannerAgentService & ChatRepository & Ref[Vector[IssueEvent]]](
      InMemoryChatRepo.layer,
      RecordingIssueRepo.refLayer,
      RecordingIssueRepo.layer,
      testConfigResolver,
      testLlm,
      stubHttpClient,
      stubCliExecutor,
      PlannerAgentService.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PlannerAgentServiceSpec")(
      test("startSession creates a planner conversation and preview") {
        for
          service <- ZIO.service[PlannerAgentService]
          chat    <- ZIO.service[ChatRepository]
          id      <- service.startSession("Plan a new planner feature", Some("ws-1"))
          conv    <- chat.getConversation(id)
          state   <- service.getPreview(id)
        yield assertTrue(
          conv.exists(_.title.startsWith("Planner:")),
          conv.flatMap(_.description).contains("planner-session|workspace:ws-1"),
          state.preview.issues.size == 2,
        )
      },
      test("confirmPlan emits issue, prompt, acceptance, tag, workspace, and dependency events") {
        for
          service <- ZIO.service[PlannerAgentService]
          ref     <- ZIO.service[Ref[Vector[IssueEvent]]]
          id      <- service.startSession("Plan a new planner feature", Some("ws-1"))
          result  <- service.confirmPlan(id)
          events  <- ref.get
        yield assertTrue(
          result.issueIds.size == 2,
          events.count(_.isInstanceOf[IssueEvent.Created]) == 2,
          events.exists(_.isInstanceOf[IssueEvent.DependencyLinked]),
          events.exists(_.isInstanceOf[IssueEvent.PromptTemplateUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.AcceptanceCriteriaUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.KaizenSkillUpdated]),
          events.exists(_.isInstanceOf[IssueEvent.ProofOfWorkRequirementsUpdated]),
          events.count(_.isInstanceOf[IssueEvent.WorkspaceLinked]) == 2,
          events.exists {
            case IssueEvent.TagsUpdated(_, tags, _) => tags.contains("skill:task-planning")
            case _                                  => false
          },
        )
      },
      test("updatePreview validates blank titles") {
        for
          service <- ZIO.service[PlannerAgentService]
          id      <- service.startSession("Plan a new planner feature", None)
          exit    <- service.updatePreview(id, PlannerPlanPreview("x", List(PlannerIssueDraft("i1", "", "desc")))).exit
        yield assertTrue(exit.isFailure)
      },
    ).provideLayerShared(plannerLayer)
