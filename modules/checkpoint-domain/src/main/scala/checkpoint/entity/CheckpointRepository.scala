package checkpoint.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ CheckpointId, TaskRunId }

trait CheckpointRepository:
  def append(event: CheckpointEvent): IO[PersistenceError, Unit]
  def get(id: CheckpointId): IO[PersistenceError, Checkpoint]
  def history(id: CheckpointId): IO[PersistenceError, List[CheckpointEvent]]
  def list: IO[PersistenceError, List[Checkpoint]]
  def listForRun(runId: TaskRunId): IO[PersistenceError, List[Checkpoint]]

object CheckpointRepository:
  def append(event: CheckpointEvent): ZIO[CheckpointRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[CheckpointRepository](_.append(event))

  def get(id: CheckpointId): ZIO[CheckpointRepository, PersistenceError, Checkpoint] =
    ZIO.serviceWithZIO[CheckpointRepository](_.get(id))

  def history(id: CheckpointId): ZIO[CheckpointRepository, PersistenceError, List[CheckpointEvent]] =
    ZIO.serviceWithZIO[CheckpointRepository](_.history(id))

  def list: ZIO[CheckpointRepository, PersistenceError, List[Checkpoint]] =
    ZIO.serviceWithZIO[CheckpointRepository](_.list)

  def listForRun(runId: TaskRunId): ZIO[CheckpointRepository, PersistenceError, List[Checkpoint]] =
    ZIO.serviceWithZIO[CheckpointRepository](_.listForRun(runId))
