package governance.control

import zio.*

import governance.entity.*
import project.entity.ProjectRepository
import shared.errors.PersistenceError

trait GovernancePolicyService:
  def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy]
  def evaluateForWorkspace(
    workspaceId: String,
    context: GovernanceEvaluationContext,
  ): IO[PersistenceError, GovernanceTransitionDecision]

object GovernancePolicyService:
  def resolvePolicyForWorkspace(workspaceId: String): ZIO[GovernancePolicyService, PersistenceError, GovernancePolicy] =
    ZIO.serviceWithZIO[GovernancePolicyService](_.resolvePolicyForWorkspace(workspaceId))

  def evaluateForWorkspace(
    workspaceId: String,
    context: GovernanceEvaluationContext,
  ): ZIO[GovernancePolicyService, PersistenceError, GovernanceTransitionDecision] =
    ZIO.serviceWithZIO[GovernancePolicyService](_.evaluateForWorkspace(workspaceId, context))

  val live
    : ZLayer[ProjectRepository & GovernancePolicyRepository & GovernancePolicyEngine, Nothing, GovernancePolicyService] =
    ZLayer.fromZIO {
      for
        projectRepository <- ZIO.service[ProjectRepository]
        policyRepository  <- ZIO.service[GovernancePolicyRepository]
        engine            <- ZIO.service[GovernancePolicyEngine]
      yield GovernancePolicyServiceLive(projectRepository, policyRepository, engine)
    }

final case class GovernancePolicyServiceLive(
  projectRepository: ProjectRepository,
  policyRepository: GovernancePolicyRepository,
  engine: GovernancePolicyEngine,
) extends GovernancePolicyService:

  override def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy] =
    projectRepository
      .list
      .flatMap { projects =>
        projects.find(_.workspaceIds.contains(workspaceId.trim)) match
          case Some(project) =>
            policyRepository.getActiveByProject(project.id).catchAll {
              case _: PersistenceError.NotFound => ZIO.succeed(GovernancePolicy.noOp)
              case other                        => ZIO.fail(other)
            }
          case None          =>
            ZIO.succeed(GovernancePolicy.noOp)
      }

  override def evaluateForWorkspace(
    workspaceId: String,
    context: GovernanceEvaluationContext,
  ): IO[PersistenceError, GovernanceTransitionDecision] =
    for
      policy   <- resolvePolicyForWorkspace(workspaceId)
      decision <- engine.evaluateTransition(policy, context).mapError(error =>
                    PersistenceError.QueryFailed("governance_policy_engine", error.toString)
                  )
    yield decision
