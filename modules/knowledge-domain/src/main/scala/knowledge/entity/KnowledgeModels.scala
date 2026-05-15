package knowledge.entity

import analysis.entity.AnalysisDoc

final case class KnowledgeEdge(
  fromId: String,
  toId: String,
  relation: String,
  score: Double,
  explicit: Boolean,
)

final case class KnowledgeDecisionMatch(
  decision: DecisionLog,
  score: Double,
  @annotation.unused relatedEdges: List[KnowledgeEdge] = Nil,
)

final case class ArchitecturalContext(
  decisions: List[KnowledgeDecisionMatch],
  analysisDocs: List[AnalysisDoc],
  edges: List[KnowledgeEdge],
)
