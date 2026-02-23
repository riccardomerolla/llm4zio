package db

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import models.{ ActivityEvent, ActivityEventType }
import store.{ ActivityEventRow, DataStoreModule }

final case class ActivityRepositoryES(
  dataStore: DataStoreModule.DataStoreService
) extends ActivityRepository:

  private val kv = dataStore.store

  private def eventKey(id: Long): String = s"event:$id"

  override def createEvent(event: ActivityEvent): IO[PersistenceError, Long] =
    for
      id <- nextId("createEvent")
      _  <- kv
              .store(eventKey(id), toStoreRow(event, id))
              .mapError(storeErr("createEvent"))
    yield id

  override def listEvents(
    eventType: Option[ActivityEventType],
    since: Option[java.time.Instant],
    limit: Int,
  ): IO[PersistenceError, List[ActivityEvent]] =
    fetchAllEvents("listEvents")
      .map(
        _.map(fromStoreRow)
          .filter(event => eventType.forall(_ == event.eventType))
          .filter(event => since.forall(s => !event.createdAt.isBefore(s)))
          .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
          .take(limit)
      )

  private def fetchAllEvents(op: String): IO[PersistenceError, List[ActivityEventRow]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith("event:"))
      .runCollect
      .mapError(storeErr(op))
      .flatMap { keys =>
        ZIO
          .foreach(keys.toList) { key =>
            kv.fetch[String, ActivityEventRow](key)
              .mapError(storeErr(op))
              .catchAllCause { cause =>
                ZIO.logWarning(s"$op skipped unreadable activity row '$key': ${cause.prettyPrint}").as(None)
              }
              .map {
                case Some(value) => value :: Nil
                case _           => Nil
              }
          }
          .map(_.flatten)
      }

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErrThrowable(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def storeErrThrowable(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  private def toStoreRow(event: ActivityEvent, id: Long): ActivityEventRow =
    ActivityEventRow(
      id = id.toString,
      eventType = event.eventType.toString,
      source = event.source,
      runId = event.runId.map(_.toString),
      conversationId = event.conversationId.map(_.toString),
      agentName = event.agentName,
      summary = event.summary,
      payload = event.payload,
      createdAt = event.createdAt,
    )

  private def fromStoreRow(row: ActivityEventRow): ActivityEvent =
    ActivityEvent(
      id = Some(row.id),
      eventType = ActivityEventType.values.find(_.toString == row.eventType).getOrElse(ActivityEventType.RunStarted),
      source = row.source,
      runId = row.runId,
      conversationId = row.conversationId,
      agentName = row.agentName,
      summary = row.summary,
      payload = row.payload,
      createdAt = row.createdAt,
    )

object ActivityRepositoryES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, ActivityRepository] =
    ZLayer.fromFunction(ActivityRepositoryES.apply)
