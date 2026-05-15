package shared.web

import java.time.Instant

import zio.test.*

import analysis.entity.{ AnalysisDoc, AnalysisType }
import knowledge.boundary.KnowledgeView
import knowledge.entity.*
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

  private val context = ArchitecturalContext(
    decisions = List(KnowledgeDecisionMatch(decision, 0.92)),
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
    edges = List(KnowledgeEdge("decision-log-1", "decision-log-2", "references_decision", 1.0, explicit = true)),
  )

  def spec: Spec[Any, Nothing] =
    suite("KnowledgeViewSpec")(
      test("page renders decision timeline and context sections") {
        val html = KnowledgeView.page(
          List(decision),
          context,
          Some("graph"),
          Some("ws-1"),
          List("ws-1" -> "Knowledge Workspace"),
        )
        assertTrue(
          html.contains("Knowledge Base"),
          html.contains("Decision Timeline"),
          html.contains("Architectural Context"),
          html.contains("Adopt knowledge graph"),
          html.contains("Architecture docs"),
        )
      }
    )
