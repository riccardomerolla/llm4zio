package plan.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.PlanId
import shared.store.{ DataStoreService, EventStore }

final case class PlanRepositoryES(
  eventStore: EventStore[PlanId, PlanEvent],
  dataStore: DataStoreService,
) extends PlanRepository:

  private def snapshotKey(id: PlanId): String =
    s"snapshot:plan:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:plan:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: PlanEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.planId, event)
      _ <- rebuildSnapshot(event.planId)
    yield ()

  override def get(id: PlanId): IO[PersistenceError, Plan] =
    fetchSnapshot(id).flatMap {
      case Some(plan) => ZIO.succeed(plan)
      case None       =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("plan", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(plan) => ZIO.succeed(plan)
              case None       => ZIO.fail(PersistenceError.NotFound("plan", id.value))
            }
        }
    }

  override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]] =
    eventStore.events(id)

  override def list: IO[PersistenceError, List[Plan]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listPlans"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listPlans")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[Plan])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.updatedAt)(Ordering[java.time.Instant].reverse))

  private def rebuildSnapshot(id: PlanId): IO[PersistenceError, Option[Plan]] =
    for
      events <- eventStore.events(id)
      plan   <- events match
                  case Nil => ZIO.succeed(None)
                  case _   =>
                    ZIO
                      .fromEither(Plan.fromEvents(events))
                      .mapBoth(err => PersistenceError.SerializationFailed(s"plan:${id.value}", err), Some(_))
      _      <- plan.fold[IO[PersistenceError, Unit]](
                  dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removePlanSnapshot"))
                )(value =>
                  dataStore.store(snapshotKey(id), value.toJson).mapError(storeErr("storePlanSnapshot"))
                )
    yield plan

  private def fetchSnapshot(id: PlanId): IO[PersistenceError, Option[Plan]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchPlanSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[Plan])
          .mapBoth(err => PersistenceError.SerializationFailed(s"plan:${id.value}", err), Some(_))
    }

object PlanRepositoryES:
  val live: ZLayer[EventStore[PlanId, PlanEvent] & DataStoreService, Nothing, PlanRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[PlanId, PlanEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield PlanRepositoryES(eventStore, dataStore)
    }
