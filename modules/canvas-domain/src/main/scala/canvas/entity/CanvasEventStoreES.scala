package canvas.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.CanvasId
import shared.store.{ DataStoreService, EventStore }

final case class CanvasEventStoreES(
  dataStore: DataStoreService
) extends EventStore[CanvasId, CanvasEvent]:

  private def eventKey(id: CanvasId, seq: Long): String = s"events:canvas:${id.value}:$seq"
  private def prefix(id: CanvasId): String              = s"events:canvas:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: CanvasId, event: CanvasEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendCanvasEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendCanvasEvent"))
    yield ()

  override def events(id: CanvasId): IO[PersistenceError, List[CanvasEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: CanvasId, sequence: Long): IO[PersistenceError, List[CanvasEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: CanvasId, op: String): IO[PersistenceError, Long] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(id: CanvasId, minSequence: Option[Long]): IO[PersistenceError, List[CanvasEvent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadCanvasEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadCanvasEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[CanvasEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object CanvasEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[CanvasId, CanvasEvent]] =
    ZLayer.fromFunction(CanvasEventStoreES.apply)
