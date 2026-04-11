package knowledge.control

import java.time.Instant

import zio.*
import zio.stream.*
import zio.test.*

import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import conversation.entity.api.ConversationEntry
import db.ChatRepository
import issues.entity.{ AgentIssue, IssueState }
import knowledge.entity.*
import llm4zio.core.{ LlmChunk, LlmError, LlmService, Message, ToolCallResponse }
import llm4zio.tools.{ AnyTool, JsonSchema }
import memory.entity.{ Scope as MemoryScope, * }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AnalysisDocId, DecisionLogId, IssueId }
import workspace.entity.{ RunSessionMode, RunStatus, WorkspaceRun }

object KnowledgeServicesSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T11:00:00Z")

  // ─── Stub repositories ────────────────────────────────────────────────────

  final class StubDecisionLogRepository(ref: Ref[List[DecisionLog]]) extends DecisionLogRepository:

    override def append(event: DecisionLogEvent): IO[PersistenceError, Unit] =
      event match
        case created: DecisionLogEvent.Created =>
          val log = DecisionLog(
            id = created.decisionLogId,
            title = created.title,
            context = created.context,
            decisionTaken = created.decisionTaken,
            rationale = created.rationale,
            decisionDate = created.decisionDate,
            decisionMaker = created.decisionMaker,
            workspaceId = created.workspaceId,
            issueIds = created.issueIds,
            runId = created.runId,
            conversationId = created.conversationId,
            createdAt = created.occurredAt,
            updatedAt = created.occurredAt,
          )
          ref.update(_ :+ log)
        case _                                 => ZIO.unit

    override def get(id: DecisionLogId): IO[PersistenceError, DecisionLog] =
      ref.get.flatMap(logs =>
        logs.find(_.id == id) match
          case Some(log) => ZIO.succeed(log)
          case None      => ZIO.fail(PersistenceError.NotFound("decision_log", id.value))
      )

    override def history(id: DecisionLogId): IO[PersistenceError, List[DecisionLogEvent]] =
      ZIO.succeed(Nil)

    override def list(filter: DecisionLogFilter): IO[PersistenceError, List[DecisionLog]] =
      ref.get.map { logs =>
        logs
          .filter(l => filter.workspaceId.forall(id => l.workspaceId.contains(id)))
          .filter(l => filter.runId.forall(id => l.runId.contains(id)))
          .filter(l => filter.query.forall(q => l.title.toLowerCase.contains(q.toLowerCase)))
          .take(filter.limit)
      }

  object StubDecisionLogRepository:
    def make(initial: List[DecisionLog] = Nil): UIO[StubDecisionLogRepository] =
      Ref.make(initial).map(StubDecisionLogRepository(_))

  final class StubMemoryRepository extends MemoryRepository:
    override def save(entry: MemoryEntry): IO[Throwable, Unit]                                                     = ZIO.unit
    override def searchRelevant(s: MemoryScope, q: String, limit: Int, f: MemoryFilter)
      : IO[Throwable, List[ScoredMemory]] =
      ZIO.succeed(Nil)
    override def listByScope(s: MemoryScope, f: MemoryFilter, page: Int, size: Int): IO[Throwable, List[MemoryEntry]] =
      ZIO.succeed(Nil)
    override def deleteById(s: MemoryScope, id: MemoryId): IO[Throwable, Unit]                                        = ZIO.unit
    override def deleteBySession(sid: SessionId): IO[Throwable, Unit]                                              = ZIO.unit

  final class StubAnalysisRepository extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                        = ZIO.unit
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       =
      ZIO.fail(PersistenceError.NotFound("analysis_doc", id.value))
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   = ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] = ZIO.succeed(Nil)

  final class StubChatRepository extends ChatRepository:
    override def createConversation(c: conversation.entity.api.ChatConversation): IO[PersistenceError, Long]       =
      ZIO.succeed(0L)
    override def getConversation(id: Long): IO[PersistenceError, Option[conversation.entity.api.ChatConversation]] =
      ZIO.succeed(None)
    override def listConversations(offset: Int, limit: Int)
      : IO[PersistenceError, List[conversation.entity.api.ChatConversation]] = ZIO.succeed(Nil)
    override def getConversationsByChannel(channelName: String)
      : IO[PersistenceError, List[conversation.entity.api.ChatConversation]] = ZIO.succeed(Nil)
    override def listConversationsByRun(runId: Long)
      : IO[PersistenceError, List[conversation.entity.api.ChatConversation]] = ZIO.succeed(Nil)
    override def updateConversation(c: conversation.entity.api.ChatConversation): IO[PersistenceError, Unit]       =
      ZIO.unit
    override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                          = ZIO.unit
    override def addMessage(m: ConversationEntry): IO[PersistenceError, Long]                                      = ZIO.succeed(0L)
    override def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]]                  = ZIO.succeed(Nil)
    override def getMessagesSince(conversationId: Long, since: Instant)
      : IO[PersistenceError, List[ConversationEntry]] =
      ZIO.succeed(Nil)

  /** LLM stub that always fails so the fallback extraction payload path is exercised. */
  final class StubLlmService extends LlmService:
    override def executeStream(prompt: String): Stream[LlmError, LlmChunk]                                     =
      ZStream.fail(LlmError.ProviderError("stub"))
    override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]                 =
      ZStream.fail(LlmError.ProviderError("stub"))
    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse]        =
      ZIO.fail(LlmError.ProviderError("stub"))
    override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.ProviderError("stub"))
    override def isAvailable: UIO[Boolean]                                                                     = ZIO.succeed(false)

  // ─── Test helpers ─────────────────────────────────────────────────────────

  private def makeDecisionLog(id: String, title: String, workspaceId: String, runId: String): DecisionLog =
    DecisionLog(
      id = DecisionLogId(id),
      title = title,
      context = "some context",
      decisionTaken = "decision",
      rationale = "rationale",
      decisionDate = now,
      decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "agent"),
      workspaceId = Some(workspaceId),
      runId = Some(runId),
      createdAt = now,
      updatedAt = now,
    )

  private def makeRun(runId: String, workspaceId: String): WorkspaceRun =
    WorkspaceRun(
      id = runId,
      workspaceId = workspaceId,
      parentRunId = None,
      issueRef = "issue-1",
      agentName = "claude",
      prompt = "do something",
      conversationId = "0",
      worktreePath = "/tmp/wt",
      branchName = "branch",
      status = RunStatus.Running(RunSessionMode.Autonomous),
      attachedUsers = Set.empty,
      controllerUserId = None,
      createdAt = now,
      updatedAt = now,
    )

  private def makeIssue(id: String, title: String): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = None,
      conversationId = None,
      title = title,
      description = "desc",
      issueType = "task",
      priority = "Medium",
      requiredCapabilities = Nil,
      state = IssueState.Open(now),
      tags = Nil,
      contextPath = ".",
      sourceFolder = ".",
    )

  private def graphLayer(ref: Ref[List[DecisionLog]]): ZLayer[Any, Nothing, KnowledgeGraphService] =
    ZLayer.make[KnowledgeGraphService](
      KnowledgeGraphService.live,
      ZLayer.fromZIO(ZIO.succeed(StubDecisionLogRepository(ref))),
      ZLayer.succeed(StubMemoryRepository()),
      ZLayer.succeed(StubAnalysisRepository()),
    )

  private def extractionLayer(
    ref: Ref[List[DecisionLog]]
  ): ZLayer[Any, Nothing, KnowledgeExtractionService] =
    ZLayer.make[KnowledgeExtractionService](
      KnowledgeExtractionService.live,
      ZLayer.succeed(StubChatRepository()),
      ZLayer.succeed(StubLlmService()),
      ZLayer.fromZIO(ZIO.succeed(StubDecisionLogRepository(ref))),
      ZLayer.succeed(StubMemoryRepository()),
      ZLayer.succeed(StubAnalysisRepository()),
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("KnowledgeServicesSpec")(
      suite("KnowledgeGraphService")(
        test("searchDecisions returns logs matching query title") {
          for
            ref     <- Ref.make(List(
                         makeDecisionLog("log-1", "Use ZIO for effects", "ws-1", "run-1"),
                         makeDecisionLog("log-2", "Choose Postgres over MySQL", "ws-1", "run-2"),
                       ))
            results <- ZIO.serviceWithZIO[KnowledgeGraphService](_.searchDecisions("ZIO", Some("ws-1"), 10))
                         .provideLayer(graphLayer(ref))
          yield assertTrue(
            results.size == 1,
            results.head.decision.title == "Use ZIO for effects",
          )
        },
        test("searchDecisions with empty query returns all logs") {
          for
            ref     <- Ref.make(List(
                         makeDecisionLog("log-1", "Decision A", "ws-1", "run-1"),
                         makeDecisionLog("log-2", "Decision B", "ws-1", "run-2"),
                       ))
            results <- ZIO.serviceWithZIO[KnowledgeGraphService](_.searchDecisions("", Some("ws-1"), 10))
                         .provideLayer(graphLayer(ref))
          yield assertTrue(results.size == 2)
        },
        test("getArchitecturalContext returns decisions and empty knowledge when no semantic hits") {
          for
            ref <- Ref.make(List(makeDecisionLog("log-1", "Architecture choice", "ws-1", "run-1")))
            ctx <- ZIO.serviceWithZIO[KnowledgeGraphService](
                     _.getArchitecturalContext("Architecture", Some("ws-1"), 10)
                   ).provideLayer(graphLayer(ref))
          yield assertTrue(
            ctx.decisions.size == 1,
            ctx.knowledgeEntries.isEmpty,
            ctx.analysisDocs.isEmpty,
          )
        },
        test("searchDecisions respects the limit parameter") {
          for
            ref     <- Ref.make((1 to 5).toList.map(i =>
                         makeDecisionLog(s"log-$i", s"Decision $i", "ws-1", s"run-$i")
                       ))
            results <- ZIO.serviceWithZIO[KnowledgeGraphService](_.searchDecisions("", Some("ws-1"), 3))
                         .provideLayer(graphLayer(ref))
          yield assertTrue(results.size == 3)
        },
      ),
      suite("KnowledgeExtractionService")(
        test("extractFromCompletedRun returns None when a log already exists for the run") {
          for
            ref    <- Ref.make(List(makeDecisionLog("log-1", "Existing", "ws-1", "run-1")))
            result <- ZIO.serviceWithZIO[KnowledgeExtractionService](
                        _.extractFromCompletedRun(makeRun("run-1", "ws-1"), None)
                      ).provideLayer(extractionLayer(ref))
          yield assertTrue(result.isEmpty)
        },
        test("extractFromCompletedRun creates a decision log for a new run") {
          for
            ref    <- Ref.make(List.empty[DecisionLog])
            result <- ZIO.serviceWithZIO[KnowledgeExtractionService](
                        _.extractFromCompletedRun(makeRun("run-new", "ws-1"), None)
                      ).provideLayer(extractionLayer(ref))
            logs   <- ref.get
          yield assertTrue(
            result.isDefined,
            logs.size == 1,
            logs.head.runId.contains("run-new"),
          )
        },
        test("extractFromCompletedRun uses issue title when issue is provided") {
          for
            ref  <- Ref.make(List.empty[DecisionLog])
            _    <- ZIO.serviceWithZIO[KnowledgeExtractionService](
                      _.extractFromCompletedRun(makeRun("run-x", "ws-2"), Some(makeIssue("issue-1", "My Feature Issue")))
                    ).provideLayer(extractionLayer(ref))
            logs <- ref.get
          yield assertTrue(logs.head.title == "My Feature Issue")
        },
      ),
    )
