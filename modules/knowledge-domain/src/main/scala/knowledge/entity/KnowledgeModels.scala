package knowledge.entity

import analysis.entity.AnalysisDoc
import memory.entity.MemoryEntry

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
  knowledgeEntries: List[MemoryEntry],
  analysisDocs: List[AnalysisDoc],
  edges: List[KnowledgeEdge],
)
