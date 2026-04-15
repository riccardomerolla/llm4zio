package board.control

import zio.*

import activity.control.ActivityHub
import activity.entity.ActivityEventType
import board.entity.*
import governance.control.{ GovernanceEvaluationContext, GovernancePolicyService }
import governance.entity.{ GovernanceGate, GovernanceLifecycleAction, GovernanceLifecycleStage, GovernanceTransition }
import project.control.ProjectStorageService
import shared.ids.Ids.BoardIssueId
import workspace.control.{ GitService, WorkspaceRunService }
import workspace.entity.{ AssignRunRequest, GitError, WorkspaceRepository }

/** Central board orchestrator for issue lifecycle management.
  *
  * Extends focused sub-traits from board.entity so that consumers can depend on the narrower interface they actually
  * need (e.g. IssueDispatcher for dispatch-only consumers, IssueApprover for approval controllers).
  */
trait BoardOrchestrator extends IssueDispatcher with IssueApprover:
  def abortIssueRuns(workspaceId: String, issueId: BoardIssueId): IO[BoardError, Int]

object BoardOrchestrator:
  def dispatchCycle(workspacePath: String): ZIO[BoardOrchestrator, BoardError, DispatchResult] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.dispatchCycle(workspacePath))

  def completeIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    success: Boolean,
    details: String,
  ): ZIO[BoardOrchestrator, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.completeIssue(workspacePath, issueId, success, details))

  def assignIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    agentName: String,
  ): ZIO[BoardOrchestrator, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.assignIssue(workspacePath, issueId, agentName))

  def markIssueStarted(
    workspacePath: String,
    issueId: BoardIssueId,
    agentName: String,
    branchName: String,
  ): ZIO[BoardOrchestrator, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.markIssueStarted(workspacePath, issueId, agentName, branchName))

  def approveIssue(
    workspacePath: String,
    issueId: BoardIssueId,
  ): ZIO[BoardOrchestrator, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.approveIssue(workspacePath, issueId))

  def abortIssueRuns(
    workspaceId: String,
    issueId: BoardIssueId,
  ): ZIO[BoardOrchestrator, BoardError, Int] =
    ZIO.serviceWithZIO[BoardOrchestrator](_.abortIssueRuns(workspaceId, issueId))

  val live
    : ZLayer[
      BoardRepository & BoardDependencyResolver & WorkspaceRunService & WorkspaceRepository & GitService & ActivityHub &
        GovernancePolicyService & ProjectStorageService,
      Nothing,
      BoardOrchestrator,
    ] =
    ZLayer.scoped {
      for
        boardRepository   <- ZIO.service[BoardRepository]
        resolver          <- ZIO.service[BoardDependencyResolver]
        runService        <- ZIO.service[WorkspaceRunService]
        workspaceRepo     <- ZIO.service[WorkspaceRepository]
        gitService        <- ZIO.service[GitService]
        activityHub       <- ZIO.service[ActivityHub]
        governance        <- ZIO.service[GovernancePolicyService]
        projectStorageSvc <- ZIO.service[ProjectStorageService]
        service            = BoardOrchestratorLive(
                               boardRepository = boardRepository,
                               dependencyResolver = resolver,
                               workspaceRunService = runService,
                               workspaceRepository = workspaceRepo,
                               gitService = gitService,
                               activityHub = activityHub,
                               governancePolicyService = governance,
                               projectStorageService = projectStorageSvc,
                             )
        _                 <- service.listenForRunCompletion.forkScoped
      yield service
    }

final case class BoardOrchestratorLive(
  boardRepository: BoardRepository,
  dependencyResolver: BoardDependencyResolver,
  workspaceRunService: WorkspaceRunService,
  workspaceRepository: WorkspaceRepository,
  gitService: GitService,
  activityHub: ActivityHub,
  governancePolicyService: GovernancePolicyService,
  projectStorageService: ProjectStorageService,
) extends BoardOrchestrator:

  override def dispatchCycle(workspacePath: String): IO[BoardError, DispatchResult] =
    for
      workspace <- resolveWorkspaceByPath(workspacePath)
      _         <- ensureDefaultBranch(workspace)
      board     <- boardRepository.readBoard(workspacePath)
      ready     <- dependencyResolver.readyToDispatch(board)
      result    <- ZIO.foldLeft(ready)(DispatchResult(Nil, Nil)) { (acc, issue) =>
                     dispatchIssue(
                       workspacePath = workspacePath,
                       workspaceId = workspace.id,
                       issue = issue,
                       defaultAgent = workspace.defaultAgent,
                     )
                       .as(acc.copy(dispatchedIssueIds = acc.dispatchedIssueIds :+ issue.frontmatter.id))
                       .catchAll(err =>
                         ZIO.logWarning(
                           s"[board] dispatch failed for ${issue.frontmatter.id.value}: ${renderBoardError(err)}"
                         ).as(acc.copy(skippedIssueIds = acc.skippedIssueIds :+ issue.frontmatter.id))
                       )
                   }
    yield result

  override def completeIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    success: Boolean,
    details: String,
  ): IO[BoardError, Unit] =
    if success then completeSuccess(workspacePath, issueId, details)
    else completeFailure(workspacePath, issueId, details)

  override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
    for
      workspace <- resolveWorkspaceByPath(workspacePath)
      _         <- ensureDefaultBranch(workspace)
      issue     <- boardRepository.readIssue(workspacePath, issueId)
      _         <- ensureGovernanceAllows(
                     workspaceId = workspace.id,
                     issue = issue,
                     transition = GovernanceTransition(
                       from = GovernanceLifecycleStage.HumanReview,
                       to = GovernanceLifecycleStage.Done,
                       action = GovernanceLifecycleAction.Approve,
                     ),
                     humanApprovalGranted = true,
                   )
      _         <- ZIO
                     .fail(BoardError.ParseError(s"Issue '${issueId.value}' is not in Review"))
                     .unless(issue.column == BoardColumn.Review)
      branch    <- ZIO
                     .fromOption(issue.frontmatter.branchName.map(_.trim).filter(_.nonEmpty))
                     .orElseFail(BoardError.ParseError(s"Issue '${issueId.value}' has no branchName"))
      now       <- Clock.instant
      _         <- boardRepository.updateIssue(workspacePath, issueId, _.copy(transientState = TransientState.Merging(now)))
      _         <- (for
                     _    <- gitService
                               .mergeNoFastForward(
                                 workspace.localPath,
                                 branch,
                                 s"[board] Merge issue ${issueId.value}: ${issue.frontmatter.title}",
                               )
                               .mapError(mapGitError("git merge --no-ff"))
                     now2 <- Clock.instant
                     _    <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Done)
                     _    <- boardRepository.updateIssue(
                               workspacePath,
                               issueId,
                               _.copy(
                                 transientState = TransientState.None,
                                 completedAt = Some(now2),
                                 branchName = None,
                               ),
                             )
                     _    <- cleanupLatestRun(issueId)
                   yield ())
                     .catchAll { err =>
                       boardRepository
                         .updateIssue(
                           workspacePath,
                           issueId,
                           _.copy(
                             transientState = TransientState.None,
                             failureReason = Some(renderBoardError(err)),
                           ),
                         )
                         .ignore *> ZIO.fail(err)
                     }
    yield ()

  override def assignIssue(workspacePath: String, issueId: BoardIssueId, agentName: String): IO[BoardError, Unit] =
    for
      _   <- ensureDefaultBranch(workspacePath)
      now <- Clock.instant
      _   <- boardRepository.updateIssue(
               workspacePath,
               issueId,
               _.copy(
                 assignedAgent = Some(agentName),
                 transientState = TransientState.Assigned(agentName, now),
                 failureReason = None,
               ),
             )
    yield ()

  override def markIssueStarted(
    workspacePath: String,
    issueId: BoardIssueId,
    agentName: String,
    branchName: String,
  ): IO[BoardError, Unit] =
    for
      _   <- ensureDefaultBranch(workspacePath)
      now <- Clock.instant
      _   <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.InProgress)
      _   <- boardRepository.updateIssue(
               workspacePath,
               issueId,
               _.copy(
                 assignedAgent = Some(agentName),
                 transientState = TransientState.Assigned(agentName, now),
                 branchName = Option(branchName).map(_.trim).filter(_.nonEmpty),
                 failureReason = None,
               ),
             )
    yield ()

  override def abortIssueRuns(workspaceId: String, issueId: BoardIssueId): IO[BoardError, Int] =
    for
      direct    <- workspaceRepository
                     .listRunsByIssueRef(issueId.value)
                     .mapError(err => BoardError.ParseError(s"list runs by issue failed: $err"))
      hash      <- workspaceRepository
                     .listRunsByIssueRef(s"#${issueId.value}")
                     .mapError(err => BoardError.ParseError(s"list runs by issue failed: $err"))
      all        = (direct ++ hash).groupBy(_.id).values.map(_.head).toList
      active     = all.filter(r =>
                     r.status match
                       case workspace.entity.RunStatus.Pending | _: workspace.entity.RunStatus.Running => true
                       case _                                                                          => false
                   )
      cancelled <- ZIO.foldLeft(active)(0) { (count, run) =>
                     workspaceRunService
                       .cancelRun(run.id)
                       .as(count + 1)
                       .catchAll { err =>
                         ZIO.logWarning(s"[board] abort run ${run.id} failed: $err").as(count)
                       }
                   }
      now       <- Clock.instant
      _         <- ZIO
                     .when(cancelled > 0)(
                       activityHub.publish(
                         activity.entity.ActivityEvent(
                           id = shared.ids.Ids.EventId(java.util.UUID.randomUUID().toString),
                           eventType = ActivityEventType.RunStateChanged,
                           source = "board-orchestrator",
                           summary = s"Aborted $cancelled active run(s) for issue #${issueId.value} (moved to Backlog)",
                           payload = Some(active.map(r => s"run:${r.id} agent:${r.agentName}").mkString(", ")),
                           createdAt = now,
                         )
                       )
                     )
                     .ignore
    yield cancelled

  private[board] def listenForRunCompletion: UIO[Unit] =
    activityHub.subscribe.flatMap(queue =>
      queue.take.flatMap(handleActivityEvent).forever
    )

  private def handleActivityEvent(event: activity.entity.ActivityEvent): UIO[Unit] =
    event.eventType match
      case ActivityEventType.RunCompleted => completeFromRunEvent(event, success = true)
      case ActivityEventType.RunFailed    => completeFromRunEvent(event, success = false)
      case _                              => ZIO.unit

  private def completeFromRunEvent(event: activity.entity.ActivityEvent, success: Boolean): UIO[Unit] =
    event.runId match
      case None        => ZIO.unit
      case Some(runId) =>
        (for
          runOpt <- workspaceRepository
                      .getRun(runId.value)
                      .mapError(err => BoardError.ParseError(s"run lookup failed: $err"))
          _      <- runOpt match
                      case None      => ZIO.unit
                      case Some(run) =>
                        issueIdFromIssueRef(run.issueRef) match
                          case None          => ZIO.unit
                          case Some(issueId) =>
                            workspaceRepository
                              .get(run.workspaceId)
                              .mapError(err => BoardError.ParseError(s"workspace lookup failed: $err"))
                              .flatMap {
                                case None            => ZIO.unit
                                case Some(workspace) =>
                                  projectStorageService.projectRoot(workspace.projectId).flatMap { boardPath =>
                                    completeIssue(
                                      boardPath.toString,
                                      issueId,
                                      success = success,
                                      details = event.summary,
                                    ) *>
                                      dispatchCycle(boardPath.toString)
                                        .catchAll(err =>
                                          ZIO.logDebug(
                                            s"[board] post-completion dispatch cycle failed: ${renderBoardError(err)}"
                                          )
                                        )
                                        .unit
                                  }
                              }
        yield ()).catchAll(err => ZIO.logWarning(s"[board] completion from activity failed: ${renderBoardError(err)}"))

  private def dispatchIssue(
    workspacePath: String,
    workspaceId: String,
    issue: BoardIssue,
    defaultAgent: Option[String],
  ): IO[BoardError, Unit] =
    val agent =
      issue.frontmatter.assignedAgent.map(_.trim).filter(_.nonEmpty)
        .orElse(defaultAgent.map(_.trim).filter(_.nonEmpty))
        .getOrElse("code-agent")

    (for
      _   <- ensureGovernanceAllows(
               workspaceId = workspaceId,
               issue = issue,
               transition = GovernanceTransition(
                 from = GovernanceLifecycleStage.Todo,
                 to = GovernanceLifecycleStage.InProgress,
                 action = GovernanceLifecycleAction.Dispatch,
               ),
             )
      now <- Clock.instant
      _   <- boardRepository.updateIssue(
               workspacePath,
               issue.frontmatter.id,
               _.copy(
                 assignedAgent = Some(agent),
                 transientState = TransientState.Assigned(agent, now),
                 failureReason = None,
               ),
             )
      _   <- boardRepository.moveIssue(workspacePath, issue.frontmatter.id, BoardColumn.InProgress)
      run <- existingPendingRun(workspaceId, issue.frontmatter.id).flatMap {
               case Some(existingRun) => ZIO.succeed(existingRun)
               case None              =>
                 workspaceRunService
                   .assign(
                     workspaceId,
                     AssignRunRequest(
                       issueRef = issue.frontmatter.id.value,
                       prompt = issue.body,
                       agentName = agent,
                     ),
                   )
                   .mapError(err => BoardError.ConcurrencyConflict(s"workspace run assign failed: $err"))
             }
      _   <- boardRepository.updateIssue(
               workspacePath,
               issue.frontmatter.id,
               _.copy(branchName = Some(run.branchName)),
             )
    yield ()).catchAll { err =>
      dispatchCompensation(workspacePath, issue.frontmatter.id, renderBoardError(err)) *>
        ZIO.fail(err)
    }

  private def completeSuccess(workspacePath: String, issueId: BoardIssueId, details: String): IO[BoardError, Unit] =
    for
      workspace <- resolveWorkspaceByPath(workspacePath)
      issue     <- boardRepository.readIssue(workspacePath, issueId)
      _         <- ensureGovernanceAllows(
                     workspaceId = workspace.id,
                     issue = issue,
                     transition = GovernanceTransition(
                       from = GovernanceLifecycleStage.InProgress,
                       to = GovernanceLifecycleStage.HumanReview,
                       action = GovernanceLifecycleAction.CompleteWork,
                     ),
                   )
      _         <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Review)
      _         <- boardRepository.updateIssue(
                     workspacePath,
                     issueId,
                     fm =>
                       fm.copy(
                         transientState = TransientState.None,
                         failureReason = None,
                         proofOfWork = appendDetail(fm.proofOfWork, details),
                       ),
                   )
    yield ()

  private def completeFailure(workspacePath: String, issueId: BoardIssueId, details: String): IO[BoardError, Unit] =
    for
      _     <- ensureDefaultBranch(workspacePath)
      now   <- Clock.instant
      reason = Option(details).map(_.trim).filter(_.nonEmpty).getOrElse("Run failed")
      _     <- boardRepository.updateIssue(
                 workspacePath,
                 issueId,
                 _.copy(
                   transientState = TransientState.None,
                   failureReason = Some(reason),
                   completedAt = None,
                 ),
               )
    yield ()

  private def cleanupLatestRun(issueId: BoardIssueId): IO[BoardError, Unit] =
    for
      direct <- workspaceRepository
                  .listRunsByIssueRef(issueId.value)
                  .mapError(err => BoardError.ParseError(s"list runs failed: $err"))
      hash   <- workspaceRepository
                  .listRunsByIssueRef(s"#${issueId.value}")
                  .mapError(err => BoardError.ParseError(s"list runs failed: $err"))
      all     = (direct ++ hash).groupBy(_.id).values.map(_.head).toList
      latest  = all.sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse).headOption
      _      <- ZIO.when(latest.isDefined)(workspaceRunService.cleanupAfterSuccessfulMerge(latest.get.id))
    yield ()

  private def existingPendingRun(workspaceId: String, issueId: BoardIssueId)
    : IO[BoardError, Option[workspace.entity.WorkspaceRun]] =
    workspaceRepository
      .listRuns(workspaceId)
      .mapError(err => BoardError.ParseError(s"list runs failed: $err"))
      .map(
        _.filter(run => issueRefMatches(run.issueRef, issueId) && run.status == workspace.entity.RunStatus.Pending)
          .sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse)
          .headOption
      )

  private def resolveWorkspaceByPath(workspacePath: String): IO[BoardError, workspace.entity.Workspace] =
    workspaceRepository
      .list
      .mapError(err => BoardError.ParseError(s"workspace list failed: $err"))
      .flatMap { workspaces =>
        ZIO
          .foreach(workspaces)(ws =>
            projectStorageService.projectRoot(ws.projectId).map(p => (ws, p.toString))
          )
          .flatMap { pairs =>
            ZIO.fromOption(pairs.find(_._2 == workspacePath).map(_._1))
              .orElseFail(BoardError.BoardNotFound(workspacePath))
          }
      }

  private def ensureDefaultBranch(workspacePath: String): IO[BoardError, Unit] =
    resolveWorkspaceByPath(workspacePath).flatMap(ensureDefaultBranch)

  private def ensureDefaultBranch(ws: workspace.entity.Workspace): IO[BoardError, Unit] =
    val targetBranch = workspace.entity.Workspace.normalizeDefaultBranch(ws.defaultBranch)
    gitService
      .branchInfo(ws.localPath)
      .mapError(mapGitError("git branch --show-current"))
      .flatMap { info =>
        if !info.isDetached && info.current == targetBranch then ZIO.unit
        else
          ZIO.fail(
            BoardError.ConcurrencyConflict(
              s"Board mutations are allowed only on '$targetBranch' (current='${info.current}', detached=${info.isDetached})"
            )
          )
      }

  private def dispatchCompensation(workspacePath: String, issueId: BoardIssueId, reason: String): UIO[Unit] =
    val fallbackReason =
      Option(reason).map(_.trim).filter(_.nonEmpty).getOrElse("dispatch failed")

    for
      _ <- boardRepository.moveIssue(workspacePath, issueId, BoardColumn.Todo).ignore
      _ <- boardRepository
             .updateIssue(
               workspacePath,
               issueId,
               _.copy(
                 transientState = TransientState.None,
                 failureReason = Some(fallbackReason),
                 branchName = None,
               ),
             )
             .ignore
    yield ()

  private def issueIdFromIssueRef(issueRef: String): Option[BoardIssueId] =
    BoardIssueId.fromString(issueRef.trim.stripPrefix("#")).toOption

  private def issueRefMatches(issueRef: String, issueId: BoardIssueId): Boolean =
    issueRef.trim.stripPrefix("#") == issueId.value

  private def appendDetail(existing: List[String], details: String): List[String] =
    Option(details).map(_.trim).filter(_.nonEmpty) match
      case Some(detail) => existing :+ detail
      case None         => existing

  private def mapGitError(operation: String)(error: GitError): BoardError =
    BoardError.GitOperationFailed(operation, error.toString)

  private def renderBoardError(error: BoardError): String =
    error match
      case BoardError.BoardNotFound(workspacePath)       => s"board not found: $workspacePath"
      case BoardError.IssueNotFound(issueId)             => s"issue not found: $issueId"
      case BoardError.IssueAlreadyExists(issueId)        => s"issue already exists: $issueId"
      case BoardError.InvalidColumn(value)               => s"invalid column: $value"
      case BoardError.ParseError(message)                => s"parse error: $message"
      case BoardError.WriteError(path, message)          => s"write error at $path: $message"
      case BoardError.GitOperationFailed(operation, msg) => s"$operation failed: $msg"
      case BoardError.DependencyCycle(issueIds)          => s"dependency cycle: ${issueIds.mkString(",")}"
      case BoardError.ConcurrencyConflict(message)       => s"concurrency conflict: $message"

  private def ensureGovernanceAllows(
    workspaceId: String,
    issue: BoardIssue,
    transition: GovernanceTransition,
    humanApprovalGranted: Boolean = false,
  ): IO[BoardError, Unit] =
    for
      decision <- governancePolicyService
                    .evaluateForWorkspace(
                      workspaceId,
                      GovernanceEvaluationContext(
                        issueType = "task",
                        transition = transition,
                        satisfiedGates = satisfiedGates(issue, humanApprovalGranted),
                        tags = issue.frontmatter.tags.toSet,
                        humanApprovalGranted = humanApprovalGranted,
                      ),
                    )
                    .mapError(err => BoardError.ParseError(s"governance lookup failed: $err"))
      _        <- ZIO
                    .fail(
                      BoardError.ConcurrencyConflict(
                        decision.reason.getOrElse("governance policy denied transition")
                      )
                    )
                    .unless(decision.allowed)
    yield ()

  private def satisfiedGates(issue: BoardIssue, humanApprovalGranted: Boolean): Set[GovernanceGate] =
    val proofOfWorkGate = Option.when(issue.frontmatter.proofOfWork.nonEmpty)(GovernanceGate.ProofOfWork).toSet
    val humanGate       = Option.when(humanApprovalGranted)(GovernanceGate.HumanApproval).toSet
    proofOfWorkGate ++ humanGate
