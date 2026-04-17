package issues.control

import zio.*

import analysis.entity.AnalysisRepository
import board.control.BoardOrchestrator
import board.entity.BoardError
import issues.boundary.IssueControllerSupport
import issues.entity.api.*
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueRepository, IssueState }
import orchestration.control.IssueAssignmentOrchestrator
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, IssueId }
import workspace.entity.{ AssignRunRequest, WorkspaceRepository, WorkspaceRunService }

/** Bulk operations over issues — extracted from [[issues.boundary.IssueController]] in phase 4F.3.
  *
  * Filed under `orchestration-domain` rather than `issues-domain` because it depends on
  * [[IssueAssignmentOrchestrator]]; `issuesDomain` is a dep of `orchestrationDomain` and cannot back-edge. The
  * same-package `issues.control` is preserved for ergonomic imports across the two sbt modules.
  */
trait IssueBulkService:
  def bulkAssign(request: BulkIssueAssignRequest): IO[PersistenceError, BulkIssueOperationResponse]
  def bulkUpdateStatus(request: BulkIssueStatusRequest): IO[PersistenceError, BulkIssueOperationResponse]
  def bulkUpdateTags(request: BulkIssueTagsRequest): IO[PersistenceError, BulkIssueOperationResponse]
  def bulkDelete(request: BulkIssueDeleteRequest): IO[PersistenceError, BulkIssueOperationResponse]

object IssueBulkService:

  val live: ZLayer[
    IssueRepository & WorkspaceRepository & WorkspaceRunService & IssueAssignmentOrchestrator & AnalysisRepository &
      BoardOrchestrator & ProjectStorageService,
    Nothing,
    IssueBulkService,
  ] = ZLayer.fromFunction(IssueBulkServiceLive.apply)

final case class IssueBulkServiceLive(
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  workspaceRunService: WorkspaceRunService,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
  analysisRepository: AnalysisRepository,
  boardOrchestrator: BoardOrchestrator,
  projectStorageService: ProjectStorageService,
) extends IssueBulkService:

  import IssueControllerSupport.{ ensureTransitionAllowed, executionPrompt, statusToEvents as supportStatusToEvents }

  override def bulkAssign(request: BulkIssueAssignRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      _        <- ensureWorkspaceExists(request.workspaceId)
      results  <- ZIO.foreach(issueIds) { issueId =>
                    (for
                      issue <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                      now   <- Clock.instant
                      _     <- issueRepository
                                 .append(
                                   IssueEvent.WorkspaceLinked(
                                     issueId = IssueId(issueId),
                                     workspaceId = request.workspaceId,
                                     occurredAt = now,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                      _     <- issueAssignmentOrchestrator.assignIssue(
                                 issueId,
                                 request.agentId,
                                 skipConversationBootstrap = true,
                               )
                      _     <- assignWorkspaceRunAndMarkStarted(
                                 issue = issue,
                                 workspaceId = request.workspaceId,
                                 agentName = request.agentId,
                               )
                    yield ()).either
                  }
    yield toBulkResponse(issueIds.size, results)

  override def bulkUpdateStatus(request: BulkIssueStatusRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      results  <- ZIO.foreach(issueIds) { issueId =>
                    (for
                      issue        <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                      now          <- Clock.instant
                      fallbackAgent = request.agentName
                                        .map(_.trim)
                                        .filter(_.nonEmpty)
                                        .orElse(assignedAgentFromState(issue.state))
                                        .getOrElse("bulk")
                      _            <- ensureTransitionAllowed(issue.state, request.status, issueId)
                      events       <- statusToEventsEnriched(
                                        issue,
                                        IssueStatusUpdateRequest(
                                          status = request.status,
                                          agentName = request.agentName,
                                          reason = request.reason,
                                          resultData = request.resultData,
                                        ),
                                        fallbackAgent,
                                        now,
                                      )
                      _            <- ZIO.foreachDiscard(events)(issueRepository.append(_).mapError(mapIssueRepoError))
                    yield ()).either
                  }
    yield toBulkResponse(issueIds.size, results)

  override def bulkUpdateTags(request: BulkIssueTagsRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds    <- validateIssueIds(request.issueIds)
      tagsToAdd    = request.addTags.map(_.trim).filter(_.nonEmpty)
      tagsToRemove = request.removeTags.map(_.trim).filter(_.nonEmpty).toSet
      results     <- ZIO.foreach(issueIds) { issueId =>
                       (for
                         issue   <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                         now     <- Clock.instant
                         existing = issue.tags.map(_.trim).filter(_.nonEmpty)
                         merged   = (existing.filterNot(t => tagsToRemove.contains(t)) ++ tagsToAdd).distinct
                         _       <- issueRepository
                                      .append(IssueEvent.TagsUpdated(IssueId(issueId), merged, now))
                                      .mapError(mapIssueRepoError)
                       yield ()).either
                     }
    yield toBulkResponse(issueIds.size, results)

  override def bulkDelete(request: BulkIssueDeleteRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      results  <-
        ZIO.foreach(issueIds)(issueId => issueRepository.delete(IssueId(issueId)).mapError(mapIssueRepoError).either)
    yield toBulkResponse(issueIds.size, results)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def validateIssueIds(issueIds: List[String]): IO[PersistenceError, List[String]] =
    val normalized = issueIds.map(_.trim).filter(_.nonEmpty).distinct
    ZIO
      .fromOption(Option.when(normalized.nonEmpty)(normalized))
      .orElseFail(PersistenceError.QueryFailed("bulk", "issueIds must contain at least one issue id"))

  private def ensureWorkspaceExists(workspaceId: String): IO[PersistenceError, Unit] =
    workspaceRepository
      .get(workspaceId)
      .mapError(mapIssueRepoError)
      .flatMap {
        case Some(_) => ZIO.unit
        case None    => ZIO.fail(PersistenceError.QueryFailed("workspace", s"Not found: $workspaceId"))
      }

  private def assignedAgentFromState(state: IssueState): Option[String] =
    state match
      case IssueState.Assigned(agent, _)     => Some(agent.value)
      case IssueState.InProgress(agent, _)   => Some(agent.value)
      case IssueState.Completed(agent, _, _) => Some(agent.value)
      case IssueState.Failed(agent, _, _)    => Some(agent.value)
      case _                                 => None

  private def statusToEventsEnriched(
    issue: DomainIssue,
    request: IssueStatusUpdateRequest,
    fallbackAgent: String,
    now: java.time.Instant,
  ): IO[PersistenceError, List[IssueEvent]] =
    supportStatusToEvents(issue, request, fallbackAgent, now).flatMap { base =>
      request.status match
        case IssueStatus.HumanReview =>
          IssueAnalysisAttachment
            .latestForHumanReview(issue, analysisRepository, now)
            .map(attached => base ++ attached.toList)
            .mapError(mapIssueRepoError)
        case _                       => ZIO.succeed(base)
    }

  private def assignWorkspaceRunAndMarkStarted(
    issue: DomainIssue,
    workspaceId: String,
    agentName: String,
  ): IO[PersistenceError, Unit] =
    for
      run <- workspaceRunService
               .assign(
                 workspaceId,
                 AssignRunRequest(
                   issueRef = s"#${issue.id.value}",
                   prompt = executionPrompt(issue),
                   agentName = agentName,
                 ),
               )
               .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
      _   <- issue.workspaceId match
               case Some(_) =>
                 markWorkspaceBoardIssueStarted(
                   issueId = issue.id.value,
                   workspaceId = workspaceId,
                   agentName = agentName,
                   branchName = run.branchName,
                 )
               case None    =>
                 ZIO.unit
    yield ()

  private def markWorkspaceBoardIssueStarted(
    issueId: String,
    workspaceId: String,
    agentName: String,
    branchName: String,
  ): IO[PersistenceError, Unit] =
    for
      workspaceOpt <- workspaceRepository.get(workspaceId).mapError(mapIssueRepoError)
      workspace    <- ZIO
                        .fromOption(workspaceOpt)
                        .orElseFail(PersistenceError.QueryFailed("workspace", s"Not found: $workspaceId"))
      boardIssueId <- ZIO
                        .fromEither(BoardIssueId.fromString(issueId))
                        .mapError(err => PersistenceError.QueryFailed("board_issue_id", err))
      boardPath    <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _            <- boardOrchestrator
                        .markIssueStarted(boardPath, boardIssueId, agentName, branchName)
                        .mapError(mapBoardError)
    yield ()

  private def toBulkResponse(
    requested: Int,
    results: List[Either[PersistenceError, Unit]],
  ): BulkIssueOperationResponse =
    val errors = results.collect { case Left(err) => err.toString }
    BulkIssueOperationResponse(
      requested = requested,
      succeeded = results.count(_.isRight),
      failed = errors.size,
      errors = errors,
    )

  private def mapIssueRepoError(e: shared.errors.PersistenceError): PersistenceError =
    e match
      case shared.errors.PersistenceError.NotFound(entity, id)               =>
        PersistenceError.QueryFailed(s"$entity", s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)             =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, cause) =>
        PersistenceError.QueryFailed(entity, cause)
      case shared.errors.PersistenceError.StoreUnavailable(msg)              =>
        PersistenceError.QueryFailed("store", msg)

  private def mapBoardError(error: BoardError): PersistenceError =
    error match
      case BoardError.BoardNotFound(value)            => PersistenceError.QueryFailed("board", s"Not found: $value")
      case BoardError.IssueNotFound(value)            => PersistenceError.QueryFailed("board_issue", s"Not found: $value")
      case BoardError.IssueAlreadyExists(value)       => PersistenceError.QueryFailed("board_issue_exists", value)
      case BoardError.InvalidColumn(value)            => PersistenceError.QueryFailed("board_column", value)
      case BoardError.ParseError(message)             => PersistenceError.QueryFailed("board_parse", message)
      case BoardError.WriteError(path, message)       => PersistenceError.QueryFailed("board_write", s"$path: $message")
      case BoardError.GitOperationFailed(op, message) => PersistenceError.QueryFailed("board_git", s"$op: $message")
      case BoardError.DependencyCycle(issueIds)       =>
        PersistenceError.QueryFailed("board_cycle", issueIds.mkString(","))
      case BoardError.ConcurrencyConflict(message)    => PersistenceError.QueryFailed("board_concurrency", message)
