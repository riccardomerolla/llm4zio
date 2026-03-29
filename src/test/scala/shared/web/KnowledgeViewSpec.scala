package shared.web

import java.time.Instant

import zio.test.*

import analysis.entity.{ AnalysisDoc, AnalysisType }
import knowledge.control.{ ArchitecturalContext, KnowledgeDecisionMatch, KnowledgeEdge }
import knowledge.entity.{ DecisionLog, DecisionLogVersion, DecisionMaker, DecisionMakerKind }
import memory.entity.{ MemoryEntry, MemoryId, MemoryKind, SessionId, UserId }
import shared.ids.Ids.{ AgentId, DecisionLogId, IssueId }

object KnowledgeViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T13:00:00Z")

  private val decision = DecisionLog(
    id = DecisionLogId("decision-log-1"),
    title = "Adopt knowledge graph",
    context = "Need linked architectural rationale",
    decisionTaken = "Build a lightweight graph service",
    rationale = "Enough to support MCP and the web view",
    consequences = List("Derived relationships only"),
    decisionDate = now,
    decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "architect"),
    workspaceId = Some("ws-1"),
    issueIds = List(IssueId("issue-1")),
    versions = List(
      DecisionLogVersion(
        version = 1,
        title = "Adopt knowledge graph",
        context = "Need linked architectural rationale",
        decisionTaken = "Build a lightweight graph service",
        rationale = "Enough to support MCP and the web view",
        changedAt = now,
      )
    ),
    createdAt = now,
    updatedAt = now,
  )

  private val memory = MemoryEntry(
    id = MemoryId("mem-1"),
    userId = UserId("knowledge"),
    sessionId = SessionId("run:1"),
    text = "Keep the graph derived from explicit ids and semantic memory links.",
    embedding = Vector.empty,
    tags = List("knowledge", "workspace:ws-1", "decision-log:decision-log-1"),
    kind = MemoryKind.ArchitecturalRationale,
    createdAt = now,
    lastAccessedAt = now,
  )

  private val context = ArchitecturalContext(
    decisions = List(KnowledgeDecisionMatch(decision, 0.92)),
    knowledgeEntries = List(memory),
    analysisDocs = List(
      AnalysisDoc(
        id = shared.ids.Ids.AnalysisDocId("analysis-1"),
        workspaceId = "ws-1",
        analysisType = AnalysisType.Architecture,
        content = "Architecture summary",
        filePath = ".llm4zio/analysis/architecture.md",
        generatedBy = AgentId("architect"),
        createdAt = now,
        updatedAt = now,
      )
    ),
    edges = List(KnowledgeEdge("decision-log-1", "mem-1", "semantic_architecturalrationale", 0.82, explicit = false)),
  )

  def spec: Spec[Any, Nothing] =
    suite("KnowledgeViewSpec")(
      test("page renders decision timeline and context sections") {
        val html = KnowledgeView.page(
          List(decision),
          context,
          List(memory),
          Some("graph"),
          Some("ws-1"),
          List("ws-1" -> "Knowledge Workspace"),
        )
        assertTrue(
          html.contains("Knowledge Base"),
          html.contains("Decision Timeline"),
          html.contains("Rationale Browser"),
          html.contains("Architectural Context"),
          html.contains("Adopt knowledge graph"),
          html.contains("Architecture docs"),
        )
      }
    )
