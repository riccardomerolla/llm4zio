package daemon.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ DaemonAgentSpecId, ProjectId }

trait DaemonAgentSpecRepository:
  def get(id: DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec]
  def listByProject(projectId: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]]
  def listAll: IO[PersistenceError, List[DaemonAgentSpec]]
  def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit]
  def delete(id: DaemonAgentSpecId): IO[PersistenceError, Unit]

object DaemonAgentSpecRepository:
  def get(id: DaemonAgentSpecId): ZIO[DaemonAgentSpecRepository, PersistenceError, DaemonAgentSpec] =
    ZIO.serviceWithZIO[DaemonAgentSpecRepository](_.get(id))

  def listByProject(projectId: ProjectId): ZIO[DaemonAgentSpecRepository, PersistenceError, List[DaemonAgentSpec]] =
    ZIO.serviceWithZIO[DaemonAgentSpecRepository](_.listByProject(projectId))

  def listAll: ZIO[DaemonAgentSpecRepository, PersistenceError, List[DaemonAgentSpec]] =
    ZIO.serviceWithZIO[DaemonAgentSpecRepository](_.listAll)

  def save(spec: DaemonAgentSpec): ZIO[DaemonAgentSpecRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentSpecRepository](_.save(spec))

  def delete(id: DaemonAgentSpecId): ZIO[DaemonAgentSpecRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentSpecRepository](_.delete(id))
