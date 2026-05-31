package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import canvas.boundary.CanvasController
import decision.entity.DecisionRepository
import issues.entity.IssueRepository
import knowledge.boundary.KnowledgeController
import project.boundary.ProjectsController

trait AdeRouteModule:
  def routes: Routes[Any, Response]

object AdeRouteModule:
  val live
    : ZLayer[
      BoardBoundaryController &
        ProjectsController &
        KnowledgeController &
        CanvasController &
        IssueRepository &
        DecisionRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board              <- ZIO.service[BoardBoundaryController]
        projects           <- ZIO.service[ProjectsController]
        knowledge          <- ZIO.service[KnowledgeController]
        canvas             <- ZIO.service[CanvasController]
        issueRepository    <- ZIO.service[IssueRepository]
        decisionRepository <- ZIO.service[DecisionRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            canvas.routes ++
            NavBadgeController.routes(
              issueRepository,
              decisionRepository,
            )
    }
