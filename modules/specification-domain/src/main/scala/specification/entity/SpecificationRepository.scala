package specification.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.SpecificationId

trait SpecificationRepository:
  def append(event: SpecificationEvent): IO[PersistenceError, Unit]
  def get(id: SpecificationId): IO[PersistenceError, Specification]
  def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]]
  def list: IO[PersistenceError, List[Specification]]
  def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff]

object SpecificationRepository:
  def append(event: SpecificationEvent): ZIO[SpecificationRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[SpecificationRepository](_.append(event))

  def get(id: SpecificationId): ZIO[SpecificationRepository, PersistenceError, Specification] =
    ZIO.serviceWithZIO[SpecificationRepository](_.get(id))

  def history(id: SpecificationId): ZIO[SpecificationRepository, PersistenceError, List[SpecificationEvent]] =
    ZIO.serviceWithZIO[SpecificationRepository](_.history(id))

  def list: ZIO[SpecificationRepository, PersistenceError, List[Specification]] =
    ZIO.serviceWithZIO[SpecificationRepository](_.list)

  def diff(
    id: SpecificationId,
    fromVersion: Int,
    toVersion: Int,
  ): ZIO[SpecificationRepository, PersistenceError, SpecificationDiff] =
    ZIO.serviceWithZIO[SpecificationRepository](_.diff(id, fromVersion, toVersion))
