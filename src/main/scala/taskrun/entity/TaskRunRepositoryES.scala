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

  private val typedStore = dataStore.store

  private def snapshotKey(id: TaskRunId): String = s"snapshot:taskrun:${id.value}"

  private def snapshotPrefix: String = "snapshot:taskrun:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: TaskRunId): IO[PersistenceError, TaskRun] =
    for
      events <- eventStore.events(id)
      run    <- ZIO
                  .fromEither(TaskRun.fromEvents(events))
                  .mapError(msg => PersistenceError.SerializationFailed(s"taskrun:${id.value}", msg))
      _      <- typedStore.store(snapshotKey(id), run).mapError(storeErr("storeTaskRunSnapshot"))
    yield run

  override def append(event: TaskRunEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.runId, event)
      _ <- rebuildSnapshot(event.runId)
    yield ()

  override def get(id: TaskRunId): IO[PersistenceError, TaskRun] =
    typedStore
      .fetch[String, TaskRun](snapshotKey(id))
      .mapError(storeErr("getTaskRunSnapshot"))
      .flatMap {
        case Some(run) => ZIO.succeed(run)
        case None      =>
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
      .flatMap(keys =>
        ZIO.foreach(
          keys.toList
        )(key => typedStore.fetch[String, TaskRun](key).mapError(storeErr("listTaskRuns"))).map(_.flatten)
      )
      .map { runs =>
        runs
          .filter { run =>
            val workflowMatches = filter.workflowId.forall(_ == run.workflowId)
            val agentMatches    = filter.agentName.forall(expected => run.agentName.equalsIgnoreCase(expected.trim))
            val stateMatches    = filter.states.isEmpty || filter.states.contains(TaskRunStateTag.fromState(run.state))
            workflowMatches && agentMatches && stateMatches
          }
          .sortBy {
            case TaskRun(_, _, TaskRunState.Pending(createdAt), _, _, _, _)           => createdAt
            case TaskRun(_, _, TaskRunState.Running(startedAt, _), _, _, _, _)        => startedAt
            case TaskRun(_, _, TaskRunState.Completed(_, completedAt, _), _, _, _, _) => completedAt
            case TaskRun(_, _, TaskRunState.Failed(_, failedAt, _), _, _, _, _)       => failedAt
            case TaskRun(_, _, TaskRunState.Cancelled(cancelledAt, _), _, _, _, _)    => cancelledAt
          }(Ordering[java.time.Instant].reverse)
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
