package canvas.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, TestScenarioDocId }

trait TestScenarioDocRepository:
  def append(event: TestScenarioEvent): IO[PersistenceError, Unit]
  def get(id: TestScenarioDocId): IO[PersistenceError, TestScenarioDoc]
  def history(id: TestScenarioDocId): IO[PersistenceError, List[TestScenarioEvent]]
  def list: IO[PersistenceError, List[TestScenarioDoc]]
  def listForCanvas(canvasId: CanvasId): IO[PersistenceError, List[TestScenarioDoc]]

object TestScenarioDocRepository:
  def append(event: TestScenarioEvent): ZIO[TestScenarioDocRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TestScenarioDocRepository](_.append(event))

  def get(id: TestScenarioDocId): ZIO[TestScenarioDocRepository, PersistenceError, TestScenarioDoc] =
    ZIO.serviceWithZIO[TestScenarioDocRepository](_.get(id))

  def history(id: TestScenarioDocId): ZIO[TestScenarioDocRepository, PersistenceError, List[TestScenarioEvent]] =
    ZIO.serviceWithZIO[TestScenarioDocRepository](_.history(id))

  def list: ZIO[TestScenarioDocRepository, PersistenceError, List[TestScenarioDoc]] =
    ZIO.serviceWithZIO[TestScenarioDocRepository](_.list)

  def listForCanvas(canvasId: CanvasId): ZIO[TestScenarioDocRepository, PersistenceError, List[TestScenarioDoc]] =
    ZIO.serviceWithZIO[TestScenarioDocRepository](_.listForCanvas(canvasId))
