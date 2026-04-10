package orchestration.control

import java.time.Instant

import zio.json.*

import orchestration.entity.DiffStats

sealed trait ParallelSessionEvent derives JsonCodec:
  def sessionId: String
  def occurredAt: Instant

object ParallelSessionEvent:
  final case class SessionStarted(
    sessionId: String,
    workflowId: String,
    worktreeCount: Int,
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec

  final case class WorktreeAgentStarted(
    sessionId: String,
    stepId: String,
    agentName: String,
    branch: String,
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec

  final case class WorktreeAgentProgress(
    sessionId: String,
    stepId: String,
    agentName: String,
    message: String,
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec

  final case class WorktreeAgentCompleted(
    sessionId: String,
    stepId: String,
    agentName: String,
    branch: String,
    diffStats: DiffStats,
    summary: String,
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec

  final case class WorktreeAgentFailed(
    sessionId: String,
    stepId: String,
    agentName: String,
    reason: String,
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec

  final case class SessionReadyForReview(
    sessionId: String,
    succeeded: Int,
    failed: Int,
    branches: List[String],
    occurredAt: Instant,
  ) extends ParallelSessionEvent
    derives JsonCodec
