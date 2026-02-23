package db

import java.time.Instant

import zio.*

import models.{ ActivityEvent, ActivityEventType }
import shared.ids.Ids.EventId
import store.DataStoreModule

trait ActivityRepository:
  def createEvent(event: ActivityEvent): IO[PersistenceError, EventId]
  def listEvents(
    eventType: Option[ActivityEventType] = None,
    since: Option[Instant] = None,
    limit: Int = 50,
  ): IO[PersistenceError, List[ActivityEvent]]

object ActivityRepository:

  def createEvent(event: ActivityEvent): ZIO[ActivityRepository, PersistenceError, EventId] =
    ZIO.serviceWithZIO[ActivityRepository](_.createEvent(event))

  def listEvents(
    eventType: Option[ActivityEventType] = None,
    since: Option[Instant] = None,
    limit: Int = 50,
  ): ZIO[ActivityRepository, PersistenceError, List[ActivityEvent]] =
    ZIO.serviceWithZIO[ActivityRepository](_.listEvents(eventType, since, limit))

  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, ActivityRepository] =
    ActivityRepositoryES.live
