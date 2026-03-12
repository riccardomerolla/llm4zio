package issues.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import shared.store.{ DataStoreModule, EventStore }

final case class IssueEventStoreES(dataStore: DataStoreModule.DataStoreService) extends EventStore[IssueId, IssueEvent]:

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
      // Events are stored as JSON strings to avoid EclipseStore binary type ID conflicts
      // when the IssueEvent sealed trait evolves (new subtypes added).
      _        <- dataStore.store(eventKey(id, nextSeq), event.toJson).mapError(storeErr("appendIssueEvent"))
    yield ()

  private def loadEvent(key: String, op: String): IO[PersistenceError, Option[IssueEvent]] =
    dataStore
      .fetch[String, String](key)
      .mapError(storeErr(op))
      .flatMap {
        case None       => ZIO.succeed(None)
        case Some(json) =>
          json.fromJson[IssueEvent] match
            case Right(event) => ZIO.succeed(Some(event))
            case Left(err)    =>
              // JSON decode failure typically means this event was stored in the legacy
              // binary EclipseStore format. The event is omitted from the history; the
              // issue snapshot (stored separately as JSON) remains the source of truth.
              ZIO.logWarning(
                s"Skipping issue event at key $key — JSON decode failed ($err). " +
                  "This event may have been stored in legacy binary format. " +
                  "The event will be omitted from history; delete and recreate " +
                  "the issue to restore a clean event log."
              ).as(None)
      }
      // Fetch failure (e.g. type mismatch when reading legacy binary events as String)
      // is handled gracefully so that new events can still be appended. The omitted
      // event is logged at WARNING level; the issue remains readable via its JSON snapshot.
      .catchAll { err =>
        ZIO.logWarning(
          s"Skipping issue event at key $key — fetch failed ($err). " +
            "This event may have been stored in legacy binary format. " +
            "The event will be omitted from history; delete and recreate " +
            "the issue to restore a clean event log."
        ).as(None)
      }

  override def events(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    listEventKeys(id, "issueEvents")
      .map(_.map(_._2))
      .flatMap(keys => ZIO.foreach(keys)(loadEvent(_, "issueEvents")))
      .map(_.flatten)

  override def eventsSince(id: IssueId, sequence: Long): IO[PersistenceError, List[IssueEvent]] =
    listEventKeys(id, "issueEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys => ZIO.foreach(keys)(loadEvent(_, "issueEventsSince")))
      .map(_.flatten)

object IssueEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[IssueId, IssueEvent]] =
    ZLayer.fromFunction(IssueEventStoreES.apply)
