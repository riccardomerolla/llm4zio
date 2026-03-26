package governance.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }
import shared.store.{ DataStoreModule, EventStore }

final case class GovernancePolicyRepositoryES(
  eventStore: EventStore[GovernancePolicyId, GovernancePolicyEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends GovernancePolicyRepository:

  private def snapshotKey(id: GovernancePolicyId): String =
    s"snapshot:governance-policy:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:governance-policy:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.policyId, event)
      _ <- rebuildSnapshot(event.policyId)
    yield ()

  override def get(id: GovernancePolicyId): IO[PersistenceError, GovernancePolicy] =
    fetchSnapshot(id).flatMap {
      case Some(policy) => ZIO.succeed(policy)
      case None         =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("governance_policy", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(policy) => ZIO.succeed(policy)
              case None         => ZIO.fail(PersistenceError.NotFound("governance_policy", id.value))
            }
        }
    }

  override def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy] =
    listByProject(projectId).flatMap {
      _.filter(_.archivedAt.isEmpty).sortBy(_.version)(Ordering.Int.reverse).headOption match
        case Some(policy) => ZIO.succeed(policy)
        case None         => ZIO.fail(PersistenceError.NotFound("governance_policy_project", projectId.value))
    }

  override def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]] =
    listAll.map(_.filter(_.projectId == projectId).sortBy(_.version))

  private def rebuildSnapshot(id: GovernancePolicyId): IO[PersistenceError, Option[GovernancePolicy]] =
    for
      events      <- eventStore.events(id)
      policyEither = GovernancePolicy.fromEvents(events)
      policyOpt   <- ZIO
                       .fromEither(policyEither)
                       .mapBoth(
                         err => PersistenceError.SerializationFailed(s"governance_policy:${id.value}", err),
                         Some(_),
                       )
                       .catchAll {
                         case _: PersistenceError.SerializationFailed if events.lastOption.exists {
                                case _: GovernancePolicyEvent.PolicyArchived => true
                                case _                                       => false
                              } =>
                           ZIO.succeed(None)
                         case other =>
                           ZIO.fail(other)
                       }
      _           <- policyOpt match
                       case Some(policy) =>
                         dataStore
                           .store(snapshotKey(id), policy.toJson)
                           .mapError(storeErr("storeGovernancePolicySnapshot"))
                       case None         =>
                         dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeGovernancePolicySnapshot"))
    yield policyOpt

  private def fetchSnapshot(id: GovernancePolicyId): IO[PersistenceError, Option[GovernancePolicy]] =
    dataStore
      .fetch[String, String](snapshotKey(id))
      .mapError(storeErr("fetchGovernancePolicySnapshot"))
      .flatMap {
        case None       => ZIO.succeed(None)
        case Some(json) =>
          ZIO
            .fromEither(json.fromJson[GovernancePolicy])
            .mapBoth(
              err => PersistenceError.SerializationFailed(s"governance_policy:${id.value}", err),
              Some(_),
            )
      }

  private def listAll: IO[PersistenceError, List[GovernancePolicy]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listGovernancePolicies"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr("listGovernancePolicies"))
            .flatMap {
              case None       => ZIO.succeed(None)
              case Some(json) =>
                ZIO
                  .fromEither(json.fromJson[GovernancePolicy])
                  .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
            }
        }
      )
      .map(_.flatten.sortBy(policy => (policy.projectId.value, policy.version)))

object GovernancePolicyRepositoryES:
  val live
    : ZLayer[
      EventStore[GovernancePolicyId, GovernancePolicyEvent] & DataStoreModule.DataStoreService,
      Nothing,
      GovernancePolicyRepository,
    ] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[GovernancePolicyId, GovernancePolicyEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield GovernancePolicyRepositoryES(eventStore, dataStore)
    }
