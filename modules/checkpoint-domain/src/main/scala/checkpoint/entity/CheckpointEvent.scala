package checkpoint.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ CanvasId, CheckpointId, TaskRunId }

sealed trait CheckpointEvent derives JsonCodec, Schema:
  def checkpointId: CheckpointId
  def occurredAt: Instant

object CheckpointEvent:
  final case class Created(
    checkpointId: CheckpointId,
    runId: TaskRunId,
    canvasId: Option[CanvasId],
    kind: CheckpointKind,
    occurredAt: Instant,
  ) extends CheckpointEvent

  final case class Started(
    checkpointId: CheckpointId,
    occurredAt: Instant,
  ) extends CheckpointEvent

  final case class Passed(
    checkpointId: CheckpointId,
    evidence: Option[String],
    occurredAt: Instant,
  ) extends CheckpointEvent

  final case class Failed(
    checkpointId: CheckpointId,
    evidence: Option[String],
    findings: List[CheckpointFinding],
    occurredAt: Instant,
  ) extends CheckpointEvent

  final case class Skipped(
    checkpointId: CheckpointId,
    reason: String,
    occurredAt: Instant,
  ) extends CheckpointEvent
