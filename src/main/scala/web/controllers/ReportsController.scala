package web.controllers

import zio.*
import zio.http.*

import db.*
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait ReportsController:
  def routes: Routes[Any, Response]

object ReportsController:

  def routes: ZIO[ReportsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ReportsController](_.routes)

  val live: ZLayer[TaskRepository, Nothing, ReportsController] =
    ZLayer.fromFunction(ReportsControllerLive.apply)

final case class ReportsControllerLive(repository: TaskRepository) extends ReportsController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "reports"              -> handler { (req: Request) =>
      req.queryParam("taskId") match
        case Some(taskIdRaw) =>
          ErrorHandlingMiddleware.fromPersistence {
            for
              taskId  <- parseLong(taskIdRaw, "taskId")
              reports <- repository.getReportsByTask(taskId)
            yield html(HtmlViews.reportsList(taskId, reports))
          }
        case None            =>
          ZIO.succeed(html(HtmlViews.reportsHome))
    },
    Method.GET / "reports" / long("id") -> handler { (id: Long, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          report <- repository.getReport(id).someOrFail(PersistenceError.NotFound("task_reports", id))
        yield html(HtmlViews.reportDetail(report))
      }
    },
  )

  private def parseLong(raw: String, field: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(raw.trim.toLong)
      .mapError(_ => PersistenceError.QueryFailed("reports", s"Invalid value for '$field': $raw"))

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
