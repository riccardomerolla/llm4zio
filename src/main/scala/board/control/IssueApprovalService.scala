package board.control

import zio.*

import board.entity.BoardError
import decision.control.DecisionInbox
import decision.entity.DecisionResolutionKind
import project.control.ProjectStorageService
import shared.ids.Ids.{ BoardIssueId, IssueId }
import workspace.entity.{ Workspace, WorkspaceRepository, WorkspaceRun }

trait IssueApprovalService:
  def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit]

object IssueApprovalService:
  def quickApprove(
    workspaceId: String,
    issueId: BoardIssueId,
    reviewerNotes: String,
  ): ZIO[IssueApprovalService, BoardError, Unit] =
    ZIO.serviceWithZIO[IssueApprovalService](_.quickApprove(workspaceId, issueId, reviewerNotes))

  val live
    : ZLayer[BoardOrchestrator & DecisionInbox & WorkspaceRepository & ProjectStorageService, Nothing, IssueApprovalService] =
    ZLayer.fromFunction(IssueApprovalServiceLive.apply)

final case class IssueApprovalServiceLive(
  boardOrchestrator: BoardOrchestrator,
  decisionInbox: DecisionInbox,
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

  private def normalizedReviewerNotes(reviewerNotes: String): String =
    Option(reviewerNotes).map(_.trim).filter(_.nonEmpty).getOrElse("Approved via quick approve")
