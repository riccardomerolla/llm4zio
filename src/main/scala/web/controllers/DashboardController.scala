package web.controllers

import zio.*
import zio.http.*

import db.MigrationRepository
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait DashboardController:
  def routes: Routes[Any, Response]

object DashboardController:

  def routes: ZIO[DashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DashboardController](_.routes)

  val live: ZLayer[MigrationRepository, Nothing, DashboardController] =
    ZLayer.fromFunction(DashboardControllerLive.apply)

final case class DashboardControllerLive(
  repository: MigrationRepository
) extends DashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / Root                      -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        repository.listRuns(offset = 0, limit = 20).map(runs => html(HtmlViews.dashboard(runs)))
      }
    },
    Method.GET / "api" / "runs" / "recent" -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        repository.listRuns(offset = 0, limit = 10).map(runs => html(HtmlViews.recentRunsFragment(runs)))
      }
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
