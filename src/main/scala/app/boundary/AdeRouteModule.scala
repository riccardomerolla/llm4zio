package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import daemon.boundary.DaemonsController
import decision.control.DecisionInbox
import demo.boundary.DemoController
import governance.boundary.GovernanceController
import governance.entity.GovernancePolicyRepository
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
        DaemonsController &
        DemoController &
        DecisionInbox &
        IssueRepository &
        GovernancePolicyRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board                <- ZIO.service[BoardBoundaryController]
        projects             <- ZIO.service[ProjectsController]
        knowledge            <- ZIO.service[KnowledgeController]
        daemons              <- ZIO.service[DaemonsController]
        demoController       <- ZIO.service[DemoController]
        decisionInbox        <- ZIO.service[DecisionInbox]
        issueRepository      <- ZIO.service[IssueRepository]
        governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            daemons.routes ++
            demoController.routes ++
            GovernanceController.routes(governancePolicyRepo) ++
            NavBadgeController.routes(
              decisionInbox,
              issueRepository,
            )
    }
