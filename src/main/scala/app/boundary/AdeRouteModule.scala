package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import decision.control.DecisionInbox
import demo.boundary.DemoController
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
        DemoController &
        DecisionInbox &
        IssueRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board           <- ZIO.service[BoardBoundaryController]
        projects        <- ZIO.service[ProjectsController]
        knowledge       <- ZIO.service[KnowledgeController]
        demoController  <- ZIO.service[DemoController]
        decisionInbox   <- ZIO.service[DecisionInbox]
        issueRepository <- ZIO.service[IssueRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            demoController.routes ++
            NavBadgeController.routes(
              decisionInbox,
              issueRepository,
            )
    }
