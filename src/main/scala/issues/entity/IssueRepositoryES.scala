package issues.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import shared.store.{ DataStoreModule, EventStore }

final case class IssueRepositoryES(
  eventStore: EventStore[IssueId, IssueEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends IssueRepository:

  private val typedStore = dataStore.store

  private def snapshotKey(id: IssueId): String = s"snapshot:issue:${id.value}"

  private def snapshotPrefix: String = "snapshot:issue:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: IssueId): IO[PersistenceError, AgentIssue] =
    for
      events <- eventStore.events(id)
      issue  <- ZIO
                  .fromEither(AgentIssue.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"issue:${id.value}", msg))
      _      <- typedStore.store(snapshotKey(id), issue).mapError(storeErr("storeIssueSnapshot"))
    yield issue

  override def append(event: IssueEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.issueId, event)
      _ <- rebuildSnapshot(event.issueId)
    yield ()

  override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
    typedStore.fetch[String, AgentIssue](snapshotKey(id)).mapError(storeErr("getIssueSnapshot")).flatMap {
      case Some(issue) => ZIO.succeed(issue)
      case None        =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("issue", id.value))
          case _   => rebuildSnapshot(id)
        }
    }

  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listIssues"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key => typedStore.fetch[String, AgentIssue](key).mapError(storeErr("listIssues")))
      )
      .map(_.flatten)
      .map(_.filter(issueMatches(filter, _)).slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0)))

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
