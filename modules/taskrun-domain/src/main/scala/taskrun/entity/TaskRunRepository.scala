package taskrun.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ TaskRunId, WorkflowId }

final case class TaskRunFilter(
  workflowId: Option[WorkflowId] = None,
  agentName: Option[String] = None,
  states: Set[TaskRunStateTag] = Set.empty,
  offset: Int = 0,
  limit: Int = 100,
)

enum TaskRunStateTag:
  case Pending, Running, Completed, Failed, Cancelled

object TaskRunStateTag:
  def fromState(state: TaskRunState): TaskRunStateTag =
    state match
      case _: TaskRunState.Pending   => TaskRunStateTag.Pending
      case _: TaskRunState.Running   => TaskRunStateTag.Running
      case _: TaskRunState.Completed => TaskRunStateTag.Completed
      case _: TaskRunState.Failed    => TaskRunStateTag.Failed
      case _: TaskRunState.Cancelled => TaskRunStateTag.Cancelled

trait TaskRunRepository:
  def append(event: TaskRunEvent): IO[PersistenceError, Unit]
  def get(id: TaskRunId): IO[PersistenceError, TaskRun]
  def list(filter: TaskRunFilter): IO[PersistenceError, List[TaskRun]]

object TaskRunRepository:
  def append(event: TaskRunEvent): ZIO[TaskRunRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskRunRepository](_.append(event))

  def get(id: TaskRunId): ZIO[TaskRunRepository, PersistenceError, TaskRun] =
    ZIO.serviceWithZIO[TaskRunRepository](_.get(id))

  def list(filter: TaskRunFilter): ZIO[TaskRunRepository, PersistenceError, List[TaskRun]] =
    ZIO.serviceWithZIO[TaskRunRepository](_.list(filter))
