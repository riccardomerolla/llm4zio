package activity.entity

import zio.*

import db.PersistenceError
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.ids.Ids.EventId
import shared.store.DataStoreModule

final case class ActivityRepositoryES(
  dataStore: DataStoreModule.DataStoreService
) extends ActivityRepository:

  private val kv = dataStore.store

  private def eventKey(id: EventId): String = s"event:${id.value}"

  override def createEvent(event: ActivityEvent): IO[PersistenceError, EventId] =
    for
      _ <- kv
             .store(eventKey(event.id), event)
             .mapError(storeErr("createEvent"))
    yield event.id

  override def listEvents(
    eventType: Option[ActivityEventType],
    since: Option[java.time.Instant],
    limit: Int,
  ): IO[PersistenceError, List[ActivityEvent]] =
    fetchAllEvents("listEvents")
      .map(
        _.filter(event => eventType.forall(_ == event.eventType))
          .filter(event => since.forall(s => !event.createdAt.isBefore(s)))
          .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
          .take(limit)
      )

  private def fetchAllEvents(op: String): IO[PersistenceError, List[ActivityEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith("event:"))
      .runCollect
      .mapError(storeErr(op))
      .flatMap { keys =>
        ZIO
          .foreach(keys.toList) { key =>
            kv.fetch[String, ActivityEvent](key)
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

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

object ActivityRepositoryES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, ActivityRepository] =
    ZLayer.fromFunction(ActivityRepositoryES.apply)
