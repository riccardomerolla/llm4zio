package orchestration.control

import java.time.Instant
import java.util.UUID

import gateway.entity.{ GatewayMessageRole, MessageDirection, NormalizedMessage, SessionKey }

object ParallelSessionFormatter:

  def toNormalizedMessage(
    event: ParallelSessionEvent,
    channel: String,
    sessionKey: SessionKey,
  ): NormalizedMessage =
    NormalizedMessage(
      id = UUID.randomUUID().toString,
      channelName = channel,
      sessionKey = sessionKey,
      direction = MessageDirection.Outbound,
      role = GatewayMessageRole.System,
      content = format(event),
      timestamp = event.occurredAt,
    )

  private def format(event: ParallelSessionEvent): String =
    event match
      case e: ParallelSessionEvent.SessionStarted =>
        s"Parallel session started: ${e.worktreeCount} agents dispatched"

      case e: ParallelSessionEvent.WorktreeAgentStarted =>
        s"Agent '${e.agentName}' started on branch ${e.branch}"

      case e: ParallelSessionEvent.WorktreeAgentProgress =>
        s"Agent '${e.agentName}': ${e.message}"

      case e: ParallelSessionEvent.WorktreeAgentCompleted =>
        s"Agent '${e.agentName}' completed on ${e.branch}\n" +
          s"+${e.diffStats.linesAdded} / -${e.diffStats.linesRemoved} across ${e.diffStats.filesChanged} files"

      case e: ParallelSessionEvent.WorktreeAgentFailed =>
        s"Agent '${e.agentName}' failed: ${e.reason}"

      case e: ParallelSessionEvent.SessionReadyForReview =>
        val branchList = e.branches.map(b => s"• $b").mkString("\n")
        s"All agents done — ${e.succeeded} succeeded, ${e.failed} failed\nBranches ready:\n$branchList"
