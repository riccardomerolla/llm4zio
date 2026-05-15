package taskrun.boundary

import zio.*
import zio.http.*

import _root_.config.entity.ConfigRepository
import activity.entity.ActivityRepository
import issues.entity.{ IssueFilter, IssueRepository, IssueStateTag }
import shared.errors.PersistenceError
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }
import taskrun.entity.TaskRepository

trait DashboardController:
  def routes: Routes[Any, Response]

object DashboardController:

  def routes: ZIO[DashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DashboardController](_.routes)

  val live: ZLayer[TaskRepository & IssueRepository & ActivityRepository & ConfigRepository, Nothing, DashboardController] =
    ZLayer {
      for
        repository      <- ZIO.service[TaskRepository]
        issueRepository <- ZIO.service[IssueRepository]
        activityRepo    <- ZIO.service[ActivityRepository]
        configRepo      <- ZIO.service[ConfigRepository]
      yield DashboardControllerLive(
        repository = repository,
        issueRepository = issueRepository,
        activityRepository = activityRepo,
        configRepository = configRepo,
      )
    }

final case class DashboardControllerLive(
  repository: TaskRepository,
  issueRepository: IssueRepository,
  activityRepository: ActivityRepository,
  configRepository: ConfigRepository,
) extends DashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / Root                       -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        firstRunRedirect.flatMap {
          case Some(redirect) => ZIO.succeed(redirect)
          case None           => renderDashboard
        }
      }
    },
    Method.GET / "api" / "tasks" / "recent" -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        repository.listRuns(offset = 0, limit = 10).map(runs => html(HtmlViews.recentRunsFragment(runs)))
      }
    },
  )

  /** Phase 5 R10 polish: fresh users land on the onboarding wizard.
    * Returns Some(redirect) when `onboarding.completedAt` is empty, None
    * otherwise. ErrorHandlingMiddleware short-circuits any config-read
    * failure (which is fine — if config is unavailable, we render the
    * dashboard rather than block the whole UI).
    */
  private def firstRunRedirect: IO[PersistenceError, Option[Response]] =
    configRepository
      .getSetting("onboarding.completedAt")
      .map(_.flatMap(row => Option(row.value)).map(_.trim).filter(_.nonEmpty))
      .map {
        case None | Some("") => Some(Response.redirect(URL.decode("/onboarding").toOption.get))
        case _               => None
      }

  private def renderDashboard: IO[PersistenceError, Response] =
    for
      now              <- Clock.instant
      allIssues        <- issueRepository.list(IssueFilter(limit = Int.MaxValue)).mapError(mapIssueRepoError)
      recentEvents     <- activityRepository.listEvents(limit = 5)
      stateCountByType  = allIssues
                            .groupBy(issue => IssueStateTag.fromState(issue.state))
                            .view
                            .mapValues(_.size)
                            .toMap
      completedLast24h  = allIssues.count {
                            case issue
                                 if IssueStateTag.fromState(issue.state) == IssueStateTag.Done ||
                                 IssueStateTag.fromState(issue.state) == IssueStateTag.Completed =>
                              issue.state match
                                case issues.entity.IssueState.Done(doneAt, _)              =>
                                  doneAt.isAfter(now.minus(24.hours))
                                case issues.entity.IssueState.Completed(_, completedAt, _) =>
                                  completedAt.isAfter(now.minus(24.hours))
                                case _                                                     => false
                            case _ => false
                          }
      failedLast24h     = allIssues.count {
                            case issue
                                 if IssueStateTag.fromState(issue.state) == IssueStateTag.Rework ||
                                 IssueStateTag.fromState(issue.state) == IssueStateTag.Failed =>
                              issue.state match
                                case issues.entity.IssueState.Rework(reworkAt, _)    =>
                                  reworkAt.isAfter(now.minus(24.hours))
                                case issues.entity.IssueState.Failed(_, failedAt, _) =>
                                  failedAt.isAfter(now.minus(24.hours))
                                case _                                               => false
                            case _ => false
                          }
      throughputPerDay  = (completedLast24h + failedLast24h).toDouble
      summary           = CommandCenterView.PipelineSummary(
                            open = stateCountByType.getOrElse(IssueStateTag.Backlog, 0) +
                              stateCountByType.getOrElse(IssueStateTag.Open, 0),
                            claimed = stateCountByType.getOrElse(IssueStateTag.Todo, 0) +
                              stateCountByType.getOrElse(IssueStateTag.Assigned, 0),
                            running = stateCountByType.getOrElse(IssueStateTag.InProgress, 0),
                            completed = stateCountByType.getOrElse(IssueStateTag.Done, 0) +
                              stateCountByType.getOrElse(IssueStateTag.Completed, 0),
                            failed = stateCountByType.getOrElse(IssueStateTag.Rework, 0) +
                              stateCountByType.getOrElse(IssueStateTag.Failed, 0),
                            throughputPerDay = throughputPerDay,
                          )
    yield html(HtmlViews.dashboard(summary, recentEvents))

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def mapIssueRepoError(error: shared.errors.PersistenceError): PersistenceError =
    error match
      case shared.errors.PersistenceError.NotFound(entity, id)             =>
        PersistenceError.QueryFailed(entity, s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)           =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, err) =>
        PersistenceError.QueryFailed(entity, err)
      case shared.errors.PersistenceError.StoreUnavailable(msg)            =>
        PersistenceError.QueryFailed("issue_store", msg)
