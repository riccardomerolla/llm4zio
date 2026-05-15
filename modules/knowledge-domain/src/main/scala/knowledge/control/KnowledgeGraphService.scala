package knowledge.control

import zio.*

import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import knowledge.entity.{ DecisionLog, DecisionLogFilter, DecisionLogRepository, * }
import shared.errors.PersistenceError

trait KnowledgeGraphService:
  def searchDecisions(
    query: String,
    workspaceId: Option[String],
    limit: Int,
  ): IO[PersistenceError, List[KnowledgeDecisionMatch]]

  def getArchitecturalContext(
    query: String,
    workspaceId: Option[String],
    limit: Int,
  ): IO[PersistenceError, ArchitecturalContext]

object KnowledgeGraphService:

  val live: ZLayer[DecisionLogRepository & AnalysisRepository, Nothing, KnowledgeGraphService] =
    ZLayer.fromZIO {
      for
        decisionLogs <- ZIO.service[DecisionLogRepository]
        analysisRepo <- ZIO.service[AnalysisRepository]
      yield KnowledgeGraphServiceLive(decisionLogs, analysisRepo)
    }

  final private case class KnowledgeGraphServiceLive(
    decisionLogs: DecisionLogRepository,
    analysisRepo: AnalysisRepository,
  ) extends KnowledgeGraphService:

    override def searchDecisions(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, List[KnowledgeDecisionMatch]] =
      for
        explicitLogs <- decisionLogs.list(
                          DecisionLogFilter(
                            workspaceId = workspaceId,
                            query = Option(query.trim).filter(_.nonEmpty),
                            limit = Math.max(limit, 1) * 3,
                          )
                        )
      yield explicitLogs
        .map(log => KnowledgeDecisionMatch(decision = log, score = 1.0, relatedEdges = buildEdges(List(log))))
        .take(limit.max(0))

    override def getArchitecturalContext(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, ArchitecturalContext] =
      for
        decisions   <- searchDecisions(query, workspaceId, limit)
        docs        <-
          workspaceId.fold[IO[PersistenceError, List[AnalysisDoc]]](ZIO.succeed(Nil))(analysisRepo.listByWorkspace)
        edges        = buildEdges(decisions.map(_.decision))
        filteredDocs = docs
                         .filter(_.analysisType == AnalysisType.Architecture)
                         .filter(doc => query.trim.isEmpty || doc.content.toLowerCase.contains(query.trim.toLowerCase))
                         .take(limit.max(0))
      yield ArchitecturalContext(
        decisions = decisions,
        analysisDocs = filteredDocs,
        edges = edges,
      )

    private def buildEdges(decisions: List[DecisionLog]): List[KnowledgeEdge] =
      decisions.flatMap { decision =>
        decision.relatedDecisionLogIds.map(relatedId =>
          KnowledgeEdge(
            fromId = decision.id.value,
            toId = relatedId.value,
            relation = "references_decision",
            score = 1.0,
            explicit = true,
          )
        )
      }.distinctBy(edge => (edge.fromId, edge.toId, edge.relation))
