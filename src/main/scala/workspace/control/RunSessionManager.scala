package workspace.control

import zio.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import conversation.entity.api.{ ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import shared.ids.Ids.{ EventId, TaskRunId }
import workspace.entity.*

trait RunSessionManager:
  def getSession(runId: String): IO[WorkspaceError, RunSession]
  def attach(runId: String, userId: String): IO[WorkspaceError, RunSession]
  def detach(runId: String, userId: String): IO[WorkspaceError, Unit]
  def interrupt(runId: String, userId: String): IO[WorkspaceError, Unit]
  def resume(runId: String, userId: String, prompt: String): IO[WorkspaceError, Unit]
  def sendMessage(runId: String, userId: String, content: String)
    : IO[WorkspaceError, Either[RunInputRejected, RunInputAccepted]]

object RunSessionManager:
  val live
    : ZLayer[WorkspaceRepository & InteractiveAgentRunner & ChatRepository & ActivityHub, Nothing, RunSessionManager] =
    ZLayer.fromZIO {
      for
        repo     <- ZIO.service[WorkspaceRepository]
        runner   <- ZIO.service[InteractiveAgentRunner]
        chatRepo <- ZIO.service[ChatRepository]
        activity <- ZIO.service[ActivityHub]
      yield RunSessionManagerLive(repo, runner, chatRepo, event => activity.publish(event))
    }

  def getSession(runId: String): ZIO[RunSessionManager, WorkspaceError, RunSession] =
    ZIO.serviceWithZIO[RunSessionManager](_.getSession(runId))

  def attach(runId: String, userId: String): ZIO[RunSessionManager, WorkspaceError, RunSession] =
    ZIO.serviceWithZIO[RunSessionManager](_.attach(runId, userId))

  def detach(runId: String, userId: String): ZIO[RunSessionManager, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[RunSessionManager](_.detach(runId, userId))

  def interrupt(runId: String, userId: String): ZIO[RunSessionManager, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[RunSessionManager](_.interrupt(runId, userId))

  def resume(runId: String, userId: String, prompt: String): ZIO[RunSessionManager, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[RunSessionManager](_.resume(runId, userId, prompt))

  def sendMessage(
    runId: String,
    userId: String,
    content: String,
  ): ZIO[RunSessionManager, WorkspaceError, Either[RunInputRejected, RunInputAccepted]] =
    ZIO.serviceWithZIO[RunSessionManager](_.sendMessage(runId, userId, content))

final case class RunSessionManagerLive(
  workspaceRepo: WorkspaceRepository,
  interactiveRunner: InteractiveAgentRunner,
  chatRepo: ChatRepository,
  publishActivity: ActivityEvent => UIO[Unit],
) extends RunSessionManager:

  override def getSession(runId: String): IO[WorkspaceError, RunSession] =
    getRunOrFail(runId).map(r => RunSession(r.id, r.status, r.attachedUsers, r.controllerUserId))

  override def attach(runId: String, userId: String): IO[WorkspaceError, RunSession] =
    for
      run <- getRunOrFail(runId)
      _   <- ensureRunning(run)
      now <- Clock.instant
      _   <- workspaceRepo
               .appendRun(WorkspaceRunEvent.UserAttached(runId, userId, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      out <- getRunOrFail(runId).map(r => RunSession(r.id, r.status, r.attachedUsers, r.controllerUserId))
      _   <- publishState(runId, s"User $userId attached", out.status)
    yield out

  override def detach(runId: String, userId: String): IO[WorkspaceError, Unit] =
    for
      _   <- getRunOrFail(runId)
      now <- Clock.instant
      _   <- workspaceRepo
               .appendRun(WorkspaceRunEvent.UserDetached(runId, userId, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      run <- getRunOrFail(runId)
      _   <- publishState(runId, s"User $userId detached", run.status)
    yield ()

  override def interrupt(runId: String, userId: String): IO[WorkspaceError, Unit] =
    for
      run <- getRunOrFail(runId)
      _   <- ensureController(run, userId)
      _   <- ensureRunning(run)
      _   <- interactiveRunner
               .resolve(AgentProcessRef(runId))
               .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
               .flatMap {
                 case Some(process) =>
                   interactiveRunner.pause(process).mapError(e => WorkspaceError.WorktreeError(e.getMessage))
                 case None          => ZIO.fail(WorkspaceError.InteractiveProcessUnavailable(runId))
               }
      now <- Clock.instant
      _   <- workspaceRepo
               .appendRun(WorkspaceRunEvent.RunInterrupted(runId, userId, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _   <- publishState(runId, s"Run interrupted by $userId", RunStatus.Running(RunSessionMode.Paused))
    yield ()

  override def resume(runId: String, userId: String, prompt: String): IO[WorkspaceError, Unit] =
    for
      run <- getRunOrFail(runId)
      _   <- ensureController(run, userId)
      _   <- ensurePaused(run)
      _   <- interactiveRunner
               .resolve(AgentProcessRef(runId))
               .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
               .flatMap {
                 case Some(process) =>
                   interactiveRunner.resume(process).mapError(e => WorkspaceError.WorktreeError(e.getMessage)) *>
                     interactiveRunner.sendInput(process, prompt).mapError(e =>
                       WorkspaceError.WorktreeError(e.getMessage)
                     )
                 case None          => ZIO.fail(WorkspaceError.InteractiveProcessUnavailable(runId))
               }
      now <- Clock.instant
      _   <- workspaceRepo
               .appendRun(WorkspaceRunEvent.RunResumed(runId, userId, prompt, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _   <- publishState(runId, s"Run resumed by $userId", RunStatus.Running(RunSessionMode.Interactive))
    yield ()

  override def sendMessage(
    runId: String,
    userId: String,
    content: String,
  ): IO[WorkspaceError, Either[RunInputRejected, RunInputAccepted]] =
    for
      run          <- getRunOrFail(runId)
      messageIdOpt <- appendRunUserMessage(run, userId, content, run.status)
      ws           <- workspaceRepo
                        .get(run.workspaceId)
                        .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                        .flatMap(
                          _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(run.workspaceId)))(ZIO.succeed)
                        )
      result       <- (CliAgentRunner.interactionSupport(ws.cliTool), run.status) match
                        case (
                               CliAgentRunner.InteractionSupport.InteractiveStdin,
                               RunStatus.Running(RunSessionMode.Interactive),
                             ) =>
                          interactiveRunner
                            .resolve(AgentProcessRef(runId))
                            .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
                            .flatMap {
                              case Some(process) =>
                                interactiveRunner.sendInput(process, content).mapError(e =>
                                  WorkspaceError.WorktreeError(e.getMessage)
                                ) *>
                                  ZIO.succeed(Right(RunInputAccepted(runId, messageIdOpt.getOrElse(0L))))
                              case None          =>
                                ZIO.succeed(
                                  Left(
                                    RunInputRejected(
                                      runId = runId,
                                      reason = "Interactive process is not available for this run",
                                      queuedMessageId = messageIdOpt,
                                    )
                                  )
                                )
                            }
                        case (_, RunStatus.Running(RunSessionMode.Autonomous))       =>
                          ZIO.succeed(
                            Left(
                              RunInputRejected(
                                runId = runId,
                                reason = "Run is autonomous; message queued for continuation",
                                queuedMessageId = messageIdOpt,
                              )
                            )
                          )
                        case (_, RunStatus.Running(RunSessionMode.Paused))           =>
                          ZIO.succeed(
                            Left(
                              RunInputRejected(
                                runId = runId,
                                reason = "Run is paused; use ContinueRun to resume with instructions",
                                queuedMessageId = messageIdOpt,
                              )
                            )
                          )
                        case (CliAgentRunner.InteractionSupport.ContinuationOnly, _) =>
                          ZIO.succeed(
                            Left(
                              RunInputRejected(
                                runId = runId,
                                reason = "CLI tool does not support interactive input; message queued for continuation",
                                queuedMessageId = messageIdOpt,
                              )
                            )
                          )
                        case _                                                       =>
                          ZIO.succeed(
                            Left(
                              RunInputRejected(
                                runId = runId,
                                reason = s"Run is not accepting interactive input in state ${run.status}",
                                queuedMessageId = messageIdOpt,
                              )
                            )
                          )
      _            <- publishState(runId, s"User $userId sent run input", run.status)
    yield result

  private def getRunOrFail(runId: String): IO[WorkspaceError, WorkspaceRun] =
    workspaceRepo
      .getRun(runId)
      .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      .flatMap(_.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed))

  private def ensureRunning(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    run.status match
      case RunStatus.Running(_) => ZIO.unit
      case other                =>
        ZIO.fail(WorkspaceError.InvalidRunState(run.id, "Running", other.toString))

  private def ensurePaused(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    run.status match
      case RunStatus.Running(RunSessionMode.Paused) => ZIO.unit
      case other                                    =>
        ZIO.fail(WorkspaceError.InvalidRunState(run.id, "Running(Paused)", other.toString))

  private def ensureController(run: WorkspaceRun, userId: String): IO[WorkspaceError, Unit] =
    run.controllerUserId match
      case Some(controller) if controller != userId =>
        ZIO.fail(WorkspaceError.ControllerConflict(run.id, controller, userId))
      case _                                        => ZIO.unit

  private def publishState(runId: String, summary: String, status: RunStatus): UIO[Unit] =
    Clock.instant.flatMap { now =>
      publishActivity(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.RunStateChanged,
          source = "run-session-manager",
          runId = Some(TaskRunId(runId)),
          summary = summary,
          payload = Some(status.toJson),
          createdAt = now,
        )
      )
    }

  private def appendRunUserMessage(
    run: WorkspaceRun,
    userId: String,
    content: String,
    status: RunStatus,
  ): IO[WorkspaceError, Option[Long]] =
    for
      now       <- Clock.instant
      metadata   = s"""{"source":"run-websocket","runId":"${run.id}","userId":"$userId","status":"${status.toString}"}"""
      messageId <- chatRepo
                     .addMessage(
                       ConversationEntry(
                         conversationId = run.conversationId,
                         sender = userId,
                         senderType = SenderType.User,
                         content = content,
                         messageType = MessageType.Text,
                         metadata = Some(metadata),
                         createdAt = now,
                         updatedAt = now,
                       )
                     )
                     .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
    yield Some(messageId)
