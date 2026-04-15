package plan.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.PlanId
import shared.store.{ DataStoreService, EventStore }

final case class PlanEventStoreES(dataStore: DataStoreService) extends EventStore[PlanId, PlanEvent]:

  private def eventKey(id: PlanId, seq: Long): String = s"events:plan:${id.value}:$seq"
  private def prefix(id: PlanId): String              = s"events:plan:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: PlanId, event: PlanEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendPlanEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendPlanEvent"))
    yield ()

  override def events(id: PlanId): IO[PersistenceError, List[PlanEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: PlanId, sequence: Long): IO[PersistenceError, List[PlanEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: PlanId, op: String): IO[PersistenceError, Long] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(id: PlanId, minSequence: Option[Long]): IO[PersistenceError, List[PlanEvent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadPlanEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadPlanEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[PlanEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object PlanEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[PlanId, PlanEvent]] =
    ZLayer.fromFunction(PlanEventStoreES.apply)
