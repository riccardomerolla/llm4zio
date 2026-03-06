package taskrun.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.TaskRunId

sealed trait TaskRunEvent derives JsonCodec, Schema:
  def runId: TaskRunId
  def occurredAt: Instant

object TaskRunEvent:
  final case class Created(
    runId: TaskRunId,
    workflowId: shared.ids.Ids.WorkflowId,
    agentName: String,
    source: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class Started(
    runId: TaskRunId,
    phase: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class PhaseChanged(
    runId: TaskRunId,
    phase: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class ReportAdded(
    runId: TaskRunId,
    report: TaskReport,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class ArtifactAdded(
    runId: TaskRunId,
    artifact: TaskArtifact,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class Completed(
    runId: TaskRunId,
    summary: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class Failed(
    runId: TaskRunId,
    errorMessage: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class Cancelled(
    runId: TaskRunId,
    reason: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class WalkthroughGenerated(
    runId: TaskRunId,
    summary: String,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class PrLinked(
    runId: TaskRunId,
    prUrl: String,
    prStatus: PrStatus,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class CiStatusUpdated(
    runId: TaskRunId,
    ciStatus: CiStatus,
    occurredAt: Instant,
  ) extends TaskRunEvent

  final case class TokenUsageRecorded(
    runId: TaskRunId,
    inputTokens: Long,
    outputTokens: Long,
    runtimeSeconds: Long,
    occurredAt: Instant,
  ) extends TaskRunEvent
