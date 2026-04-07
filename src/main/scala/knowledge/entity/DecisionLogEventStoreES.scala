package knowledge.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionLogId
import shared.store.{ DataStoreService, EventStore }

final case class DecisionLogEventStoreES(dataStore: DataStoreService)
  extends EventStore[DecisionLogId, DecisionLogEvent]:

  private def eventKey(id: DecisionLogId, seq: Long): String = s"events:decision-log:${id.value}:$seq"
  private def prefix(id: DecisionLogId): String              = s"events:decision-log:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: DecisionLogId, event: DecisionLogEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendDecisionLogEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendDecisionLogEvent"))
    yield ()

  override def events(id: DecisionLogId): IO[PersistenceError, List[DecisionLogEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: DecisionLogId, sequence: Long): IO[PersistenceError, List[DecisionLogEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: DecisionLogId, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    id: DecisionLogId,
    minSequence: Option[Long],
  ): IO[PersistenceError, List[DecisionLogEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadDecisionLogEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadDecisionLogEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[DecisionLogEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object DecisionLogEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[DecisionLogId, DecisionLogEvent]] =
    ZLayer.fromFunction(DecisionLogEventStoreES.apply)
