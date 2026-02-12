package web.controllers

import zio.*
import zio.http.*

import db.{ MigrationRepository, PersistenceError }
import orchestration.{ WorkflowService, WorkflowServiceError }
import web.ErrorHandlingMiddleware
import web.views.HtmlViews

trait DashboardController:
  def routes: Routes[Any, Response]

object DashboardController:

  def routes: ZIO[DashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DashboardController](_.routes)

  val live: ZLayer[MigrationRepository & WorkflowService, Nothing, DashboardController] =
    ZLayer.fromFunction(DashboardControllerLive.apply)

final case class DashboardControllerLive(
  repository: MigrationRepository,
  workflowService: WorkflowService,
) extends DashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / Root                      -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          runs          <- repository.listRuns(offset = 0, limit = 20)
          workflowCount <- workflowService
                             .listWorkflows
                             .map(_.length)
                             .mapError(workflowAsPersistence("listWorkflows"))
        yield html(HtmlViews.dashboard(runs, workflowCount))
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

  private def workflowAsPersistence(action: String)(error: WorkflowServiceError): PersistenceError =
    error match
      case WorkflowServiceError.PersistenceFailed(err)             => err
      case WorkflowServiceError.ValidationFailed(errors)           =>
        PersistenceError.QueryFailed(action, errors.mkString("; "))
      case WorkflowServiceError.StepsDecodingFailed(workflow, why) =>
        PersistenceError.QueryFailed(action, s"Invalid workflow '$workflow': $why")
