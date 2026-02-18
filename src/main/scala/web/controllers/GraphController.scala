package web.controllers

import zio.*
import zio.http.*
import zio.json.*

import db.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait GraphController:
  def routes: Routes[Any, Response]

object GraphController:

  def routes: ZIO[GraphController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[GraphController](_.routes)

  val live: ZLayer[TaskRepository, Nothing, GraphController] =
    ZLayer.fromFunction(GraphControllerLive.apply)

final private case class GraphPayload(
  id: Long,
  taskRunId: Long,
  stepName: String,
  createdAt: String,
  source: String,
) derives JsonEncoder

final case class GraphControllerLive(repository: TaskRepository) extends GraphController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "graph"                      -> handler { (req: Request) =>
      req.queryParam("taskId") match
        case Some(taskIdRaw) =>
          ErrorHandlingMiddleware.fromPersistence {
            for
              taskId  <- parseLong(taskIdRaw, "taskId")
              reports <- repository.getReportsByTask(taskId)
              graphs   = reports.filter(report => report.reportType.trim.equalsIgnoreCase("graph"))
            yield html(HtmlViews.graphPage(taskId, graphs))
          }
        case None            =>
          ZIO.succeed(html(HtmlViews.graphHome))
    },
    Method.GET / "api" / "graph" / long("id") -> handler { (id: Long, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          report <- repository.getReport(id).someOrFail(PersistenceError.NotFound("task_reports", id))
          _      <- ensureGraph(report)
        yield Response.json(
          GraphPayload(
            id = report.id,
            taskRunId = report.taskRunId,
            stepName = report.stepName,
            createdAt = report.createdAt.toString,
            source = report.content,
          ).toJson
        )
      }
    },
  )

  private def ensureGraph(report: TaskReportRow): IO[PersistenceError, Unit] =
    if report.reportType.trim.equalsIgnoreCase("graph") then ZIO.unit
    else ZIO.fail(PersistenceError.QueryFailed("graph", s"Report ${report.id} is not a graph report"))

  private def parseLong(raw: String, field: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(raw.trim.toLong)
      .mapError(_ => PersistenceError.QueryFailed("graph", s"Invalid value for '$field': $raw"))

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
