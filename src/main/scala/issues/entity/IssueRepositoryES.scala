package issues.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import shared.store.{ DataStoreModule, EventStore }

final case class IssueRepositoryES(
  eventStore: EventStore[IssueId, IssueEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends IssueRepository:

  private def enrichBlockingRelationships(issues: List[AgentIssue]): List[AgentIssue] =
    val blockingIndex = issues.foldLeft(Map.empty[IssueId, List[IssueId]]) { (acc, issue) =>
      issue.blockedBy.foldLeft(acc) { (inner, blockedById) =>
        inner.updated(blockedById, (inner.getOrElse(blockedById, Nil) :+ issue.id).distinct)
      }
    }
    issues.map(issue => issue.copy(blocking = blockingIndex.getOrElse(issue.id, issue.blocking).distinct))

  private def snapshotKey(id: IssueId): String = s"snapshot:issue:${id.value}"

  private def snapshotPrefix: String = "snapshot:issue:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: IssueId): IO[PersistenceError, AgentIssue] =
    for
      events <- eventStore.events(id)
      _      <- ZIO.logDebug(s"Rebuilding issue snapshot for ${id.value} from ${events.size} events")
      issue  <- ZIO
                  .fromEither(AgentIssue.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"issue:${id.value}", msg))
      _      <- dataStore
                  .store(snapshotKey(id), issue.toJson)
                  .mapError(storeErr("storeIssueSnapshot"))
      _      <-
        ZIO.logDebug(s"Stored issue snapshot (JSON): ${snapshotKey(id)} [state=${issue.state.getClass.getSimpleName}]")
    yield issue

  override def append(event: IssueEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.issueId, event)
      _ <- rebuildSnapshot(event.issueId)
    yield ()

  private def fetchSnapshot(id: IssueId): IO[PersistenceError, Option[AgentIssue]] =
    dataStore
      .fetch[String, String](snapshotKey(id))
      .mapError(storeErr("fetchIssueSnapshot"))
      .flatMap {
        case None       => ZIO.succeed(None)
        case Some(json) =>
          ZIO
            .fromEither(json.fromJson[AgentIssue])
            .mapBoth(
              err => PersistenceError.SerializationFailed(s"issue:${id.value}", err),
              Some(_),
            )
      }

  override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
    list(IssueFilter(limit = Int.MaxValue)).flatMap { issues =>
      issues.find(_.id == id) match
        case Some(issue) => ZIO.succeed(issue)
        case None        =>
          fetchSnapshot(id).flatMap {
            case Some(issue) =>
              ZIO.logDebug(
                s"Fetched issue snapshot (JSON) from store: ${snapshotKey(id)} [state=${issue.state.getClass.getSimpleName}]"
              ) *>
                ZIO.succeed(issue)
            case None        =>
              ZIO.logDebug(s"No snapshot found for issue:${id.value} — rebuilding from events") *>
                eventStore.events(id).flatMap {
                  case Nil => ZIO.fail(PersistenceError.NotFound("issue", id.value))
                  case _   => rebuildSnapshot(id)
                }
          }
    }

  override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    eventStore.events(id)

  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listIssues"))
      .flatMap { keys =>
        ZIO.logDebug(s"Scanning ${keys.size} issue snapshot key(s) from store") *>
          ZIO.foreach(keys.toList) { key =>
            dataStore
              .fetch[String, String](key)
              .mapError(storeErr("listIssues"))
              .flatMap {
                case None       => ZIO.succeed(None)
                case Some(json) =>
                  ZIO
                    .fromEither(json.fromJson[AgentIssue])
                    .mapBoth(err => PersistenceError.SerializationFailed(key, err), Some(_))
              }
          }
      }
      .map(_.flatten)
      .map(enrichBlockingRelationships)
      .tap { all =>
        ZIO.logDebug(s"Recovered ${all.size} issue object(s) from store") *>
          ZIO.foreachDiscard(all) { i =>
            ZIO.logDebug(
              s"issue[${i.id.value}] state=${i.state.getClass.getSimpleName}" +
                s" runId=${i.runId} (${i.runId.getClass.getSimpleName})" +
                s" conversationId=${i.conversationId} (${i.conversationId.getClass.getSimpleName})" +
                s" priority=${i.priority} tags=${i.tags}" +
                s" contextPath='${i.contextPath}' sourceFolder='${i.sourceFolder}'"
            )
          }
      }
      .map(_.filter(issueMatches(filter, _)).slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0)))

  override def delete(id: IssueId): IO[PersistenceError, Unit] =
    val eventPrefix = s"events:issue:${id.value}:"
    for
      _         <- ZIO.logDebug(s"Deleting issue ${id.value}: removing snapshot and events")
      _         <- dataStore.remove[String](snapshotKey(id)).mapError(storeErr("deleteIssueSnapshot"))
      eventKeys <- dataStore.rawStore
                     .streamKeys[String]
                     .filter(_.startsWith(eventPrefix))
                     .runCollect
                     .mapError(storeErr("deleteIssueEvents"))
      _         <- ZIO.foreachDiscard(eventKeys)(key =>
                     dataStore.remove[String](key).mapError(storeErr("deleteIssueEvents"))
                   )
      _         <- ZIO.logDebug(s"Deleted issue ${id.value}: removed snapshot + ${eventKeys.size} event(s)")
    yield ()

  private def issueMatches(filter: IssueFilter, issue: AgentIssue): Boolean =
    val runMatches   = filter.runId.forall(expected => issue.runId.contains(expected))
    val stateMatches = filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(issue.state))
    val agentMatches = filter.agentId.forall { expected =>
      issue.state match
        case IssueState.Assigned(agent, _)     => agent == expected
        case IssueState.InProgress(agent, _)   => agent == expected
        case IssueState.Completed(agent, _, _) => agent == expected
        case IssueState.Failed(agent, _, _)    => agent == expected
        case _                                 => false
    }
    runMatches && stateMatches && (filter.agentId.isEmpty || agentMatches)

object IssueRepositoryES:
  val live: ZLayer[EventStore[IssueId, IssueEvent] & DataStoreModule.DataStoreService, Nothing, IssueRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[IssueId, IssueEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield IssueRepositoryES(eventStore, dataStore)
    }
