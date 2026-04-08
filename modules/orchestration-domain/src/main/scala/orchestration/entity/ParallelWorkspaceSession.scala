package orchestration.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

final case class DiffStats(
  filesChanged: Int,
  linesAdded: Int,
  linesRemoved: Int,
) derives JsonCodec, Schema

enum WorktreeRunStatus derives JsonCodec, Schema:
  case Pending, Running, Completed, Failed, Cancelled

enum ParallelSessionStatus derives JsonCodec, Schema:
  case Pending, Running, Collecting, ReadyForReview, Completed, Failed, Cancelled

final case class WorktreeRunRef(
  stepId: String,
  workspaceRunId: String,
  agentName: String,
  branch: String,
  status: WorktreeRunStatus,
  summary: Option[String],
  diffStats: Option[DiffStats],
) derives JsonCodec, Schema

final case class ParallelWorkspaceSession(
  id: String,
  workflowId: String,
  correlationId: String,
  status: ParallelSessionStatus,
  baseBranch: String,
  worktreeRuns: List[WorktreeRunRef],
  createdAt: Instant,
  updatedAt: Instant,
  completedAt: Option[Instant],
  requestedBy: Option[String],
) derives JsonCodec, Schema

sealed trait ParallelSessionError derives JsonCodec
object ParallelSessionError:
  final case class WorkflowNotFound(workflowId: String)                  extends ParallelSessionError
  final case class WorkspaceNotFound(workspaceId: String)                extends ParallelSessionError
  final case class SessionNotFound(sessionId: String)                    extends ParallelSessionError
  final case class InsufficientResources(available: Int, required: Int)  extends ParallelSessionError
  final case class AgentAssignmentFailed(stepId: String, reason: String) extends ParallelSessionError
  final case class WorktreeError(detail: String)                         extends ParallelSessionError
