package knowledge.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import analysis.entity.{ AnalysisDoc, AnalysisType }
import knowledge.control.{ ArchitecturalContext, KnowledgeDecisionMatch, KnowledgeEdge, KnowledgeGraphService }
import knowledge.entity.*
import memory.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, DecisionLogId }

object KnowledgeControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T13:10:00Z")

  private val decision = DecisionLog(
    id = DecisionLogId("decision-log-1"),
    title = "Keep issue-linked knowledge first",
    context = "Phase 2 can start without full spec/plan linking",
    decisionTaken = "Link completed runs to issue-derived decision logs",
    rationale = "Meets the current issue scope without extra prerequisites",
    consequences = List("Spec and plan links can be added later"),
    decisionDate = now,
    decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "planner"),
    workspaceId = Some("ws-1"),
    createdAt = now,
    updatedAt = now,
  )

  private val memory = MemoryEntry(
    id = MemoryId("mem-1"),
    userId = UserId("knowledge"),
    sessionId = SessionId("run:1"),
    text = "Start with issue-linked knowledge extraction.",
    embedding = Vector.empty,
    tags = List("workspace:ws-1", "decision-log:decision-log-1"),
    kind = MemoryKind.ArchitecturalRationale,
    createdAt = now,
    lastAccessedAt = now,
  )

  private val stubDecisionLogs: DecisionLogRepository = new DecisionLogRepository:
    override def append(event: DecisionLogEvent): IO[PersistenceError, Unit]              = ZIO.unit
    override def get(id: DecisionLogId): IO[PersistenceError, DecisionLog]                = ZIO.succeed(decision)
    override def history(id: DecisionLogId): IO[PersistenceError, List[DecisionLogEvent]] = ZIO.succeed(Nil)
    override def list(filter: DecisionLogFilter): IO[PersistenceError, List[DecisionLog]] = ZIO.succeed(List(decision))

  private val stubGraph: KnowledgeGraphService = new KnowledgeGraphService:
    override def searchDecisions(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, List[KnowledgeDecisionMatch]] =
      ZIO.succeed(List(KnowledgeDecisionMatch(decision, 0.9)))

    override def getArchitecturalContext(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, ArchitecturalContext] =
      ZIO.succeed(
        ArchitecturalContext(
          decisions = List(KnowledgeDecisionMatch(decision, 0.9)),
          knowledgeEntries = List(memory),
          analysisDocs = List(
            AnalysisDoc(
              id = shared.ids.Ids.AnalysisDocId("analysis-1"),
              workspaceId = "ws-1",
              analysisType = AnalysisType.Architecture,
              content = "Architecture context",
              filePath = ".llm4zio/analysis/architecture.md",
              generatedBy = AgentId("architect"),
              createdAt = now,
              updatedAt = now,
            )
          ),
          edges =
            List(KnowledgeEdge("decision-log-1", "mem-1", "semantic_architecturalrationale", 0.8, explicit = false)),
        )
      )

  private val stubMemoryRepo: MemoryRepository = new MemoryRepository:
    override def save(entry: MemoryEntry): IO[Throwable, Unit]                 = ZIO.unit
    override def searchRelevant(userId: UserId, query: String, limit: Int, filter: MemoryFilter)
      : IO[Throwable, List[ScoredMemory]] = ZIO.succeed(Nil)
    override def listForUser(userId: UserId, filter: MemoryFilter, page: Int, pageSize: Int)
      : IO[Throwable, List[MemoryEntry]] = ZIO.succeed(List(memory))
    override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] = ZIO.unit
    override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit]    = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("KnowledgeControllerSpec")(
      test("GET /knowledge renders the knowledge page") {
        val routes = KnowledgeController.make(stubDecisionLogs, stubGraph, stubMemoryRepo).routes
        for
          resp <- routes.runZIO(Request.get(URL.decode("/knowledge?q=issue&workspaceId=ws-1").toOption.get))
          body <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Knowledge Base"),
          body.contains("Keep issue-linked knowledge first"),
          body.contains("Architecture context"),
        )
      }
    )
