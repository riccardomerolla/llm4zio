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

sealed trait RunStatus derives JsonCodec, Schema
object RunStatus:
  case object Pending                            extends RunStatus
  final case class Running(mode: RunSessionMode) extends RunStatus
  case object Completed                          extends RunStatus
  case object Failed                             extends RunStatus
  case object Cancelled                          extends RunStatus

sealed trait RunSessionMode derives JsonCodec, Schema
object RunSessionMode:
  case object Autonomous  extends RunSessionMode
  case object Interactive extends RunSessionMode
  case object Paused      extends RunSessionMode

/** Read-side projection of the Workspace aggregate. Rebuilt by folding [[WorkspaceEvent]]s. Never persisted directly as
  * a mutable record — only as a snapshot cache for fast reads.
  */
case class Workspace(
  id: String,
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean,
  runMode: RunMode,
  cliTool: String,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

object Workspace:
  def fromEvents(events: List[WorkspaceEvent]): Either[String, Workspace] =
    events match
      case Nil => Left("Cannot rebuild Workspace from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Workspace]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(ws) => Right(ws)
            case None     => Left("Workspace event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[Workspace],
    event: WorkspaceEvent,
  ): Either[String, Option[Workspace]] =
    event match
      case e: WorkspaceEvent.Created =>
        current match
          case Some(_) => Left(s"Workspace ${e.workspaceId} already initialised")
          case None    =>
            Right(
              Some(
                Workspace(
                  id = e.workspaceId,
                  name = e.name,
                  localPath = e.localPath,
                  defaultAgent = e.defaultAgent,
                  description = e.description,
                  enabled = true,
                  runMode = e.runMode,
                  cliTool = e.cliTool,
                  createdAt = e.occurredAt,
                  updatedAt = e.occurredAt,
                )
              )
            )

      case e: WorkspaceEvent.Updated =>
        current
          .toRight(s"Workspace ${e.workspaceId} not initialised before Updated event")
          .map(ws =>
            Some(
              ws.copy(
                name = e.name,
                localPath = e.localPath,
                defaultAgent = e.defaultAgent,
                description = e.description,
                cliTool = e.cliTool,
                runMode = e.runMode,
                updatedAt = e.occurredAt,
              )
            )
          )

      case e: WorkspaceEvent.Enabled =>
        current
          .toRight(s"Workspace ${e.workspaceId} not initialised before Enabled event")
          .map(ws => Some(ws.copy(enabled = true, updatedAt = e.occurredAt)))

      case e: WorkspaceEvent.Disabled =>
        current
          .toRight(s"Workspace ${e.workspaceId} not initialised before Disabled event")
          .map(ws => Some(ws.copy(enabled = false, updatedAt = e.occurredAt)))

      case _: WorkspaceEvent.Deleted => Right(None)

/** Read-side projection of the WorkspaceRun aggregate. */
case class WorkspaceRun(
  id: String,
  workspaceId: String,
  parentRunId: Option[String],
  issueRef: String,
  agentName: String,
  prompt: String,
  conversationId: String,
  worktreePath: String,
  branchName: String,
  status: RunStatus,
  attachedUsers: Set[String],
  controllerUserId: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

final case class AgentProcessRef(runId: String) derives JsonCodec, Schema
final case class RunSession(
  runId: String,
  status: RunStatus,
  attachedUsers: Set[String],
  controllerUserId: Option[String],
) derives JsonCodec,
    Schema
final case class RunInputAccepted(
  runId: String,
  messageId: Long,
  queuedForContinuation: Boolean = false,
) derives JsonCodec,
    Schema
final case class RunInputRejected(
  runId: String,
  reason: String,
  queuedMessageId: Option[Long] = None,
) derives JsonCodec,
    Schema

object WorkspaceRun:
  def fromEvents(events: List[WorkspaceRunEvent]): Either[String, WorkspaceRun] =
    events match
      case Nil => Left("Cannot rebuild WorkspaceRun from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[WorkspaceRun]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(run) => Right(run)
            case None      => Left("WorkspaceRun event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[WorkspaceRun],
    event: WorkspaceRunEvent,
  ): Either[String, Option[WorkspaceRun]] =
    event match
      case e: WorkspaceRunEvent.Assigned =>
        current match
          case Some(_) => Left(s"WorkspaceRun ${e.runId} already initialised")
          case None    =>
            Right(
              Some(
                WorkspaceRun(
                  id = e.runId,
                  workspaceId = e.workspaceId,
                  parentRunId = e.parentRunId,
                  issueRef = e.issueRef,
                  agentName = e.agentName,
                  prompt = e.prompt,
                  conversationId = e.conversationId,
                  worktreePath = e.worktreePath,
                  branchName = e.branchName,
                  status = RunStatus.Pending,
                  attachedUsers = Set.empty,
                  controllerUserId = None,
                  createdAt = e.occurredAt,
                  updatedAt = e.occurredAt,
                )
              )
            )

      case e: WorkspaceRunEvent.StatusChanged =>
        current
          .toRight(s"WorkspaceRun ${e.runId} not initialised before StatusChanged event")
          .map(run => Some(run.copy(status = e.status, updatedAt = e.occurredAt)))

      case e: WorkspaceRunEvent.UserAttached =>
        current
          .toRight(s"WorkspaceRun ${e.runId} not initialised before UserAttached event")
          .map { run =>
            val updatedUsers   = run.attachedUsers + e.userId
            val nextController = run.controllerUserId.orElse(Some(e.userId))
            val nextStatus     = run.status match
              case RunStatus.Running(RunSessionMode.Autonomous) =>
                RunStatus.Running(RunSessionMode.Interactive)
              case other                                        => other
            Some(
              run.copy(
                status = nextStatus,
                attachedUsers = updatedUsers,
                controllerUserId = nextController,
                updatedAt = e.occurredAt,
              )
            )
          }

      case e: WorkspaceRunEvent.UserDetached =>
        current
          .toRight(s"WorkspaceRun ${e.runId} not initialised before UserDetached event")
          .map { run =>
            val updatedUsers   = run.attachedUsers - e.userId
            val nextController =
              run.controllerUserId match
                case Some(currentController) if currentController == e.userId =>
                  updatedUsers.headOption
                case other                                                    => other
            val nextStatus     = run.status match
              case RunStatus.Running(RunSessionMode.Interactive) if updatedUsers.isEmpty =>
                RunStatus.Running(RunSessionMode.Autonomous)
              case other                                                                 => other
            Some(
              run.copy(
                status = nextStatus,
                attachedUsers = updatedUsers,
                controllerUserId = nextController,
                updatedAt = e.occurredAt,
              )
            )
          }

      case e: WorkspaceRunEvent.RunInterrupted =>
        current
          .toRight(s"WorkspaceRun ${e.runId} not initialised before RunInterrupted event")
          .map { run =>
            Some(
              run.copy(
                status = RunStatus.Running(RunSessionMode.Paused),
                attachedUsers = run.attachedUsers + e.userId,
                controllerUserId = Some(e.userId),
                updatedAt = e.occurredAt,
              )
            )
          }

      case e: WorkspaceRunEvent.RunResumed =>
        current
          .toRight(s"WorkspaceRun ${e.runId} not initialised before RunResumed event")
          .map { run =>
            Some(
              run.copy(
                status = RunStatus.Running(RunSessionMode.Interactive),
                attachedUsers = run.attachedUsers + e.userId,
                controllerUserId = Some(e.userId),
                updatedAt = e.occurredAt,
              )
            )
          }

enum WorkspaceError:
  case NotFound(id: String)
  case Disabled(id: String)
  case WorktreeError(message: String)
  case AgentNotFound(name: String)
  case RunTimeout(runId: String)
  case PersistenceFailure(cause: Throwable)
  case DockerNotAvailable(reason: String)
  case InvalidRunState(runId: String, expected: String, actual: String)
  case ControllerConflict(runId: String, controller: String, requestedBy: String)
  case InteractiveProcessUnavailable(runId: String)
