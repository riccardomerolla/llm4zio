package canvas.entity

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.CanvasId
import shared.store.{ DataStoreService, EventStore }

final case class ReasonsCanvasRepositoryES(
  eventStore: EventStore[CanvasId, CanvasEvent],
  dataStore: DataStoreService,
) extends ReasonsCanvasRepository:

  private def snapshotKey(id: CanvasId): String = s"snapshot:canvas:${id.value}"
  private def snapshotPrefix: String            = "snapshot:canvas:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: CanvasEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.canvasId, event)
      _ <- rebuildSnapshot(event.canvasId)
    yield ()

  override def get(id: CanvasId): IO[PersistenceError, ReasonsCanvas] =
    fetchSnapshot(id).flatMap {
      case Some(canvas) => ZIO.succeed(canvas)
      case None         =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("canvas", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(canvas) => ZIO.succeed(canvas)
              case None         => ZIO.fail(PersistenceError.NotFound("canvas", id.value))
            }
        }
    }

  override def history(id: CanvasId): IO[PersistenceError, List[CanvasEvent]] =
    eventStore.events(id)

  override def list: IO[PersistenceError, List[ReasonsCanvas]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listCanvases"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listCanvases")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[ReasonsCanvas])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.updatedAt)(using Ordering[Instant].reverse))

  private def rebuildSnapshot(id: CanvasId): IO[PersistenceError, Option[ReasonsCanvas]] =
    for
      events <- eventStore.events(id)
      canvas <- events match
                  case Nil => ZIO.succeed(None)
                  case _   =>
                    ZIO
                      .fromEither(ReasonsCanvas.fromEvents(events))
                      .mapBoth(
                        err => PersistenceError.SerializationFailed(s"canvas:${id.value}", err),
                        Some(_),
                      )
      _      <- canvas.fold[IO[PersistenceError, Unit]](
                  dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeCanvasSnapshot"))
                ) { value =>
                  dataStore.store(snapshotKey(id), value.toJson).mapError(storeErr("storeCanvasSnapshot"))
                }
    yield canvas

  private def fetchSnapshot(id: CanvasId): IO[PersistenceError, Option[ReasonsCanvas]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchCanvasSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[ReasonsCanvas])
          .mapBoth(err => PersistenceError.SerializationFailed(s"canvas:${id.value}", err), Some(_))
    }

object ReasonsCanvasRepositoryES:
  val live: ZLayer[EventStore[CanvasId, CanvasEvent] & DataStoreService, Nothing, ReasonsCanvasRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[CanvasId, CanvasEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield ReasonsCanvasRepositoryES(eventStore, dataStore)
    }
