package specification.entity

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.SpecificationId
import shared.store.{ DataStoreService, EventStore }

final case class SpecificationRepositoryES(
  eventStore: EventStore[SpecificationId, SpecificationEvent],
  dataStore: DataStoreService,
) extends SpecificationRepository:

  private def snapshotKey(id: SpecificationId): String =
    s"snapshot:specification:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:specification:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: SpecificationEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.specificationId, event)
      _ <- rebuildSnapshot(event.specificationId)
    yield ()

  override def get(id: SpecificationId): IO[PersistenceError, Specification] =
    fetchSnapshot(id).flatMap {
      case Some(specification) => ZIO.succeed(specification)
      case None                =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("specification", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(specification) => ZIO.succeed(specification)
              case None                => ZIO.fail(PersistenceError.NotFound("specification", id.value))
            }
        }
    }

  override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]] =
    eventStore.events(id)

  override def list: IO[PersistenceError, List[Specification]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listSpecifications"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listSpecifications")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[Specification])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.updatedAt)(Ordering[Instant].reverse))

  override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
    get(id).flatMap(specification =>
      ZIO
        .fromEither(Specification.diff(specification, fromVersion, toVersion))
        .mapError(err => PersistenceError.QueryFailed("specification_diff", err))
    )

  private def rebuildSnapshot(id: SpecificationId): IO[PersistenceError, Option[Specification]] =
    for
      events        <- eventStore.events(id)
      specification <- events match
                         case Nil => ZIO.succeed(None)
                         case _   =>
                           ZIO
                             .fromEither(Specification.fromEvents(events))
                             .mapBoth(
                               err => PersistenceError.SerializationFailed(s"specification:${id.value}", err),
                               Some(_),
                             )
      _             <- specification.fold[IO[PersistenceError, Unit]](
                         dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeSpecificationSnapshot"))
                       ) { value =>
                         dataStore.store(snapshotKey(id), value.toJson).mapError(storeErr("storeSpecificationSnapshot"))
                       }
    yield specification

  private def fetchSnapshot(id: SpecificationId): IO[PersistenceError, Option[Specification]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchSpecificationSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[Specification])
          .mapBoth(
            err => PersistenceError.SerializationFailed(s"specification:${id.value}", err),
            Some(_),
          )
    }

object SpecificationRepositoryES:
  val live
    : ZLayer[EventStore[SpecificationId, SpecificationEvent] & DataStoreService, Nothing, SpecificationRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[SpecificationId, SpecificationEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield SpecificationRepositoryES(eventStore, dataStore)
    }
