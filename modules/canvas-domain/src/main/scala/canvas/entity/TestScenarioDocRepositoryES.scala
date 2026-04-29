package canvas.entity

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, TestScenarioDocId }
import shared.store.{ DataStoreService, EventStore }

final case class TestScenarioDocRepositoryES(
  eventStore: EventStore[TestScenarioDocId, TestScenarioEvent],
  dataStore: DataStoreService,
) extends TestScenarioDocRepository:

  private def snapshotKey(id: TestScenarioDocId): String = s"snapshot:test_scenario:${id.value}"
  private def snapshotPrefix: String                     = "snapshot:test_scenario:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: TestScenarioEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.testScenarioDocId, event)
      _ <- rebuildSnapshot(event.testScenarioDocId)
    yield ()

  override def get(id: TestScenarioDocId): IO[PersistenceError, TestScenarioDoc] =
    fetchSnapshot(id).flatMap {
      case Some(doc) => ZIO.succeed(doc)
      case None      =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("test_scenario_doc", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(doc) => ZIO.succeed(doc)
              case None      => ZIO.fail(PersistenceError.NotFound("test_scenario_doc", id.value))
            }
        }
    }

  override def history(id: TestScenarioDocId): IO[PersistenceError, List[TestScenarioEvent]] =
    eventStore.events(id)

  override def list: IO[PersistenceError, List[TestScenarioDoc]] =
    listAll

  override def listForCanvas(canvasId: CanvasId): IO[PersistenceError, List[TestScenarioDoc]] =
    listAll.map(_.filter(_.canvasId == canvasId))

  private def listAll: IO[PersistenceError, List[TestScenarioDoc]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listTestScenarioDocs"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listTestScenarioDocs")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[TestScenarioDoc])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.updatedAt)(using Ordering[Instant].reverse))

  private def rebuildSnapshot(id: TestScenarioDocId): IO[PersistenceError, Option[TestScenarioDoc]] =
    for
      events <- eventStore.events(id)
      doc    <- events match
                  case Nil => ZIO.succeed(None)
                  case _   =>
                    ZIO
                      .fromEither(TestScenarioDoc.fromEvents(events))
                      .mapBoth(
                        err => PersistenceError.SerializationFailed(s"test_scenario_doc:${id.value}", err),
                        Some(_),
                      )
      _      <- doc.fold[IO[PersistenceError, Unit]](
                  dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeTestScenarioSnapshot"))
                ) { value =>
                  dataStore.store(snapshotKey(id), value.toJson).mapError(storeErr("storeTestScenarioSnapshot"))
                }
    yield doc

  private def fetchSnapshot(id: TestScenarioDocId): IO[PersistenceError, Option[TestScenarioDoc]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchTestScenarioSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[TestScenarioDoc])
          .mapBoth(err => PersistenceError.SerializationFailed(s"test_scenario_doc:${id.value}", err), Some(_))
    }

object TestScenarioDocRepositoryES:
  val live: ZLayer[
    EventStore[TestScenarioDocId, TestScenarioEvent] & DataStoreService,
    Nothing,
    TestScenarioDocRepository,
  ] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[TestScenarioDocId, TestScenarioEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield TestScenarioDocRepositoryES(eventStore, dataStore)
    }
