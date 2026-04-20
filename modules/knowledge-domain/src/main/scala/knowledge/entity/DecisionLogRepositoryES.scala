package knowledge.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.DecisionLogId
import shared.store.{ DataStoreService, EventStore }

final case class DecisionLogRepositoryES(
  eventStore: EventStore[DecisionLogId, DecisionLogEvent],
  dataStore: DataStoreService,
) extends DecisionLogRepository:

  private def snapshotKey(id: DecisionLogId): String =
    s"snapshot:decision-log:${id.value}"

  private def snapshotPrefix: String =
    "snapshot:decision-log:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: DecisionLogEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.decisionLogId, event)
      _ <- rebuildSnapshot(event.decisionLogId)
    yield ()

  override def get(id: DecisionLogId): IO[PersistenceError, DecisionLog] =
    fetchSnapshot(id).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("decision_log", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(value) => ZIO.succeed(value)
              case None        => ZIO.fail(PersistenceError.NotFound("decision_log", id.value))
            }
        }
    }

  override def history(id: DecisionLogId): IO[PersistenceError, List[DecisionLogEvent]] =
    eventStore.events(id)

  override def list(filter: DecisionLogFilter): IO[PersistenceError, List[DecisionLog]] =
    listAll.map { values =>
      values
        .filter(matches(filter, _))
        .slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0))
    }

  private def rebuildSnapshot(id: DecisionLogId): IO[PersistenceError, Option[DecisionLog]] =
    for
      events <- eventStore.events(id)
      value  <- events match
                  case Nil => ZIO.succeed(None)
                  case _   =>
                    ZIO
                      .fromEither(DecisionLog.fromEvents(events))
                      .mapBoth(err => PersistenceError.SerializationFailed(s"decision-log:${id.value}", err), Some(_))
      _      <- value.fold[IO[PersistenceError, Unit]](
                  dataStore.remove[String](snapshotKey(id)).mapError(storeErr("removeDecisionLogSnapshot"))
                )(log =>
                  dataStore.store(snapshotKey(id), log.toJson).mapError(storeErr("storeDecisionLogSnapshot"))
                )
    yield value

  private def fetchSnapshot(id: DecisionLogId): IO[PersistenceError, Option[DecisionLog]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchDecisionLogSnapshot")).flatMap {
      case None       => ZIO.succeed(None)
      case Some(json) =>
        ZIO
          .fromEither(json.fromJson[DecisionLog])
          .mapBoth(err => PersistenceError.SerializationFailed(s"decision-log:${id.value}", err), Some(_))
    }

  private def listAll: IO[PersistenceError, List[DecisionLog]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listDecisionLogs"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listDecisionLogs")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO.fromEither(json.fromJson[DecisionLog]).mapBoth(
                err => PersistenceError.SerializationFailed(key, err),
                Some(_),
              )
          }
        }
      )
      .map(_.flatten.sortBy(_.decisionDate)(using Ordering[java.time.Instant].reverse))

  private def matches(filter: DecisionLogFilter, value: DecisionLog): Boolean =
    val needle           = filter.query.map(_.trim.toLowerCase).filter(_.nonEmpty)
    val matchesWorkspace = filter.workspaceId.forall(_ == value.workspaceId.getOrElse(""))
    val matchesIssue     = filter.issueId.forall(value.issueIds.contains)
    val matchesSpec      = filter.specificationId.forall(value.specificationIds.contains)
    val matchesPlan      = filter.planId.forall(value.planIds.contains)
    val matchesRun       = filter.runId.forall(id => value.runId.contains(id))
    val matchesQuery     = needle.forall { query =>
      val haystacks = List(
        value.id.value,
        value.title,
        value.context,
        value.decisionTaken,
        value.rationale,
      ) ++ value.consequences ++ value.designConstraints ++ value.lessonsLearned ++ value.systemUnderstanding ++ value.architecturalRationales
      haystacks.exists(_.toLowerCase.contains(query))
    }
    matchesWorkspace && matchesIssue && matchesSpec && matchesPlan && matchesRun && matchesQuery

object DecisionLogRepositoryES:
  val live
    : ZLayer[EventStore[DecisionLogId, DecisionLogEvent] & DataStoreService, Nothing, DecisionLogRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[DecisionLogId, DecisionLogEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield DecisionLogRepositoryES(eventStore, dataStore)
    }
