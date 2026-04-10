package orchestration.entity

import zio.*

import _root_.config.entity.WorkflowDefinition
import shared.errors.PersistenceError

trait TaskExecutor:
  def execute(taskRunId: Long, workflow: WorkflowDefinition): IO[PersistenceError, Unit]
  def start(taskRunId: Long, workflow: WorkflowDefinition): UIO[Unit]
  def cancel(taskRunId: Long): UIO[Unit]

object TaskExecutor:
  def execute(taskRunId: Long, workflow: WorkflowDefinition): ZIO[TaskExecutor, PersistenceError, Unit] =
    ZIO.serviceWithZIO[TaskExecutor](_.execute(taskRunId, workflow))

  def start(taskRunId: Long, workflow: WorkflowDefinition): ZIO[TaskExecutor, Nothing, Unit] =
    ZIO.serviceWithZIO[TaskExecutor](_.start(taskRunId, workflow))

  def cancel(taskRunId: Long): ZIO[TaskExecutor, Nothing, Unit] =
    ZIO.serviceWithZIO[TaskExecutor](_.cancel(taskRunId))
