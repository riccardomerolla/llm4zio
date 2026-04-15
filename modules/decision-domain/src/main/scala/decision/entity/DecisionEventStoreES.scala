package decision.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionId
import shared.store.{ DataStoreService, EventStore }

final case class DecisionEventStoreES(dataStore: DataStoreService)
  extends EventStore[DecisionId, DecisionEvent]:

  private def eventKey(id: DecisionId, seq: Long): String = s"events:decision:${id.value}:$seq"
  private def prefix(id: DecisionId): String              = s"events:decision:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: DecisionId, event: DecisionEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendDecisionEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendDecisionEvent"))
    yield ()

  override def events(id: DecisionId): IO[PersistenceError, List[DecisionEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: DecisionId, sequence: Long): IO[PersistenceError, List[DecisionEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: DecisionId, op: String): IO[PersistenceError, Long] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(id: DecisionId, minSequence: Option[Long]): IO[PersistenceError, List[DecisionEvent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadDecisionEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadDecisionEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[DecisionEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object DecisionEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[DecisionId, DecisionEvent]] =
    ZLayer.fromFunction(DecisionEventStoreES.apply)
