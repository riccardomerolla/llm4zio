package workspace.control

import zio.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId }
import workspace.entity.{ GitError, RunStatus, WorkspaceRepository, WorkspaceRun }

enum MergeAgentError:
  case IssueNotFound(issueId: IssueId)
  case InvalidIssueState(issueId: IssueId, state: String)
  case RunNotFound(issueId: IssueId)
  case WorkspaceNotFound(workspaceId: String)
  case InvalidBaseBranch(repoPath: String, branch: String)
  case PersistenceFailure(operation: String, details: String)
  case GitFailure(error: GitError)

  def message: String =
    this match
      case IssueNotFound(issueId)                 => s"Issue not found: ${issueId.value}"
      case InvalidIssueState(issueId, state)      => s"Issue ${issueId.value} is not mergeable from state: $state"
      case RunNotFound(issueId)                   => s"No workspace run found for issue ${issueId.value}"
      case WorkspaceNotFound(workspaceId)         => s"Workspace not found: $workspaceId"
      case InvalidBaseBranch(repoPath, branch)    => s"Invalid base branch '$branch' for repository $repoPath"
      case PersistenceFailure(operation, details) => s"$operation failed: $details"
      case GitFailure(error)                      => error.toString

trait MergeAgentService:
  def enqueue(issueId: IssueId): UIO[Unit]
  def mergeOnce(issueId: IssueId): IO[MergeAgentError, Unit]

object MergeAgentService:
  final private case class IssueStatusPayload(issueId: String, status: String) derives JsonDecoder

  def enqueue(issueId: IssueId): URIO[MergeAgentService, Unit] =
    ZIO.serviceWithZIO[MergeAgentService](_.enqueue(issueId))

  def mergeOnce(issueId: IssueId): ZIO[MergeAgentService, MergeAgentError, Unit] =
    ZIO.serviceWithZIO[MergeAgentService](_.mergeOnce(issueId))

  val live: ZLayer[IssueRepository & WorkspaceRepository & GitService & ActivityHub, Nothing, MergeAgentService] =
    ZLayer.scoped {
      for
        issueRepository     <- ZIO.service[IssueRepository]
        workspaceRepository <- ZIO.service[WorkspaceRepository]
        gitService          <- ZIO.service[GitService]
        activityHub         <- ZIO.service[ActivityHub]
        queue               <- Queue.unbounded[IssueId]
        pending             <- Ref.Synchronized.make(Set.empty[IssueId])
        service              = MergeAgentServiceLive(
                                 issueRepository = issueRepository,
                                 workspaceRepository = workspaceRepository,
                                 gitService = gitService,
                                 activityHub = activityHub,
                                 queue = queue,
                                 pending = pending,
                               )
        _                   <- service.bootstrap.catchAll(logBootstrapError).forkScoped
        _                   <- service.listen.forkScoped
        _                   <- service.worker.forever.forkScoped
      yield service
    }

  private def logBootstrapError(error: PersistenceError): UIO[Unit] =
    ZIO.logWarning(s"Merge agent bootstrap failed: $error")

  private[workspace] def issueIdFromActivity(event: ActivityEvent): Option[IssueId] =
    payloadIssueId(event).orElse(summaryIssueId(event))

  private def payloadIssueId(event: ActivityEvent): Option[IssueId] =
    event.payload
      .flatMap(_.fromJson[IssueStatusPayload].toOption)
      .filter(payload => payload.status.trim.equalsIgnoreCase("merging"))
      .flatMap(payload => normalizeIssueId(payload.issueId))

  private def summaryIssueId(event: ActivityEvent): Option[IssueId] =
    val Pattern = """Issue #([^ ]+) moved to Merging""".r
    event.summary match
      case Pattern(rawId) => normalizeIssueId(rawId)
      case _              => None

  private def normalizeIssueId(raw: String): Option[IssueId] =
    Option(raw).map(_.trim).filter(_.nonEmpty).map(_.stripPrefix("#")).filter(_.nonEmpty).map(IssueId.apply)

final case class MergeAgentServiceLive(
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  gitService: GitService,
  activityHub: ActivityHub,
  queue: Queue[IssueId],
  pending: Ref.Synchronized[Set[IssueId]],
) extends MergeAgentService:

  override def enqueue(issueId: IssueId): UIO[Unit] =
    pending.modifyZIO { current =>
      if current.contains(issueId) then ZIO.succeed(((), current))
      else queue.offer(issueId).as(((), current + issueId))
    }

  override def mergeOnce(issueId: IssueId): IO[MergeAgentError, Unit] =
    for
      issue      <- loadIssue(issueId)
      _          <- ensureMerging(issue)
      run        <- resolveRun(issue)
      workspace  <- resolveWorkspace(run.workspaceId)
      branchInfo <- gitService.branchInfo(workspace.localPath).mapError(MergeAgentError.GitFailure.apply)
      baseBranch <- validateBaseBranch(workspace.localPath, branchInfo.current, branchInfo.isDetached)
      _          <- gitService
                      .checkout(workspace.localPath, baseBranch)
                      .mapError(MergeAgentError.GitFailure.apply)
      _          <- gitService
                      .mergeNoFastForward(
                        workspace.localPath,
                        run.branchName,
                        mergeCommitMessage(issue, run.branchName, baseBranch),
                      )
                      .mapError(MergeAgentError.GitFailure.apply)
      now        <- Clock.instant
      result      = s"Merged ${run.branchName} into $baseBranch"
      _          <- issueRepository
                      .append(IssueEvent.MarkedDone(issue.id, now, result, now))
                      .mapError(err => MergeAgentError.PersistenceFailure("mark_issue_done", err.toString))
    yield ()

  private[workspace] def bootstrap: IO[PersistenceError, Unit] =
    issueRepository
      .list(IssueFilter(states = Set(IssueStateTag.Merging), limit = Int.MaxValue))
      .flatMap(issues => ZIO.foreachDiscard(issues)(issue => enqueue(issue.id)))

  private[workspace] def listen: UIO[Unit] =
    activityHub.subscribe.flatMap(queue =>
      queue.take.flatMap(handleActivity).forever
    )

  private[workspace] def worker: UIO[Unit] =
    queue.take.flatMap { issueId =>
      mergeOnce(issueId)
        .tapError(error => publishFailure(issueId, error))
        .tap(_ => publishSuccess(issueId))
        .catchAll(error => ZIO.logWarning(s"Merge agent failed for issue ${issueId.value}: ${error.message}"))
        .ensuring(pending.update(_ - issueId))
        .unit
    }

  private def handleActivity(event: ActivityEvent): UIO[Unit] =
    if event.eventType == ActivityEventType.RunStateChanged then
      MergeAgentService.issueIdFromActivity(event) match
        case Some(issueId) => enqueue(issueId)
        case None          => ZIO.unit
    else ZIO.unit

  private def loadIssue(issueId: IssueId): IO[MergeAgentError, AgentIssue] =
    issueRepository
      .get(issueId)
      .mapError {
        case PersistenceError.NotFound(_, _) => MergeAgentError.IssueNotFound(issueId)
        case other                           => MergeAgentError.PersistenceFailure("load_issue", other.toString)
      }

  private def ensureMerging(issue: AgentIssue): IO[MergeAgentError, Unit] =
    issue.state match
      case _: IssueState.Merging => ZIO.unit
      case other                 => ZIO.fail(MergeAgentError.InvalidIssueState(issue.id, other.toString))

  private def resolveRun(issue: AgentIssue): IO[MergeAgentError, WorkspaceRun] =
    issue.runId match
      case Some(runId) =>
        workspaceRepository
          .getRun(runId.value)
          .mapError(err => MergeAgentError.PersistenceFailure("get_run", err.toString))
          .flatMap {
            case Some(run) => ZIO.succeed(run)
            case None      => fallbackRunLookup(issue)
          }
      case None        =>
        fallbackRunLookup(issue)

  private def fallbackRunLookup(issue: AgentIssue): IO[MergeAgentError, WorkspaceRun] =
    workspaceRepository
      .listRunsByIssueRef(s"#${issue.id.value}")
      .mapError(err => MergeAgentError.PersistenceFailure("list_runs_by_issue", err.toString))
      .flatMap { runs =>
        val preferred =
          runs.sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse)
            .find(_.status == RunStatus.Completed)
            .orElse(runs.sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse).headOption)
        ZIO.fromOption(preferred).orElseFail(MergeAgentError.RunNotFound(issue.id))
      }

  private def resolveWorkspace(workspaceId: String): IO[MergeAgentError, workspace.entity.Workspace] =
    workspaceRepository
      .get(workspaceId)
      .mapError(err => MergeAgentError.PersistenceFailure("get_workspace", err.toString))
      .flatMap(ws => ZIO.fromOption(ws).orElseFail(MergeAgentError.WorkspaceNotFound(workspaceId)))

  private def validateBaseBranch(repoPath: String, branch: String, detached: Boolean): IO[MergeAgentError, String] =
    val trimmed = Option(branch).map(_.trim).getOrElse("")
    if detached || trimmed.isEmpty || trimmed == "HEAD" then
      ZIO.fail(MergeAgentError.InvalidBaseBranch(repoPath, trimmed))
    else ZIO.succeed(trimmed)

  private def mergeCommitMessage(issue: AgentIssue, sourceBranch: String, baseBranch: String): String =
    s"Merge issue #${issue.id.value} (${issue.title}) from $sourceBranch into $baseBranch"

  private def publishSuccess(issueId: IssueId): UIO[Unit] =
    Clock.instant.flatMap(now =>
      activityHub.publish(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.RunCompleted,
          source = "merge-agent",
          summary = s"Issue #${issueId.value} merged successfully",
          payload = Some(s"""{"issueId":"${issueId.value}","status":"done"}"""),
          createdAt = now,
        )
      )
    )

  private def publishFailure(issueId: IssueId, error: MergeAgentError): UIO[Unit] =
    Clock.instant.flatMap(now =>
      activityHub.publish(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.RunFailed,
          source = "merge-agent",
          summary = s"Issue #${issueId.value} merge failed",
          payload = Some(
            s"""{"issueId":"${issueId.value}","status":"merging","error":${error.message.toJson}}"""
          ),
          createdAt = now,
        )
      )
    )
