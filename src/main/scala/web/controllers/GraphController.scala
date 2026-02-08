package web.controllers

import zio.*
import zio.http.*
import zio.json.*

import db.*
import models.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait GraphController:
  def routes: Routes[Any, Response]

object GraphController:

  def routes: ZIO[GraphController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[GraphController](_.routes)

  val live: ZLayer[MigrationRepository, Nothing, GraphController] =
    ZLayer.fromFunction(GraphControllerLive.apply)

final case class GraphControllerLive(
  repository: MigrationRepository
) extends GraphController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "graph"                                    -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          runId <- resolveGraphRunId(req)
          deps  <- repository.getDependenciesByRun(runId)
        yield html(HtmlViews.graphPage(runId, deps))
      }
    },
    Method.GET / "api" / "graph" / long("runId")            -> handler { (runId: Long, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        repository.getDependenciesByRun(runId).map(deps => Response.json(deps.toJson))
      }
    },
    Method.GET / "api" / "graph" / long("runId") / "export" -> handler { (runId: Long, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          format <- getStringQuery(req, "format").map(_.toLowerCase)
          deps   <- repository.getDependenciesByRun(runId)
          graph   = toDependencyGraph(deps)
          resp   <- format match
                      case "json"    => ZIO.succeed(Response.json(deps.toJson))
                      case "mermaid" => ZIO.succeed(Response.text(DependencyGraphBuilder.toMermaid(graph)))
                      case "d3"      => ZIO.succeed(Response.json(DependencyGraphBuilder.toD3Json(graph)))
                      case other     => ZIO.fail(
                          PersistenceError.QueryFailed(
                            "graph.export",
                            s"Unsupported format: $other (expected mermaid|json|d3)",
                          )
                        )
        yield resp
      }
    },
  )

  private def resolveGraphRunId(req: Request): IO[PersistenceError, Long] =
    req.queryParam("runId").flatMap(_.toLongOption) match
      case Some(runId) => ZIO.succeed(runId)
      case None        =>
        repository
          .listRuns(offset = 0, limit = 1)
          .flatMap(_.headOption.map(_.id) match
            case Some(runId) => ZIO.succeed(runId)
            case None        =>
              ZIO.fail(
                PersistenceError.QueryFailed(
                  "query:runId",
                  "Missing query parameter 'runId' and no migration runs are available",
                )
              ))

  private def getStringQuery(req: Request, key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(req.queryParam(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed(s"query:$key", s"Missing query parameter '$key'"))

  private def toDependencyGraph(dependencies: List[DependencyRow]): DependencyGraph =
    val nodeIds = dependencies.flatMap(dep => List(dep.sourceNode, dep.targetNode)).distinct
    val nodes   = nodeIds.map(id => DependencyNode(id = id, name = id, nodeType = NodeType.Program, complexity = 0))
    val edges   = dependencies.map { dep =>
      val edgeType = dep.edgeType.toLowerCase match
        case "includes" => EdgeType.Includes
        case "uses"     => EdgeType.Uses
        case _          => EdgeType.Calls
      DependencyEdge(
        from = dep.sourceNode,
        to = dep.targetNode,
        edgeType = edgeType,
      )
    }
    DependencyGraph(nodes = nodes, edges = edges, serviceCandidates = List.empty)

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
