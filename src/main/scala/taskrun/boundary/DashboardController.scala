package taskrun.boundary

import zio.*
import zio.http.*

import db.{ ChatRepository, PersistenceError, TaskRepository }
import orchestration.control.{ WorkflowService, WorkflowServiceError }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }

trait DashboardController:
  def routes: Routes[Any, Response]

object DashboardController:

  def routes: ZIO[DashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DashboardController](_.routes)

  val live: ZLayer[TaskRepository & WorkflowService & ChatRepository, Nothing, DashboardController] =
    ZLayer {
      for
        repository      <- ZIO.service[TaskRepository]
        workflowService <- ZIO.service[WorkflowService]
        chatRepository  <- ZIO.service[ChatRepository]
      yield DashboardControllerLive(
        repository = repository,
        workflowService = workflowService,
        activeSessionCount = chatRepository.listSessionContexts.map(_.length).orElseSucceed(0),
      )
    }

final case class DashboardControllerLive(
  repository: TaskRepository,
  workflowService: WorkflowService,
  activeSessionCount: UIO[Int] = ZIO.succeed(0),
) extends DashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / Root                       -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          runs          <- repository.listRuns(offset = 0, limit = 20)
          workflowCount <- workflowService
                             .listWorkflows
                             .map(_.length)
                             .mapError(workflowAsPersistence("listWorkflows"))
          sessionsCount <- activeSessionCount
        yield html(HtmlViews.dashboard(runs, workflowCount, sessionsCount))
      }
    },
    Method.GET / "api" / "tasks" / "recent" -> handler {
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
