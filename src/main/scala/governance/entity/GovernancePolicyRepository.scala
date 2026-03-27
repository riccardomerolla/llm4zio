package governance.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

trait GovernancePolicyRepository:
  def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]
  def get(id: GovernancePolicyId): IO[PersistenceError, GovernancePolicy]
  def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy]
  def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]]
  def list: IO[PersistenceError, List[GovernancePolicy]]

object GovernancePolicyRepository:
  def append(event: GovernancePolicyEvent): ZIO[GovernancePolicyRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[GovernancePolicyRepository](_.append(event))

  def get(id: GovernancePolicyId): ZIO[GovernancePolicyRepository, PersistenceError, GovernancePolicy] =
    ZIO.serviceWithZIO[GovernancePolicyRepository](_.get(id))

  def getActiveByProject(projectId: ProjectId): ZIO[GovernancePolicyRepository, PersistenceError, GovernancePolicy] =
    ZIO.serviceWithZIO[GovernancePolicyRepository](_.getActiveByProject(projectId))

  def listByProject(projectId: ProjectId): ZIO[GovernancePolicyRepository, PersistenceError, List[GovernancePolicy]] =
    ZIO.serviceWithZIO[GovernancePolicyRepository](_.listByProject(projectId))

  def list: ZIO[GovernancePolicyRepository, PersistenceError, List[GovernancePolicy]] =
    ZIO.serviceWithZIO[GovernancePolicyRepository](_.list)
