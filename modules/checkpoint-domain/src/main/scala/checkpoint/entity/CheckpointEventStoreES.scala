package checkpoint.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.CheckpointId
import shared.store.{ DataStoreService, EventStore }

final case class CheckpointEventStoreES(
  dataStore: DataStoreService
) extends EventStore[CheckpointId, CheckpointEvent]:

  private def eventKey(id: CheckpointId, seq: Long): String = s"events:checkpoint:${id.value}:$seq"
  private def prefix(id: CheckpointId): String              = s"events:checkpoint:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: CheckpointId, event: CheckpointEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendCheckpointEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendCheckpointEvent"))
    yield ()

  override def events(id: CheckpointId): IO[PersistenceError, List[CheckpointEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: CheckpointId, sequence: Long): IO[PersistenceError, List[CheckpointEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: CheckpointId, op: String): IO[PersistenceError, Long] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(id: CheckpointId, minSequence: Option[Long]): IO[PersistenceError, List[CheckpointEvent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadCheckpointEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadCheckpointEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[CheckpointEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object CheckpointEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[CheckpointId, CheckpointEvent]] =
    ZLayer.fromFunction(CheckpointEventStoreES.apply)
