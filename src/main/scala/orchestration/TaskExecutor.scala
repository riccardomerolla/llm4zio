package orchestration

import java.util.UUID

import zio.*

import agents.AgentRegistry
import db.{ PersistenceError, RunStatus, TaskRepository }
import models.*

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

  val live
    : ZLayer[
      TaskRepository & AgentRegistry & WorkflowEngine & AgentDispatcher & OrchestratorControlPlane,
      Nothing,
      TaskExecutor,
    ] =
    ZLayer.fromZIO {
      for
        repository     <- ZIO.service[TaskRepository]
        registry       <- ZIO.service[AgentRegistry]
        workflowEngine <- ZIO.service[WorkflowEngine]
        dispatcher     <- ZIO.service[AgentDispatcher]
        controlPlane   <- ZIO.service[OrchestratorControlPlane]
        runningFibers  <- Ref.Synchronized.make(Map.empty[Long, Fiber.Runtime[PersistenceError, Unit]])
      yield TaskExecutorLive(
        repository = repository,
        registry = registry,
        workflowEngine = workflowEngine,
        dispatcher = dispatcher,
        controlPlane = controlPlane,
        runningFibers = runningFibers,
      )
    }

final case class TaskExecutorLive(
  repository: TaskRepository,
  registry: AgentRegistry,
  workflowEngine: WorkflowEngine,
  dispatcher: AgentDispatcher,
  controlPlane: OrchestratorControlPlane,
  runningFibers: Ref.Synchronized[Map[Long, Fiber.Runtime[PersistenceError, Unit]]],
) extends TaskExecutor:

  override def start(taskRunId: Long, workflow: WorkflowDefinition): UIO[Unit] =
    for
      effect <- ZIO.succeed(
                  execute(taskRunId, workflow)
                    .catchAll(err => ZIO.logError(s"Task execution failed for run=$taskRunId: $err"))
                )
      fiber  <- effect.ensuring(runningFibers.update(_ - taskRunId)).forkDaemon
      old    <- runningFibers.modify { fibers =>
                  (fibers.get(taskRunId), fibers.updated(taskRunId, fiber))
                }
      _      <- ZIO.foreachDiscard(old)(_.interrupt)
    yield ()

  override def cancel(taskRunId: Long): UIO[Unit] =
    runningFibers.modify { fibers =>
      (fibers.get(taskRunId), fibers - taskRunId)
    }.flatMap(old => ZIO.foreachDiscard(old)(_.interrupt))

  override def execute(taskRunId: Long, workflow: WorkflowDefinition): IO[PersistenceError, Unit] =
    for
      run           <- repository.getRun(taskRunId).someOrFail(PersistenceError.NotFound("task_runs", taskRunId))
      runId          = taskRunId.toString
      workflowId     = workflow.id.orElse(run.workflowId).getOrElse(0L)
      correlationId <- controlPlane
                         .startWorkflow(runId, workflowId, workflow)
                         .catchAll(_ => ZIO.succeed(UUID.randomUUID().toString))
      _             <- updateRun(
                         taskRunId = taskRunId,
                         status = RunStatus.Running,
                         currentPhase = workflow.steps.headOption,
                         errorMessage = None,
                       )
      _             <- controlPlane.updateRunState(runId, WorkflowRunState.Running).ignore
      candidates    <- registry.getAllAgents
      plan          <- workflowEngine
                         .buildPlan(workflow, WorkflowContext(), candidates)
                         .mapError(err => PersistenceError.QueryFailed("buildPlan", err.toString))
      _             <- ZIO.foreachDiscard(plan.batches) { batch =>
                         ZIO.foreachParDiscard(batch) { stepPlan =>
                           runStep(runId, correlationId, taskRunId, stepPlan)
                         }
                       }
      _             <- markCompleted(taskRunId, runId)
    yield ()

  private def runStep(
    runId: String,
    correlationId: String,
    taskRunId: Long,
    stepPlan: WorkflowStepPlan,
  ): IO[PersistenceError, Unit] =
    (for
      startedAt <- Clock.instant
      _         <- updateRun(
                     taskRunId = taskRunId,
                     status = RunStatus.Running,
                     currentPhase = Some(stepPlan.step),
                     errorMessage = None,
                   )
      _         <- publish(
                     StepStarted(
                       correlationId = correlationId,
                       runId = runId,
                       step = stepPlan.step,
                       assignedAgent =
                         stepPlan.assignedAgent.orElse(stepPlan.fallbackAgents.headOption).getOrElse("unassigned"),
                       timestamp = startedAt,
                     )
                   )
      _         <- publish(
                     StepProgress(
                       correlationId = correlationId,
                       runId = runId,
                       step = stepPlan.step,
                       itemsProcessed = 0,
                       itemsTotal = 1,
                       message = s"Executing ${stepPlan.step}",
                       timestamp = startedAt,
                     )
                   )
      result    <- dispatcher.dispatch(stepPlan, taskRunId)
      _         <- publish(
                     StepCompleted(
                       correlationId = correlationId,
                       runId = runId,
                       step = stepPlan.step,
                       status = WorkflowStatus.Completed,
                       timestamp = result.completedAt,
                     )
                   )
    yield ()).catchAll { err =>
      Clock.instant.flatMap { now =>
        publish(
          StepFailed(
            correlationId = correlationId,
            runId = runId,
            step = stepPlan.step,
            error = err.toString,
            timestamp = now,
          )
        ) *> markFailed(taskRunId, runId, err.toString, correlationId) *> ZIO.fail(err)
      }
    }

  private def markCompleted(taskRunId: Long, runId: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- updateRun(taskRunId, RunStatus.Completed, currentPhase = None, errorMessage = None, completedAt = Some(now))
      _   <- controlPlane.updateRunState(runId, WorkflowRunState.Completed).ignore
    yield ()

  private def markFailed(
    taskRunId: Long,
    runId: String,
    error: String,
    correlationId: String,
  ): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- updateRun(
               taskRunId,
               RunStatus.Failed,
               currentPhase = None,
               errorMessage = Some(error),
               completedAt = Some(now),
             )
      _   <- publish(
               WorkflowFailed(
                 correlationId = correlationId,
                 runId = runId,
                 error = error,
                 timestamp = now,
               )
             )
      _   <- controlPlane.updateRunState(runId, WorkflowRunState.Failed).ignore
    yield ()

  private def publish(event: ControlPlaneEvent): UIO[Unit] =
    controlPlane.publishEvent(event).ignore

  private def updateRun(
    taskRunId: Long,
    status: RunStatus,
    currentPhase: Option[String],
    errorMessage: Option[String],
    completedAt: Option[java.time.Instant] = None,
  ): IO[PersistenceError, Unit] =
    repository.getRun(taskRunId).flatMap {
      case None      => ZIO.fail(PersistenceError.NotFound("task_runs", taskRunId))
      case Some(run) =>
        repository.updateRun(
          run.copy(
            status = status,
            currentPhase = currentPhase,
            errorMessage = errorMessage,
            completedAt = completedAt.orElse(run.completedAt),
          )
        )
    }
