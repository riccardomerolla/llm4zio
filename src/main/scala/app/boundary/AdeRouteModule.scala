package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import checkpoint.boundary.CheckpointsController
import checkpoint.control.CheckpointReviewService
import daemon.boundary.DaemonsController
import decision.boundary.DecisionsController
import decision.control.DecisionInbox
import evolution.boundary.EvolutionController
import evolution.entity.EvolutionProposalRepository
import governance.boundary.GovernanceController
import governance.entity.GovernancePolicyRepository
import issues.entity.IssueRepository
import knowledge.boundary.KnowledgeController
import plan.boundary.PlansController
import project.boundary.ProjectsController
import specification.boundary.SpecificationsController

trait AdeRouteModule:
  def routes: Routes[Any, Response]

object AdeRouteModule:
  val live
    : ZLayer[
      BoardBoundaryController &
        ProjectsController &
        SpecificationsController &
        PlansController &
        DecisionsController &
        CheckpointsController &
        KnowledgeController &
        DaemonsController &
        DecisionInbox &
        CheckpointReviewService &
        IssueRepository &
        GovernancePolicyRepository &
        EvolutionProposalRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board                <- ZIO.service[BoardBoundaryController]
        projects             <- ZIO.service[ProjectsController]
        specifications       <- ZIO.service[SpecificationsController]
        plans                <- ZIO.service[PlansController]
        decisions            <- ZIO.service[DecisionsController]
        checkpoints          <- ZIO.service[CheckpointsController]
        knowledge            <- ZIO.service[KnowledgeController]
        daemons              <- ZIO.service[DaemonsController]
        decisionInbox        <- ZIO.service[DecisionInbox]
        checkpointReview     <- ZIO.service[CheckpointReviewService]
        issueRepository      <- ZIO.service[IssueRepository]
        governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
        evolutionRepo        <- ZIO.service[EvolutionProposalRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            specifications.routes ++
            plans.routes ++
            decisions.routes ++
            checkpoints.routes ++
            knowledge.routes ++
            daemons.routes ++
            GovernanceController.routes(governancePolicyRepo) ++
            EvolutionController.routes(evolutionRepo) ++
            SidebarStatusController.routes(
              decisionInbox,
              checkpointReview,
              issueRepository,
            )
    }
