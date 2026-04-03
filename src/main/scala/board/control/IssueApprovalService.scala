package board.control

import zio.*

import board.entity.{ BoardColumn, BoardError, BoardRepository }
import decision.control.DecisionInbox
import decision.entity.DecisionResolutionKind
import project.control.ProjectStorageService
import shared.ids.Ids.{ BoardIssueId, IssueId }
import workspace.control.WorkspaceRunService
import workspace.entity.{ RunStatus, Workspace, WorkspaceRepository, WorkspaceRun }

trait IssueApprovalService:
  def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit]
  def reworkIssue(
    workspaceId: String,
    issueId: BoardIssueId,
    reworkComment: String,
    actor: String,
  ): IO[BoardError, Unit]

object IssueApprovalService:
  def quickApprove(
    workspaceId: String,
    issueId: BoardIssueId,
    reviewerNotes: String,
  ): ZIO[IssueApprovalService, BoardError, Unit] =
    ZIO.serviceWithZIO[IssueApprovalService](_.quickApprove(workspaceId, issueId, reviewerNotes))

  def reworkIssue(
    workspaceId: String,
    issueId: BoardIssueId,
    reworkComment: String,
    actor: String,
  ): ZIO[IssueApprovalService, BoardError, Unit] =
    ZIO.serviceWithZIO[IssueApprovalService](_.reworkIssue(workspaceId, issueId, reworkComment, actor))

  val live
    : ZLayer[
      BoardOrchestrator & BoardRepository & DecisionInbox & WorkspaceRunService & WorkspaceRepository &
        ProjectStorageService,
      Nothing,
      IssueApprovalService,
    ] =
    ZLayer.fromFunction(IssueApprovalServiceLive.apply)

final case class IssueApprovalServiceLive(
  boardOrchestrator: BoardOrchestrator,
  boardRepository: BoardRepository,
  decisionInbox: DecisionInbox,
  workspaceRunService: WorkspaceRunService,
  workspaceRepository: WorkspaceRepository,
  projectStorageService: ProjectStorageService,
) extends IssueApprovalService:

  override def quickApprove(
    workspaceId: String,
    issueId: BoardIssueId,
    reviewerNotes: String,
  ): IO[BoardError, Unit] =
    for
      workspace   <- loadWorkspace(workspaceId)
      _           <- loadLatestRun(workspace.id, issueId)
      reviewNotes  = normalizedReviewerNotes(reviewerNotes)
      _           <- decisionInbox
                       .resolveOpenIssueReviewDecision(
                         issueId = IssueId(issueId.value),
                         resolutionKind = DecisionResolutionKind.Approved,
                         actor = "web",
                         summary = reviewNotes,
                       )
                       .mapError(err => BoardError.ParseError(s"decision resolve failed: $err"))
      projectRoot <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _           <- boardOrchestrator.approveIssue(projectRoot, issueId)
    yield ()

  override def reworkIssue(
    workspaceId: String,
    issueId: BoardIssueId,
    reworkComment: String,
    actor: String,
  ): IO[BoardError, Unit] =
    for
      workspace    <- loadWorkspace(workspaceId)
      projectRoot  <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      latestRun    <- loadLatestCompletedRun(workspace.id, issueId)
      reviewComment = normalizedReworkComment(reworkComment)
      resolvedActor = normalizedActor(actor)
      _            <- decisionInbox
                        .resolveOpenIssueReviewDecision(
                          issueId = IssueId(issueId.value),
                          resolutionKind = DecisionResolutionKind.ReworkRequested,
                          actor = resolvedActor,
                          summary = reviewComment,
                        )
                        .mapError(err => BoardError.ParseError(s"decision rework failed: $err"))
      _            <- boardRepository.moveIssue(projectRoot, issueId, BoardColumn.Todo)
      _            <- workspaceRunService
                        .continueRun(latestRun.id, reviewComment)
                        .mapError(err => BoardError.ParseError(s"continue run failed: $err"))
                        .unit
    yield ()

  private def loadWorkspace(workspaceId: String): IO[BoardError, Workspace] =
    workspaceRepository
      .get(workspaceId)
      .mapError(err => BoardError.ParseError(s"workspace lookup failed: $err"))
      .flatMap(value => ZIO.fromOption(value).orElseFail(BoardError.BoardNotFound(workspaceId)))

  private def loadLatestRun(workspaceId: String, issueId: BoardIssueId): IO[BoardError, WorkspaceRun] =
    for
      direct <- workspaceRepository
                  .listRunsByIssueRef(issueId.value)
                  .mapError(err => BoardError.ParseError(s"run lookup failed: $err"))
      hash   <- workspaceRepository
                  .listRunsByIssueRef(s"#${issueId.value}")
                  .mapError(err => BoardError.ParseError(s"run lookup failed: $err"))
      latest <- ZIO
                  .fromOption(
                    (direct ++ hash)
                      .filter(_.workspaceId == workspaceId)
                      .groupBy(_.id)
                      .values
                      .map(_.head)
                      .toList
                      .sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse)
                      .headOption
                  )
                  .orElseFail(BoardError.ParseError(s"latest run not found for issue '${issueId.value}'"))
    yield latest

  private def loadLatestCompletedRun(workspaceId: String, issueId: BoardIssueId): IO[BoardError, WorkspaceRun] =
    loadMatchingRuns(workspaceId, issueId)
      .flatMap(runs =>
        ZIO
          .fromOption(runs.filter(run => run.status == RunStatus.Completed).headOption)
          .orElseFail(BoardError.ParseError(s"latest completed run not found for issue '${issueId.value}'"))
      )

  private def loadMatchingRuns(workspaceId: String, issueId: BoardIssueId): IO[BoardError, List[WorkspaceRun]] =
    for
      direct <- workspaceRepository
                  .listRunsByIssueRef(issueId.value)
                  .mapError(err => BoardError.ParseError(s"run lookup failed: $err"))
      hash   <- workspaceRepository
                  .listRunsByIssueRef(s"#${issueId.value}")
                  .mapError(err => BoardError.ParseError(s"run lookup failed: $err"))
    yield (direct ++ hash)
      .filter(_.workspaceId == workspaceId)
      .groupBy(_.id)
      .values
      .map(_.head)
      .toList
      .sortBy(_.updatedAt.toEpochMilli)(Ordering.Long.reverse)

  private def normalizedReviewerNotes(reviewerNotes: String): String =
    Option(reviewerNotes).map(_.trim).filter(_.nonEmpty).getOrElse("Approved via quick approve")

  private def normalizedReworkComment(reworkComment: String): String =
    Option(reworkComment).map(_.trim).filter(_.nonEmpty).getOrElse("Changes requested via review")

  private def normalizedActor(actor: String): String =
    Option(actor).map(_.trim).filter(_.nonEmpty).getOrElse("web")
