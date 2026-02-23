package issues.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import shared.store.{ DataStoreModule, EventStore }

final case class IssueEventStoreES(dataStore: DataStoreModule.DataStoreService) extends EventStore[IssueId, IssueEvent]:

  private val typedStore = dataStore.store

  private def eventKey(id: IssueId, sequence: Long): String = s"events:issue:${id.value}:$sequence"

  private def eventPrefix(id: IssueId): String = s"events:issue:${id.value}:"

  private def sequenceFromKey(prefix: String, key: String): Option[Long] =
    key.stripPrefix(prefix).toLongOption

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: IssueId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(_.toList.flatMap(key => sequenceFromKey(prefix, key).map(seq => seq -> key)).sortBy(_._1))

  override def append(id: IssueId, event: IssueEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendIssueEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- typedStore.store(eventKey(id, nextSeq), event).mapError(storeErr("appendIssueEvent"))
    yield ()

  override def events(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    listEventKeys(id, "issueEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => typedStore.fetch[String, IssueEvent](key).mapError(storeErr("issueEvents")))
      )
      .map(_.flatten)

  override def eventsSince(id: IssueId, sequence: Long): IO[PersistenceError, List[IssueEvent]] =
    listEventKeys(id, "issueEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => typedStore.fetch[String, IssueEvent](key).mapError(storeErr("issueEventsSince")))
      )
      .map(_.flatten)

object IssueEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[IssueId, IssueEvent]] =
    ZLayer.fromFunction(IssueEventStoreES.apply)
