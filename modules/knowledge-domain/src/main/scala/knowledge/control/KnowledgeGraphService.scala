package knowledge.control

import zio.*

import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import knowledge.entity.{ DecisionLog, DecisionLogFilter, DecisionLogRepository, * }
import memory.entity.{ MemoryEntry, MemoryFilter, MemoryKind, MemoryRepository, UserId }
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
  private val knowledgeUserId = UserId("knowledge")

  val live: ZLayer[DecisionLogRepository & MemoryRepository & AnalysisRepository, Nothing, KnowledgeGraphService] =
    ZLayer.fromZIO {
      for
        decisionLogs <- ZIO.service[DecisionLogRepository]
        memoryRepo   <- ZIO.service[MemoryRepository]
        analysisRepo <- ZIO.service[AnalysisRepository]
      yield KnowledgeGraphServiceLive(decisionLogs, memoryRepo, analysisRepo)
    }

  private val knowledgeKinds = Set(
    MemoryKind.Decision,
    MemoryKind.ArchitecturalRationale,
    MemoryKind.DesignConstraint,
    MemoryKind.LessonsLearned,
    MemoryKind.SystemUnderstanding,
  )

  private val architecturalKinds = Set(
    MemoryKind.ArchitecturalRationale,
    MemoryKind.DesignConstraint,
    MemoryKind.LessonsLearned,
    MemoryKind.SystemUnderstanding,
  )

  final private case class SemanticHit(memory: MemoryEntry, score: Double, decisionLogId: Option[String])

  final private case class KnowledgeGraphServiceLive(
    decisionLogs: DecisionLogRepository,
    memoryRepo: MemoryRepository,
    analysisRepo: AnalysisRepository,
  ) extends KnowledgeGraphService:

    override def searchDecisions(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, List[KnowledgeDecisionMatch]] =
      for
        allLogs      <- decisionLogs.list(DecisionLogFilter(workspaceId = workspaceId, limit = Int.MaxValue))
        explicitLogs <- decisionLogs.list(
                          DecisionLogFilter(
                            workspaceId = workspaceId,
                            query = Option(query.trim).filter(_.nonEmpty),
                            limit = Math.max(limit, 1) * 3,
                          )
                        )
        semanticHits <- semanticKnowledge(query, workspaceId, Math.max(limit, 1) * 5, knowledgeKinds)
        ranked        = rankDecisionMatches(allLogs, explicitLogs, semanticHits)
      yield ranked.take(limit.max(0))

    override def getArchitecturalContext(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, ArchitecturalContext] =
      for
        decisions   <- searchDecisions(query, workspaceId, limit)
        memories    <- semanticKnowledge(query, workspaceId, Math.max(limit, 1) * 5, architecturalKinds)
        docs        <-
          workspaceId.fold[IO[PersistenceError, List[AnalysisDoc]]](ZIO.succeed(Nil))(analysisRepo.listByWorkspace)
        edges        = buildEdges(
                         decisions.map(_.decision),
                         memories.flatMap(_.decisionLogId).distinct,
                         memories,
                       )
        filteredDocs = docs
                         .filter(_.analysisType == AnalysisType.Architecture)
                         .filter(doc => query.trim.isEmpty || doc.content.toLowerCase.contains(query.trim.toLowerCase))
                         .take(limit.max(0))
      yield ArchitecturalContext(
        decisions = decisions,
        knowledgeEntries = memories.map(_.memory).take(limit.max(0)),
        analysisDocs = filteredDocs,
        edges = edges,
      )

    private def semanticKnowledge(
      query: String,
      workspaceId: Option[String],
      limit: Int,
      allowedKinds: Set[MemoryKind],
    ): IO[PersistenceError, List[SemanticHit]] =
      if query.trim.isEmpty then ZIO.succeed(Nil)
      else
        memoryRepo
          .searchRelevant(knowledgeUserId, query, limit, MemoryFilter(userId = Some(knowledgeUserId)))
          .mapError(err =>
            PersistenceError.QueryFailed("knowledgeSemanticSearch", Option(err.getMessage).getOrElse(err.toString))
          )
          .map(
            _.collect {
              case scored
                   if allowedKinds.contains(scored.entry.kind) &&
                   workspaceId.forall(id => scored.entry.tags.contains(s"workspace:$id")) =>
                SemanticHit(
                  memory = scored.entry,
                  score = scored.score.toDouble,
                  decisionLogId = tagValue(scored.entry.tags, "decision-log:"),
                )
            }
          )

    private def rankDecisionMatches(
      allLogs: List[DecisionLog],
      explicitLogs: List[DecisionLog],
      semanticHits: List[SemanticHit],
    ): List[KnowledgeDecisionMatch] =
      val byId           = allLogs.map(log => log.id.value -> log).toMap
      val explicitScores = explicitLogs.groupMapReduce(_.id.value)(_ => 1.0)(Math.max)
      val semanticScores = semanticHits
        .flatMap(hit => hit.decisionLogId.map(_ -> hit.score))
        .groupMapReduce(_._1)(_._2)(Math.max)

      (explicitScores.keySet ++ semanticScores.keySet).toList
        .flatMap(id => byId.get(id).map(_ -> (explicitScores.getOrElse(id, 0.0) + semanticScores.getOrElse(id, 0.0))))
        .sortBy(_._2)(Ordering[Double].reverse)
        .map {
          case (decision, score) =>
            KnowledgeDecisionMatch(
              decision = decision,
              score = score,
              relatedEdges = buildEdges(List(decision), semanticHits.flatMap(_.decisionLogId).distinct, semanticHits),
            )
        }

    private def buildEdges(
      decisions: List[DecisionLog],
      semanticIds: List[String],
      semanticHits: List[SemanticHit],
    ): List[KnowledgeEdge] =
      decisions.flatMap { decision =>
        val explicit      = decision.relatedDecisionLogIds.map(relatedId =>
          KnowledgeEdge(
            fromId = decision.id.value,
            toId = relatedId.value,
            relation = "references_decision",
            score = 1.0,
            explicit = true,
          )
        )
        val semantic      = semanticHits.collect {
          case hit if hit.decisionLogId.contains(decision.id.value) =>
            KnowledgeEdge(
              fromId = decision.id.value,
              toId = hit.memory.id.value,
              relation = s"semantic_${hit.memory.kind.value.toLowerCase}",
              score = hit.score,
              explicit = false,
            )
        }
        val sharedSources = semanticIds
          .filter(_ != decision.id.value)
          .map(other =>
            KnowledgeEdge(
              fromId = decision.id.value,
              toId = other,
              relation = "related_by_context",
              score = 0.6,
              explicit = false,
            )
          )
        (explicit ++ semantic ++ sharedSources).distinctBy(edge => (edge.fromId, edge.toId, edge.relation))
      }.distinctBy(edge => (edge.fromId, edge.toId, edge.relation))

    private def tagValue(tags: List[String], prefix: String): Option[String] =
      tags.collectFirst {
        case tag if tag.startsWith(prefix) => tag.stripPrefix(prefix)
      }
