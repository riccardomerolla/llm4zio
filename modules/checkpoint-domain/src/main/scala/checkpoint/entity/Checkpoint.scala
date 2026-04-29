package checkpoint.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ CanvasId, CheckpointId, TaskRunId }

/** SPDD post-stage gate. A Checkpoint is the evidence that one of the SPDD
  * quality gates ran against a TaskRun: INVEST validation on a Story,
  * GWT validation on AC, Canvas completeness, Norms/Safeguards lint,
  * API or unit-test execution.
  *
  * Status moves Pending -> Running -> (Passed | Failed | Skipped).
  * Failures carry a list of CheckpointFinding so reviewers can see
  * exactly which item failed.
  */

enum CheckpointKind derives JsonCodec, Schema:
  case InvestValidated
  case GwtValidated
  case CanvasComplete
  case NormsLinted
  case SafeguardsChecked
  case ApiTestsPassed
  case UnitTestsPassed
  case Custom(name: String)

enum CheckpointStatus derives JsonCodec, Schema:
  case Pending
  case Running
  case Passed
  case Failed
  case Skipped

enum CheckpointFindingSeverity derives JsonCodec, Schema:
  case Info
  case Warning
  case Error

final case class CheckpointFinding(
  code: String,
  message: String,
  severity: CheckpointFindingSeverity,
) derives JsonCodec,
    Schema

final case class Checkpoint(
  id: CheckpointId,
  runId: TaskRunId,
  canvasId: Option[CanvasId],
  kind: CheckpointKind,
  status: CheckpointStatus,
  evidence: Option[String],
  findings: List[CheckpointFinding],
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object Checkpoint:
  def fromEvents(events: List[CheckpointEvent]): Either[String, Checkpoint] =
    events match
      case Nil => Left("Cannot rebuild Checkpoint from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Checkpoint]]](Right(None))((acc, e) => acc.flatMap(applyEvent(_, e)))
          .flatMap {
            case Some(checkpoint) => Right(checkpoint)
            case None             => Left("Checkpoint event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[Checkpoint],
    event: CheckpointEvent,
  ): Either[String, Option[Checkpoint]] =
    event match
      case created: CheckpointEvent.Created =>
        current match
          case Some(_) => Left(s"Checkpoint ${created.checkpointId.value} already initialized")
          case None    =>
            Right(
              Some(
                Checkpoint(
                  id = created.checkpointId,
                  runId = created.runId,
                  canvasId = created.canvasId,
                  kind = created.kind,
                  status = CheckpointStatus.Pending,
                  evidence = None,
                  findings = Nil,
                  startedAt = None,
                  completedAt = None,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )

      case started: CheckpointEvent.Started =>
        current
          .toRight(s"Checkpoint ${started.checkpointId.value} not initialized before Started")
          .flatMap { checkpoint =>
            checkpoint.status match
              case CheckpointStatus.Pending =>
                Right(
                  Some(
                    checkpoint.copy(
                      status = CheckpointStatus.Running,
                      startedAt = Some(started.occurredAt),
                      updatedAt = started.occurredAt,
                    )
                  )
                )
              case other                    =>
                Left(s"Checkpoint ${started.checkpointId.value} cannot transition $other -> Running")
          }

      case passed: CheckpointEvent.Passed =>
        current
          .toRight(s"Checkpoint ${passed.checkpointId.value} not initialized before Passed")
          .flatMap { checkpoint =>
            checkpoint.status match
              case CheckpointStatus.Running | CheckpointStatus.Pending =>
                Right(
                  Some(
                    checkpoint.copy(
                      status = CheckpointStatus.Passed,
                      evidence = passed.evidence.orElse(checkpoint.evidence),
                      completedAt = Some(passed.occurredAt),
                      updatedAt = passed.occurredAt,
                    )
                  )
                )
              case other                                                =>
                Left(s"Checkpoint ${passed.checkpointId.value} cannot transition $other -> Passed")
          }

      case failed: CheckpointEvent.Failed =>
        current
          .toRight(s"Checkpoint ${failed.checkpointId.value} not initialized before Failed")
          .flatMap { checkpoint =>
            checkpoint.status match
              case CheckpointStatus.Running | CheckpointStatus.Pending =>
                Right(
                  Some(
                    checkpoint.copy(
                      status = CheckpointStatus.Failed,
                      evidence = failed.evidence.orElse(checkpoint.evidence),
                      findings = checkpoint.findings ++ failed.findings,
                      completedAt = Some(failed.occurredAt),
                      updatedAt = failed.occurredAt,
                    )
                  )
                )
              case other                                                =>
                Left(s"Checkpoint ${failed.checkpointId.value} cannot transition $other -> Failed")
          }

      case skipped: CheckpointEvent.Skipped =>
        current
          .toRight(s"Checkpoint ${skipped.checkpointId.value} not initialized before Skipped")
          .map(checkpoint =>
            Some(
              checkpoint.copy(
                status = CheckpointStatus.Skipped,
                evidence = Some(skipped.reason),
                completedAt = Some(skipped.occurredAt),
                updatedAt = skipped.occurredAt,
              )
            )
          )
