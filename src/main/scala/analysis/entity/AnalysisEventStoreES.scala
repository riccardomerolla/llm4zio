package analysis.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.AnalysisDocId
import shared.store.{ DataStoreModule, EventStore }

final case class AnalysisEventStoreES(dataStore: DataStoreModule.DataStoreService)
  extends EventStore[AnalysisDocId, AnalysisEvent]:

  private def eventKey(id: AnalysisDocId, sequence: Long): String =
    s"events:analysis:${id.value}:$sequence"

  private def eventPrefix(id: AnalysisDocId): String =
    s"events:analysis:${id.value}:"

  private def sequenceFromKey(prefix: String, key: String): Option[Long] =
    key.stripPrefix(prefix).toLongOption

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: AnalysisDocId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(_.toList.flatMap(key => sequenceFromKey(prefix, key).map(seq => seq -> key)).sortBy(_._1))

  override def append(id: AnalysisDocId, event: AnalysisEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendAnalysisEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- dataStore.store(eventKey(id, nextSeq), event).mapError(storeErr("appendAnalysisEvent"))
    yield ()

  override def events(id: AnalysisDocId): IO[PersistenceError, List[AnalysisEvent]] =
    listEventKeys(id, "analysisEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => dataStore.fetch[String, AnalysisEvent](key).mapError(storeErr("analysisEvents")))
      )
      .map(_.flatten)

  override def eventsSince(id: AnalysisDocId, sequence: Long): IO[PersistenceError, List[AnalysisEvent]] =
    listEventKeys(id, "analysisEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          dataStore.fetch[String, AnalysisEvent](key).mapError(storeErr("analysisEventsSince"))
        )
      )
      .map(_.flatten)

object AnalysisEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[AnalysisDocId, AnalysisEvent]] =
    ZLayer.fromFunction(AnalysisEventStoreES.apply)
