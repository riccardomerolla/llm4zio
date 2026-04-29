package checkpoint.entity

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.{ CheckpointId, TaskRunId }
import shared.store.{ DataStoreService, EventStore }

final case class CheckpointRepositoryES(
  eventStore: EventStore[CheckpointId, CheckpointEvent],
  dataStore: DataStoreService,
) extends CheckpointRepository:

  private def snapshotKey(id: CheckpointId): String = s"snapshot:checkpoint:${id.value}"
  private def snapshotPrefix: String                = "snapshot:checkpoint:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: CheckpointEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.checkpointId, event)
      _ <- rebuildSnapshot(event.checkpointId)
    yield ()

  override def get(id: CheckpointId): IO[PersistenceError, Checkpoint] =
    fetchSnapshot(id).flatMap {
      case Some(checkpoint) => ZIO.succeed(checkpoint)
      case None             =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("checkpoint", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(checkpoint) => ZIO.succeed(checkpoint)
              case None             => ZIO.fail(PersistenceError.NotFound("checkpoint", id.value))
            }
        }
    }

  override def history(id: CheckpointId): IO[PersistenceError, List[CheckpointEvent]] =
    eventStore.events(id)

  override def list: IO[PersistenceError, List[Checkpoint]] =
    listAll

  override def listForRun(runId: TaskRunId): IO[PersistenceError, List[Checkpoint]] =
    listAll.map(_.filter(_.runId == runId))

  private def listAll: IO[PersistenceError, List[Checkpoint]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listCheckpoints"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listCheckpoints")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[Checkpoint])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.updatedAt)(using Ordering[Instant].reverse))

  private def rebuildSnapshot(id: CheckpointId): IO[PersistenceError, Option[Checkpoint]] =
    for
      events     <- eventStore.events(id)
      checkpoint <- events match
                      case Nil => ZIO.succeed(None)
                      case _   =>
                        ZIO
                          .fromEither(Checkpoint.fromEvents(events))
                          .mapBoth(
                            err => PersistenceError.SerializationFailed(s"checkpoint:${id.value}", err),
                            Some(_),
                          )
      _          <- checkpoint.fold[IO[PersistenceError, Unit]](
                      dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeCheckpointSnapshot"))
                    ) { value =>
                      dataStore.store(snapshotKey(id), value.toJson).mapError(storeErr("storeCheckpointSnapshot"))
                    }
    yield checkpoint

  private def fetchSnapshot(id: CheckpointId): IO[PersistenceError, Option[Checkpoint]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchCheckpointSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[Checkpoint])
          .mapBoth(err => PersistenceError.SerializationFailed(s"checkpoint:${id.value}", err), Some(_))
    }

object CheckpointRepositoryES:
  val live: ZLayer[EventStore[CheckpointId, CheckpointEvent] & DataStoreService, Nothing, CheckpointRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[CheckpointId, CheckpointEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield CheckpointRepositoryES(eventStore, dataStore)
    }
