package specification.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.SpecificationId
import shared.store.{ DataStoreModule, EventStore }

final case class SpecificationEventStoreES(
  dataStore: DataStoreModule.DataStoreService
) extends EventStore[SpecificationId, SpecificationEvent]:

  private def eventKey(id: SpecificationId, seq: Long): String = s"events:specification:${id.value}:$seq"
  private def prefix(id: SpecificationId): String              = s"events:specification:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: SpecificationId, event: SpecificationEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendSpecificationEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendSpecificationEvent"))
    yield ()

  override def events(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: SpecificationId, sequence: Long): IO[PersistenceError, List[SpecificationEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: SpecificationId, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    id: SpecificationId,
    minSequence: Option[Long],
  ): IO[PersistenceError, List[SpecificationEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadSpecificationEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadSpecificationEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[SpecificationEvent])
                .mapBoth(
                  err => PersistenceError.SerializationFailed(key, err),
                  Some(_),
                )
          }
        }
      )
      .map(_.flatten)

object SpecificationEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[SpecificationId, SpecificationEvent]] =
    ZLayer.fromFunction(SpecificationEventStoreES.apply)
