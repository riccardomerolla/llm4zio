package canvas.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.TestScenarioDocId
import shared.store.{ DataStoreService, EventStore }

final case class TestScenarioEventStoreES(
  dataStore: DataStoreService
) extends EventStore[TestScenarioDocId, TestScenarioEvent]:

  private def eventKey(id: TestScenarioDocId, seq: Long): String = s"events:test_scenario:${id.value}:$seq"
  private def prefix(id: TestScenarioDocId): String              = s"events:test_scenario:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: TestScenarioDocId, event: TestScenarioEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendTestScenarioEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendTestScenarioEvent"))
    yield ()

  override def events(id: TestScenarioDocId): IO[PersistenceError, List[TestScenarioEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: TestScenarioDocId, sequence: Long): IO[PersistenceError, List[TestScenarioEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: TestScenarioDocId, op: String): IO[PersistenceError, Long] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    id: TestScenarioDocId,
    minSequence: Option[Long],
  ): IO[PersistenceError, List[TestScenarioEvent]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadTestScenarioEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadTestScenarioEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[TestScenarioEvent])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten)

object TestScenarioEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[TestScenarioDocId, TestScenarioEvent]] =
    ZLayer.fromFunction(TestScenarioEventStoreES.apply)
