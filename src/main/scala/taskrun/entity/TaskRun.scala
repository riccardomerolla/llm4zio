package taskrun.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ArtifactId, ReportId, TaskRunId, WorkflowId }

enum TaskRunState derives JsonCodec, Schema:
  case Pending(createdAt: Instant)
  case Running(startedAt: Instant, currentPhase: String)
  case Completed(startedAt: Instant, completedAt: Instant, summary: String)
  case Failed(startedAt: Instant, failedAt: Instant, errorMessage: String)
  case Cancelled(cancelledAt: Instant, reason: String)

final case class TaskReport(
  id: ReportId,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives JsonCodec, Schema

final case class TaskArtifact(
  id: ArtifactId,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives JsonCodec, Schema

final case class TaskRun(
  id: TaskRunId,
  workflowId: WorkflowId,
  state: TaskRunState,
  agentName: String,
  source: String,
  reports: List[TaskReport],
  artifacts: List[TaskArtifact],
) derives JsonCodec, Schema

object TaskRun:
  def fromEvents(events: List[TaskRunEvent]): Either[String, TaskRun] =
    events match
      case Nil => Left("Cannot rebuild TaskRun from an empty event stream")
      case _   =>
        events.foldLeft[Either[String, Option[TaskRun]]](Right(None)) { (acc, event) =>
          acc.flatMap(current => applyEvent(current, event))
        }.flatMap {
          case Some(run) => Right(run)
          case None      => Left("TaskRun stream did not produce a state")
        }

  private def applyEvent(current: Option[TaskRun], event: TaskRunEvent): Either[String, Option[TaskRun]] =
    event match
      case created: TaskRunEvent.Created =>
        current match
          case Some(_) =>
            Left(s"TaskRun ${created.runId.value} already initialized")
          case None    =>
            Right(
              Some(
                TaskRun(
                  id = created.runId,
                  workflowId = created.workflowId,
                  state = TaskRunState.Pending(created.occurredAt),
                  agentName = created.agentName,
                  source = created.source,
                  reports = Nil,
                  artifacts = Nil,
                )
              )
            )

      case started: TaskRunEvent.Started =>
        current
          .toRight(s"TaskRun ${started.runId.value} not initialized before Started event")
          .map { run =>
            val startedAt = run.state match
              case TaskRunState.Running(existingStartedAt, _) => existingStartedAt
              case _                                          => started.occurredAt
            Some(run.copy(state = TaskRunState.Running(startedAt, started.phase)))
          }

      case changed: TaskRunEvent.PhaseChanged =>
        current
          .toRight(s"TaskRun ${changed.runId.value} not initialized before PhaseChanged event")
          .flatMap { run =>
            run.state match
              case TaskRunState.Running(startedAt, _) =>
                Right(Some(run.copy(state = TaskRunState.Running(startedAt, changed.phase))))
              case _                                  =>
                Left(s"Cannot change phase for task run ${changed.runId.value} outside Running state")
          }

      case added: TaskRunEvent.ReportAdded =>
        current
          .toRight(s"TaskRun ${added.runId.value} not initialized before ReportAdded event")
          .map(run => Some(run.copy(reports = run.reports :+ added.report)))

      case added: TaskRunEvent.ArtifactAdded =>
        current
          .toRight(s"TaskRun ${added.runId.value} not initialized before ArtifactAdded event")
          .map(run => Some(run.copy(artifacts = run.artifacts :+ added.artifact)))

      case completed: TaskRunEvent.Completed =>
        current
          .toRight(s"TaskRun ${completed.runId.value} not initialized before Completed event")
          .flatMap { run =>
            run.state match
              case TaskRunState.Running(startedAt, _) =>
                Right(Some(run.copy(state =
                  TaskRunState.Completed(startedAt, completed.occurredAt, completed.summary)
                )))
              case _                                  =>
                Left(s"Cannot complete task run ${completed.runId.value} outside Running state")
          }

      case failed: TaskRunEvent.Failed =>
        current
          .toRight(s"TaskRun ${failed.runId.value} not initialized before Failed event")
          .flatMap { run =>
            run.state match
              case TaskRunState.Running(startedAt, _) =>
                Right(Some(run.copy(state = TaskRunState.Failed(startedAt, failed.occurredAt, failed.errorMessage))))
              case _                                  =>
                Left(s"Cannot fail task run ${failed.runId.value} outside Running state")
          }

      case cancelled: TaskRunEvent.Cancelled =>
        current
          .toRight(s"TaskRun ${cancelled.runId.value} not initialized before Cancelled event")
          .map(run => Some(run.copy(state = TaskRunState.Cancelled(cancelled.occurredAt, cancelled.reason))))
