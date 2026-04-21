package workspace.control

import zio.*
import zio.json.*

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import issues.entity.*
import orchestration.control.WorkReportEventBus
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId, TaskRunId }
import taskrun.entity.{ CiStatus, TaskRunEvent }
import workspace.entity.{ GitError, RunStatus, WorkspaceRepository, WorkspaceRun, WorkspaceRunService }

enum MergeAgentError:
  case IssueNotFound(issueId: IssueId)
  case InvalidIssueState(issueId: IssueId, state: String)
  case RunNotFound(issueId: IssueId)
  case WorkspaceNotFound(workspaceId: String)
  case InvalidBaseBranch(repoPath: String, branch: String)
  case CiCommandMissing(workspaceId: String)
  case CiVerificationFailed(details: String)
  case PersistenceFailure(operation: String, details: String)
  case GitFailure(error: GitError)

  def message: String =
    this match
      case IssueNotFound(issueId)                 => s"Issue not found: ${issueId.value}"
      case InvalidIssueState(issueId, state)      => s"Issue ${issueId.value} is not mergeable from state: $state"
      case RunNotFound(issueId)                   => s"No workspace run found for issue ${issueId.value}"
      case WorkspaceNotFound(workspaceId)         => s"Workspace not found: $workspaceId"
      case InvalidBaseBranch(repoPath, branch)    => s"Invalid base branch '$branch' for repository $repoPath"
      case CiCommandMissing(workspaceId)          =>
        s"CI verification required but no CI command configured for workspace $workspaceId"
      case CiVerificationFailed(details)          => s"CI verification failed: $details"
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

  val live
    : ZLayer[
      IssueRepository & WorkspaceRepository & GitService & ActivityHub & ConfigRepository & WorkReportEventBus &
        WorkspaceRunService,
      Nothing,
      MergeAgentService,
    ] =
    ZLayer.scoped {
      for
        issueRepository     <- ZIO.service[IssueRepository]
        workspaceRepository <- ZIO.service[WorkspaceRepository]
        gitService          <- ZIO.service[GitService]
        activityHub         <- ZIO.service[ActivityHub]
        configRepository    <- ZIO.service[ConfigRepository]
        workReportEventBus  <- ZIO.service[WorkReportEventBus]
        workspaceRunService <- ZIO.service[WorkspaceRunService]
        queue               <- Queue.unbounded[IssueId]
        pending             <- Ref.Synchronized.make(Set.empty[IssueId])
        service              = MergeAgentServiceLive(
                                 issueRepository = issueRepository,
                                 workspaceRepository = workspaceRepository,
                                 gitService = gitService,
                                 activityHub = activityHub,
                                 configRepository = configRepository,
                                 workReportEventBus = workReportEventBus,
                                 cleanupMergedRun = workspaceRunService.cleanupAfterSuccessfulMerge,
                                 queue = queue,
                                 pending = pending,
                                 commandRunner = (argv, cwd) => CliAgentRunner.runProcess(argv, cwd),
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
  configRepository: ConfigRepository,
  workReportEventBus: WorkReportEventBus,
  cleanupMergedRun: String => UIO[Unit] = _ => ZIO.unit,
  queue: Queue[IssueId],
  pending: Ref.Synchronized[Set[IssueId]],
  commandRunner: (List[String], String) => Task[(List[String], Int)],
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
      _          <- mergeIntoBase(issue, run, workspace.id, workspace.localPath, baseBranch)
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
        .tapError(error => publishFailureUnlessConflict(issueId, error))
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
          runs.sortBy(_.updatedAt.toEpochMilli)(using Ordering.Long.reverse)
            .find(_.status == RunStatus.Completed)
            .orElse(runs.sortBy(_.updatedAt.toEpochMilli)(using Ordering.Long.reverse).headOption)
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

  private def mergeIntoBase(
    issue: AgentIssue,
    run: WorkspaceRun,
    workspaceId: String,
    repoPath: String,
    baseBranch: String,
  ): IO[MergeAgentError, Unit] =
    for
      attemptedAt <- Clock.instant
      _           <- issueRepository
                       .append(
                         IssueEvent.MergeAttempted(
                           issueId = issue.id,
                           sourceBranch = run.branchName,
                           targetBranch = baseBranch,
                           attemptedAt = attemptedAt,
                           occurredAt = attemptedAt,
                         )
                       )
                       .mapError(err => MergeAgentError.PersistenceFailure("record_merge_attempt", err.toString))
      _           <- gitService
                       .checkout(repoPath, baseBranch)
                       .mapError(MergeAgentError.GitFailure.apply)
      _           <- gitService
                       .mergeNoFastForward(
                         repoPath,
                         run.branchName,
                         mergeCommitMessage(issue, run.branchName, baseBranch),
                       )
                       .catchAll {
                         case mergeFailure: GitError.CommandFailed =>
                           handleMergeConflict(issue, repoPath, mergeFailure)
                         case other                                =>
                           ZIO.fail(MergeAgentError.GitFailure(other))
                       }
      _           <- recordMergeSuccess(issue, repoPath)
      _           <- verifyCiIfRequired(issue, run, workspaceId, repoPath)
      _           <- markIssueDone(issue, run.branchName, baseBranch)
      _           <- cleanupMergedRun(run.id)
    yield ()

  private def handleMergeConflict(
    issue: AgentIssue,
    repoPath: String,
    mergeFailure: GitError.CommandFailed,
  ): IO[MergeAgentError, Unit] =
    for
      files <- gitService.conflictedFiles(repoPath).orElseSucceed(Nil)
      _     <- gitService.mergeAbort(repoPath).orElseSucceed(())
      now   <- Clock.instant
      _     <- issueRepository
                 .append(
                   IssueEvent.MergeFailed(
                     issueId = issue.id,
                     conflictFiles = files,
                     failedAt = now,
                     occurredAt = now,
                   )
                 )
                 .mapError(err => MergeAgentError.PersistenceFailure("record_merge_failed", err.toString))
      _     <- issueRepository
                 .append(
                   IssueEvent.MergeConflictRecorded(
                     issueId = issue.id,
                     conflictingFiles = files,
                     detectedAt = now,
                     occurredAt = now,
                   )
                 )
                 .mapError(err => MergeAgentError.PersistenceFailure("record_merge_conflict", err.toString))
      _     <- issueRepository
                 .append(
                   IssueEvent.MovedToRework(
                     issueId = issue.id,
                     movedAt = now,
                     reason = mergeConflictReason(files),
                     occurredAt = now,
                   )
                 )
                 .mapError(err => MergeAgentError.PersistenceFailure("move_issue_to_rework", err.toString))
      _     <- publishMergeConflict(issue.id, files)
      _     <- ZIO.fail(MergeAgentError.GitFailure(mergeFailure))
    yield ()

  private def recordMergeSuccess(issue: AgentIssue, repoPath: String): IO[MergeAgentError, Unit] =
    for
      commitSha <- gitService.headSha(repoPath).mapError(MergeAgentError.GitFailure.apply)
      diffStat  <- gitService.showDiffStat(repoPath, commitSha).mapError(MergeAgentError.GitFailure.apply)
      mergedAt  <- Clock.instant
      summary    = summarizeDiffStat(diffStat)
      _         <- issueRepository
                     .append(
                       IssueEvent.MergeSucceeded(
                         issueId = issue.id,
                         commitSha = commitSha,
                         mergedAt = mergedAt,
                         filesChanged = summary.filesChanged,
                         insertions = summary.insertions,
                         deletions = summary.deletions,
                         occurredAt = mergedAt,
                       )
                     )
                     .mapError(err => MergeAgentError.PersistenceFailure("record_merge_succeeded", err.toString))
    yield ()

  private def markIssueDone(
    issue: AgentIssue,
    sourceBranch: String,
    baseBranch: String,
  ): IO[MergeAgentError, Unit] =
    for
      now   <- Clock.instant
      result = s"Merged $sourceBranch into $baseBranch"
      _     <- issueRepository
                 .append(IssueEvent.MarkedDone(issue.id, now, result, now))
                 .mapError(err => MergeAgentError.PersistenceFailure("mark_issue_done", err.toString))
    yield ()

  private def mergeConflictReason(files: List[String]): String =
    val summary = files match
      case Nil => "unknown files"
      case xs  => xs.mkString(", ")
    s"merge conflict: $summary"

  private def verifyCiIfRequired(
    issue: AgentIssue,
    run: WorkspaceRun,
    workspaceId: String,
    repoPath: String,
  ): IO[MergeAgentError, Unit] =
    for
      config <- loadCiVerificationConfig(workspaceId)
      _      <- if !config.requireCi then ZIO.unit
                else
                  config.command match
                    case Some(command) => runCiVerification(issue, run, repoPath, command)
                    case None          => ZIO.fail(MergeAgentError.CiCommandMissing(workspaceId))
    yield ()

  private def loadCiVerificationConfig(workspaceId: String): IO[MergeAgentError, CiVerificationConfig] =
    for
      requireCi <- settingValue(s"workspace.$workspaceId.mergePolicy.requireCi")
                     .flatMap {
                       case Some(value) => ZIO.succeed(parseBoolean(value))
                       case None        => settingValue("mergePolicy.requireCi").map(_.exists(parseBoolean))
                     }
      command   <- settingValue(s"workspace.$workspaceId.mergePolicy.ciCommand")
                     .flatMap {
                       case some @ Some(_) => ZIO.succeed(some)
                       case None           => settingValue("mergePolicy.ciCommand")
                     }
    yield CiVerificationConfig(requireCi = requireCi, command = command)

  private def settingValue(key: String): IO[MergeAgentError, Option[String]] =
    configRepository
      .getSetting(key)
      .mapError(err => MergeAgentError.PersistenceFailure(s"get_setting:$key", err.toString))
      .map(_.map(_.value.trim).filter(_.nonEmpty))

  private def parseBoolean(value: String): Boolean =
    value.trim.equalsIgnoreCase("true") || value.trim == "1" || value.trim.equalsIgnoreCase("yes")

  private def runCiVerification(
    issue: AgentIssue,
    run: WorkspaceRun,
    repoPath: String,
    command: String,
  ): IO[MergeAgentError, Unit] =
    for
      startedAt    <- Clock.instant
      _            <- publishCiStatus(issue, run, CiStatus.Running, startedAt)
      result       <- runShellCommand(command, repoPath)
      (lines, code) = result
      parsedStatus  =
        if code == 0 then normalizePassedStatus(ProofOfWorkExtractor.parseCiStatus(lines)) else CiStatus.Failed
      finishedAt   <- Clock.instant
      details       = lines.map(_.trim).filter(_.nonEmpty).mkString(" | ")
      _            <- issueRepository
                        .append(
                          IssueEvent.CiVerificationResult(
                            issueId = issue.id,
                            passed = parsedStatus == CiStatus.Passed,
                            details = details,
                            checkedAt = finishedAt,
                            occurredAt = finishedAt,
                          )
                        )
                        .mapError(err => MergeAgentError.PersistenceFailure("record_ci_verification", err.toString))
      _            <- publishCiStatus(issue, run, parsedStatus, finishedAt)
      _            <- parsedStatus match
                        case CiStatus.Passed =>
                          ZIO.unit
                        case _               =>
                          handleCiFailure(issue, lines, finishedAt)
    yield ()

  private def runShellCommand(command: String, cwd: String): IO[MergeAgentError, (List[String], Int)] =
    commandRunner(shellArgv(command), cwd)
      .mapError(err => MergeAgentError.PersistenceFailure("ci_command", err.getMessage))

  private def shellArgv(command: String): List[String] =
    if HostPlatform.isWindows() then List("cmd", "/c", command)
    else List("sh", "-lc", command)

  private def normalizePassedStatus(status: CiStatus): CiStatus =
    status match
      case CiStatus.Failed => CiStatus.Failed
      case _               => CiStatus.Passed

  private def publishCiStatus(
    issue: AgentIssue,
    run: WorkspaceRun,
    status: CiStatus,
    at: java.time.Instant,
  ): UIO[Unit] =
    val runId = issue.runId.getOrElse(TaskRunId(run.id))
    workReportEventBus.publishTaskRun(TaskRunEvent.CiStatusUpdated(runId, status, at))

  private def handleCiFailure(
    issue: AgentIssue,
    lines: List[String],
    now: java.time.Instant,
  ): IO[MergeAgentError, Unit] =
    val details = ciFailureDetails(lines)
    issueRepository
      .append(
        IssueEvent.MovedToRework(
          issueId = issue.id,
          movedAt = now,
          reason = s"CI verification failed: $details",
          occurredAt = now,
        )
      )
      .mapError(err => MergeAgentError.PersistenceFailure("move_issue_to_rework_ci", err.toString)) *>
      ZIO.fail(MergeAgentError.CiVerificationFailed(details))

  private def ciFailureDetails(lines: List[String]): String =
    lines.map(_.trim).filter(_.nonEmpty).takeRight(8) match
      case Nil => "command exited with errors"
      case xs  => xs.mkString(" | ")

  private def summarizeDiffStat(diffStat: workspace.entity.GitDiffStat): workspace.entity.GitChangeSummary =
    workspace.entity.GitChangeSummary(
      filesChanged = diffStat.files.size,
      insertions = diffStat.files.map(_.additions).sum,
      deletions = diffStat.files.map(_.deletions).sum,
    )

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

  private def publishFailureUnlessConflict(issueId: IssueId, error: MergeAgentError): UIO[Unit] =
    error match
      case MergeAgentError.GitFailure(GitError.CommandFailed(command, _)) if command.startsWith("git merge") =>
        ZIO.unit
      case _                                                                                                 =>
        publishFailure(issueId, error)

  private def publishMergeConflict(issueId: IssueId, files: List[String]): UIO[Unit] =
    Clock.instant.flatMap(now =>
      activityHub.publish(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.MergeConflict,
          source = "merge-agent",
          summary = s"Issue #${issueId.value} encountered a merge conflict",
          payload =
            Some(
              s"""{"issueId":"${issueId.value}","status":"rework","conflictingFiles":${files.toJson}}"""
            ),
          createdAt = now,
        )
      )
    )

final private case class CiVerificationConfig(
  requireCi: Boolean,
  command: Option[String],
)
