package decision.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionId
import shared.store.{ DataStoreModule, EventStore }

final case class DecisionRepositoryES(
  eventStore: EventStore[DecisionId, DecisionEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends DecisionRepository:

  private def snapshotKey(id: DecisionId): String =
    s"snapshot:decision:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:decision:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: DecisionEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.decisionId, event)
      _ <- rebuildSnapshot(event.decisionId)
    yield ()

  override def get(id: DecisionId): IO[PersistenceError, Decision] =
    fetchSnapshot(id).flatMap {
      case Some(decision) => ZIO.succeed(decision)
      case None           =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("decision", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(decision) => ZIO.succeed(decision)
              case None           => ZIO.fail(PersistenceError.NotFound("decision", id.value))
            }
        }
    }

  override def history(id: DecisionId): IO[PersistenceError, List[DecisionEvent]] =
    eventStore.events(id)

  override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
    listAll.map { decisions =>
      decisions
        .filter(matches(filter, _))
        .sortBy(decision => (decision.status.toString, decision.updatedAt, decision.createdAt))(Ordering.Tuple3(
          Ordering.String,
          Ordering[java.time.Instant].reverse,
          Ordering[java.time.Instant].reverse,
        ))
        .slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0))
    }

  private def rebuildSnapshot(id: DecisionId): IO[PersistenceError, Option[Decision]] =
    for
      events <- eventStore.events(id)
      value  <- ZIO
                  .fromEither(Decision.fromEvents(events))
                  .mapBoth(
                    err => PersistenceError.SerializationFailed(s"decision:${id.value}", err),
                    Some(_),
                  )
      _      <- dataStore.store(snapshotKey(id), value.get.toJson).mapError(storeErr("storeDecisionSnapshot"))
    yield value

  private def fetchSnapshot(id: DecisionId): IO[PersistenceError, Option[Decision]] =
    dataStore
      .fetch[String, String](snapshotKey(id))
      .mapError(storeErr("fetchDecisionSnapshot"))
      .flatMap {
        case None       => ZIO.succeed(None)
        case Some(json) =>
          ZIO
            .fromEither(json.fromJson[Decision])
            .mapBoth(err => PersistenceError.SerializationFailed(s"decision:${id.value}", err), Some(_))
      }

  private def listAll: IO[PersistenceError, List[Decision]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listDecisions"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listDecisions")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO
                .fromEither(json.fromJson[Decision])
                .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
          }
        }
      )
      .map(_.flatten.sortBy(_.createdAt)(Ordering[java.time.Instant].reverse))

  private def matches(filter: DecisionFilter, decision: Decision): Boolean =
    val matchesStatus    = filter.statuses.isEmpty || filter.statuses.contains(decision.status)
    val matchesSource    = filter.sourceKind.forall(_ == decision.source.kind)
    val matchesUrgency   = filter.urgency.forall(_ == decision.urgency)
    val matchesWorkspace = filter.workspaceId.forall(id => decision.source.workspaceId.contains(id))
    val matchesIssue     = filter.issueId.forall(id => decision.source.issueId.contains(id))
    val matchesQuery     = filter.query
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .forall { query =>
        List(
          decision.id.value,
          decision.title,
          decision.context,
          decision.source.referenceId,
          decision.source.summary,
        ).exists(_.toLowerCase.contains(query))
      }

    matchesStatus && matchesSource && matchesUrgency && matchesWorkspace && matchesIssue && matchesQuery

object DecisionRepositoryES:
  val live: ZLayer[
    EventStore[DecisionId, DecisionEvent] & DataStoreModule.DataStoreService,
    Nothing,
    DecisionRepository,
  ] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[DecisionId, DecisionEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield DecisionRepositoryES(eventStore, dataStore)
    }
