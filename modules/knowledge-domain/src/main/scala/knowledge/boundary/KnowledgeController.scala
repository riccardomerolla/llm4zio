package knowledge.boundary

import zio.*
import zio.http.*

import knowledge.control.KnowledgeGraphService
import knowledge.entity.{ DecisionLogFilter, DecisionLogRepository }
import shared.errors.PersistenceError
import workspace.entity.WorkspaceRepository

trait KnowledgeController:
  def routes: Routes[Any, Response]

object KnowledgeController:

  def routes: ZIO[KnowledgeController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[KnowledgeController](_.routes)

  val live
    : ZLayer[DecisionLogRepository & KnowledgeGraphService & WorkspaceRepository, Nothing, KnowledgeController] =
    ZLayer {
      for
        decisionLogs  <- ZIO.service[DecisionLogRepository]
        graph         <- ZIO.service[KnowledgeGraphService]
        workspaceRepo <- ZIO.service[WorkspaceRepository]
      yield make(decisionLogs, graph, workspaceRepo)
    }

  def make(
    decisionLogs: DecisionLogRepository,
    graph: KnowledgeGraphService,
    workspaceRepo: WorkspaceRepository,
  ): KnowledgeController =
    new KnowledgeController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "knowledge" -> handler { (req: Request) =>
          listPage(req, decisionLogs, graph, workspaceRepo).catchAll(error =>
            ZIO.succeed(persistErr(error))
          )
        }
      )

  private def listPage(
    req: Request,
    decisionLogs: DecisionLogRepository,
    graph: KnowledgeGraphService,
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
      workspaces <- workspaceRepo.list
                      .mapError(err => PersistenceError.QueryFailed("knowledgeListWorkspaces", err.toString))
                      .map(_.filter(_.enabled).map(ws => ws.id -> ws.name))
    yield Response
      .text(KnowledgeView.page(timeline, context, query, workspaceId, workspaces))
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
