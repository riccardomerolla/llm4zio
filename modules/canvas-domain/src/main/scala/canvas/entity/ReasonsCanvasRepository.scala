package canvas.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.CanvasId

trait ReasonsCanvasRepository:
  def append(event: CanvasEvent): IO[PersistenceError, Unit]
  def get(id: CanvasId): IO[PersistenceError, ReasonsCanvas]
  def history(id: CanvasId): IO[PersistenceError, List[CanvasEvent]]
  def list: IO[PersistenceError, List[ReasonsCanvas]]

object ReasonsCanvasRepository:
  def append(event: CanvasEvent): ZIO[ReasonsCanvasRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ReasonsCanvasRepository](_.append(event))

  def get(id: CanvasId): ZIO[ReasonsCanvasRepository, PersistenceError, ReasonsCanvas] =
    ZIO.serviceWithZIO[ReasonsCanvasRepository](_.get(id))

  def history(id: CanvasId): ZIO[ReasonsCanvasRepository, PersistenceError, List[CanvasEvent]] =
    ZIO.serviceWithZIO[ReasonsCanvasRepository](_.history(id))

  def list: ZIO[ReasonsCanvasRepository, PersistenceError, List[ReasonsCanvas]] =
    ZIO.serviceWithZIO[ReasonsCanvasRepository](_.list)
