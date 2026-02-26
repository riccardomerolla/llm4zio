package workspace.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

sealed trait RunMode derives JsonCodec, Schema
object RunMode:
  /** Run the agent directly on the host (default, current behaviour). */
  case object Host extends RunMode

  /** Run the agent inside a Docker container. */
  final case class Docker(
    image: String,
    extraArgs: List[String] = List.empty,
    mountWorktree: Boolean = true,
    network: Option[String] = None,
  ) extends RunMode

case class Workspace(
  id: String,
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean = true,
  runMode: RunMode = RunMode.Host,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

sealed trait RunStatus derives JsonCodec, Schema
object RunStatus:
  case object Pending   extends RunStatus
  case object Running   extends RunStatus
  case object Completed extends RunStatus
  case object Failed    extends RunStatus

case class WorkspaceRun(
  id: String,
  workspaceId: String,
  issueRef: String,
  agentName: String,
  prompt: String,
  conversationId: String,
  worktreePath: String,
  branchName: String,
  status: RunStatus,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum WorkspaceError:
  case NotFound(id: String)
  case Disabled(id: String)
  case WorktreeError(message: String)
  case AgentNotFound(name: String)
  case RunTimeout(runId: String)
  case PersistenceFailure(cause: Throwable)
  case DockerNotAvailable(reason: String)
