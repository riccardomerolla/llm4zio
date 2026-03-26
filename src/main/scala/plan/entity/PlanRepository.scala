package plan.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.PlanId

trait PlanRepository:
  def append(event: PlanEvent): IO[PersistenceError, Unit]
  def get(id: PlanId): IO[PersistenceError, Plan]
  def history(id: PlanId): IO[PersistenceError, List[PlanEvent]]
  def list: IO[PersistenceError, List[Plan]]

object PlanRepository:
  def append(event: PlanEvent): ZIO[PlanRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[PlanRepository](_.append(event))

  def get(id: PlanId): ZIO[PlanRepository, PersistenceError, Plan] =
    ZIO.serviceWithZIO[PlanRepository](_.get(id))

  def history(id: PlanId): ZIO[PlanRepository, PersistenceError, List[PlanEvent]] =
    ZIO.serviceWithZIO[PlanRepository](_.history(id))

  def list: ZIO[PlanRepository, PersistenceError, List[Plan]] =
    ZIO.serviceWithZIO[PlanRepository](_.list)
