package taskrun.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.TaskRunId
import shared.store.{ DataStoreModule, EventStore }

final case class TaskRunRepositoryES(
  eventStore: EventStore[TaskRunId, TaskRunEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends TaskRunRepository:

  private def snapshotKey(id: TaskRunId): String = s"snapshot:taskrun:${id.value}"

  private def snapshotPrefix: String = "snapshot:taskrun:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: TaskRunId): IO[PersistenceError, TaskRun] =
    for
      events <- eventStore.events(id)
      _      <- ZIO.logDebug(s"Rebuilding taskrun snapshot for ${id.value} from ${events.size} events")
      run    <- ZIO
                  .fromEither(TaskRun.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"taskrun:${id.value}", msg))
      _      <- dataStore.store(snapshotKey(id), run).mapError(storeErr("storeTaskRunSnapshot"))
      _      <- ZIO.logDebug(s"Stored taskrun snapshot: ${snapshotKey(id)} [state=${run.state.getClass.getSimpleName}]")
    yield run

  override def append(event: TaskRunEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.runId, event)
      _ <- rebuildSnapshot(event.runId)
    yield ()

  override def get(id: TaskRunId): IO[PersistenceError, TaskRun] =
    dataStore
      .fetch[String, TaskRun](snapshotKey(id))
      .mapError(storeErr("getTaskRunSnapshot"))
      .flatMap {
        case Some(run) =>
          ZIO.logDebug(
            s"Fetched taskrun snapshot from store: ${snapshotKey(id)} [state=${run.state.getClass.getSimpleName}]"
          ) *>
            ZIO.succeed(run)
        case None      =>
          ZIO.logDebug(s"No snapshot found for taskrun:${id.value} — rebuilding from events") *>
            eventStore.events(id).flatMap {
              case Nil => ZIO.fail(PersistenceError.NotFound("taskrun", id.value))
              case _   => rebuildSnapshot(id)
            }
      }

  override def list(filter: TaskRunFilter): IO[PersistenceError, List[TaskRun]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listTaskRuns"))
      .flatMap { keys =>
        ZIO.logDebug(s"Scanning ${keys.size} taskrun snapshot key(s) from store") *>
          ZIO
            .foreach(keys.toList)(key => dataStore.fetch[String, TaskRun](key).mapError(storeErr("listTaskRuns")))
            .map(_.flatten)
      }
      .tap(all => ZIO.logDebug(s"Recovered ${all.size} taskrun object(s) from store"))
      .map { runs =>
        runs
          .filter { run =>
            val workflowMatches = filter.workflowId.forall(_ == run.workflowId)
            val agentMatches    = filter.agentName.forall(expected => run.agentName.equalsIgnoreCase(expected.trim))
            val stateMatches    = filter.states.isEmpty || filter.states.contains(TaskRunStateTag.fromState(run.state))
            workflowMatches && agentMatches && stateMatches
          }
          .sortBy(run =>
            run.state match
              case TaskRunState.Pending(createdAt)           => createdAt
              case TaskRunState.Running(startedAt, _)        => startedAt
              case TaskRunState.Completed(_, completedAt, _) => completedAt
              case TaskRunState.Failed(_, failedAt, _)       => failedAt
              case TaskRunState.Cancelled(cancelledAt, _)    => cancelledAt
          )(Ordering[java.time.Instant].reverse)
          .slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0))
      }

object TaskRunRepositoryES:
  val live: ZLayer[EventStore[TaskRunId, TaskRunEvent] & DataStoreModule.DataStoreService, Nothing, TaskRunRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[TaskRunId, TaskRunEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield TaskRunRepositoryES(eventStore, dataStore)
    }
