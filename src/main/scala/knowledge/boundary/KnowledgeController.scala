package knowledge.boundary

import zio.*
import zio.http.*

import knowledge.control.KnowledgeGraphService
import knowledge.entity.{ DecisionLogFilter, DecisionLogRepository }
import memory.entity.{ MemoryFilter, MemoryKind, MemoryRepository, Scope }
import shared.errors.PersistenceError
import shared.web.KnowledgeView
import workspace.entity.WorkspaceRepository

trait KnowledgeController:
  def routes: Routes[Any, Response]

object KnowledgeController:
  private val knowledgeScope = Scope("knowledge")

  def routes: ZIO[KnowledgeController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[KnowledgeController](_.routes)

  val live
    : ZLayer[DecisionLogRepository & KnowledgeGraphService & MemoryRepository & WorkspaceRepository, Nothing, KnowledgeController] =
    ZLayer {
      for
        decisionLogs  <- ZIO.service[DecisionLogRepository]
        graph         <- ZIO.service[KnowledgeGraphService]
        memoryRepo    <- ZIO.service[MemoryRepository]
        workspaceRepo <- ZIO.service[WorkspaceRepository]
      yield make(decisionLogs, graph, memoryRepo, workspaceRepo)
    }

  def make(
    decisionLogs: DecisionLogRepository,
    graph: KnowledgeGraphService,
    memoryRepo: MemoryRepository,
    workspaceRepo: WorkspaceRepository,
  ): KnowledgeController =
    new KnowledgeController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "knowledge" -> handler { (req: Request) =>
          listPage(req, decisionLogs, graph, memoryRepo, workspaceRepo).catchAll(error =>
            ZIO.succeed(persistErr(error))
          )
        }
      )

  private def listPage(
    req: Request,
    decisionLogs: DecisionLogRepository,
    graph: KnowledgeGraphService,
    memoryRepo: MemoryRepository,
    workspaceRepo: WorkspaceRepository,
  ): IO[PersistenceError, Response] =
    val query       = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
    val workspaceId = req.queryParam("workspaceId").map(_.trim).filter(_.nonEmpty)
    val limit       = req.queryParam("limit").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(12)
    for
      timeline   <- decisionLogs.list(
                      DecisionLogFilter(
                        workspaceId = workspaceId,
                        query = query,
                        limit = limit,
                      )
                    )
      context    <- graph.getArchitecturalContext(query.getOrElse(""), workspaceId, limit)
      browser    <- memoryRepo
                      .listByScope(knowledgeScope, MemoryFilter(scope = Some(knowledgeScope)), 0, 200)
                      .mapError(err => PersistenceError.QueryFailed("knowledgeListMemories", err.toString))
                      .map(
                        _.filter(entry =>
                          Set(
                            MemoryKind.ArchitecturalRationale,
                            MemoryKind.DesignConstraint,
                            MemoryKind.LessonsLearned,
                            MemoryKind.SystemUnderstanding,
                          ).contains(entry.kind) &&
                          workspaceId.forall(id => entry.tags.contains(s"workspace:$id")) &&
                          query.forall(q => entry.text.toLowerCase.contains(q.toLowerCase))
                        ).take(limit)
                      )
      workspaces <- workspaceRepo.list
                      .mapError(err => PersistenceError.QueryFailed("knowledgeListWorkspaces", err.toString))
                      .map(_.filter(_.enabled).map(ws => ws.id -> ws.name))
    yield Response
      .text(KnowledgeView.page(timeline, context, browser, query, workspaceId, workspaces))
      .contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Knowledge store unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Knowledge query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Knowledge serialization failed: $cause").status(Status.InternalServerError)
