package workspace.control

import java.nio.file.Paths

import zio.*
import zio.json.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import workspace.entity.*

case class AssignRunRequest(issueRef: String, prompt: String, agentName: String) derives JsonCodec

trait WorkspaceRunService:
  def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun]
  def continueRun(runId: String, followUpPrompt: String): IO[WorkspaceError, Unit]

object WorkspaceRunService:
  val live: ZLayer[WorkspaceRepository & ChatRepository, Nothing, WorkspaceRunService] =
    ZLayer.fromFunction((repo: WorkspaceRepository, chat: ChatRepository) =>
      WorkspaceRunServiceLive(repo, chat)
    )

object WorkspaceRunServiceLive:
  val defaultWorktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] =
    (repoPath, wtPath, branch) =>
      ZIO
        .attemptBlockingIO {
          val pb   = new ProcessBuilder("git", "worktree", "add", wtPath, "-b", branch)
          pb.directory(Paths.get(repoPath).toFile)
          pb.redirectErrorStream(true)
          val proc = pb.start()
          val out  = scala.io.Source.fromInputStream(proc.getInputStream).mkString
          val code = proc.waitFor()
          Either.cond(code == 0, (), s"git worktree add failed (exit $code): $out")
        }
        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
        .flatMap(_.fold(msg => ZIO.fail(WorkspaceError.WorktreeError(msg)), _ => ZIO.unit))

  val defaultWorktreeRemove: String => Task[Unit] =
    wtPath =>
      ZIO.attemptBlockingIO {
        val pb = new ProcessBuilder("git", "worktree", "remove", "--force", wtPath)
        pb.start().waitFor()
        ()
      }

final case class WorkspaceRunServiceLive(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
  timeoutSeconds: Long = 1800,
  // Injectable for testing: (repoPath, worktreePath, branch) => effect
  worktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] = WorkspaceRunServiceLive.defaultWorktreeAdd,
  worktreeRemove: String => Task[Unit] = WorkspaceRunServiceLive.defaultWorktreeRemove,
  // Injectable for testing: checks Docker availability
  dockerCheck: IO[WorkspaceError, Unit] = DockerSupport.requireDocker,
) extends WorkspaceRunService:

  override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
    for
      ws     <- wsRepo
                  .get(workspaceId)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                  .flatMap(
                    _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(workspaceId)))(ZIO.succeed)
                  )
      _      <- ZIO.unless(ws.enabled)(ZIO.fail(WorkspaceError.Disabled(workspaceId)))
      _      <- ws.runMode match
                  case RunMode.Docker(_, _, _, _) => dockerCheck
                  case RunMode.Host               => ZIO.unit
      runId   = java.util.UUID.randomUUID().toString
      short   = runId.take(8)
      branch  = s"agent/${req.issueRef.stripPrefix("#")}-$short"
      wtPath  = s"${sys.props("user.home")}/.cache/agent-worktrees/${ws.name}/$runId"
      _      <- worktreeAdd(ws.localPath, wtPath, branch)
      now    <- Clock.instant
      conv    = ChatConversation(
                  title = s"[${ws.name}] ${req.issueRef}",
                  runId = Some(runId),
                  createdAt = now,
                  updatedAt = now,
                )
      convId <- chatRepo
                  .createConversation(conv)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      run     = WorkspaceRun(
                  id = runId,
                  workspaceId = workspaceId,
                  issueRef = req.issueRef,
                  agentName = req.agentName,
                  prompt = req.prompt,
                  conversationId = convId.toString,
                  worktreePath = wtPath,
                  branchName = branch,
                  status = RunStatus.Pending,
                  createdAt = now,
                  updatedAt = now,
                )
      _      <- wsRepo
                  .saveRun(run)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _      <- executeInFiber(run, ws.runMode).forkDaemon
    yield run

  override def continueRun(runId: String, followUpPrompt: String): IO[WorkspaceError, Unit] =
    for
      run <- wsRepo
               .getRun(runId)
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
               .flatMap(
                 _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed)
               )
      ws  <- wsRepo
               .get(run.workspaceId)
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
               .flatMap(
                 _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(run.workspaceId)))(ZIO.succeed)
               )
      _   <- executeInFiber(run.copy(prompt = followUpPrompt), ws.runMode).forkDaemon
    yield ()

  private def executeInFiber(run: WorkspaceRun, runMode: RunMode): IO[WorkspaceError, Unit] =
    val argv = CliAgentRunner.buildArgv(run.agentName, run.prompt, run.worktreePath, runMode)
    for
      _                <- wsRepo
                            .updateRunStatus(run.id, RunStatus.Running)
                            .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      resultOrTimeout  <- CliAgentRunner
                            .runProcess(argv, run.worktreePath)
                            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                            .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
      (lines, exitCode) = resultOrTimeout.getOrElse((List("Run timed out"), 1))
      _                <- streamLinesToConversation(run.conversationId, lines)
      status            = if resultOrTimeout.isDefined && exitCode == 0 then RunStatus.Completed else RunStatus.Failed
      _                <- wsRepo
                            .updateRunStatus(run.id, status)
                            .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _                <- worktreeRemove(run.worktreePath).ignore
    yield ()

  private def streamLinesToConversation(conversationId: String, lines: List[String]): IO[WorkspaceError, Unit] =
    ZIO.foreachDiscard(lines) { line =>
      for
        now  <- Clock.instant
        entry = ConversationEntry(
                  conversationId = conversationId,
                  sender = "agent",
                  senderType = SenderType.Assistant,
                  content = line,
                  messageType = MessageType.Status,
                  createdAt = now,
                  updatedAt = now,
                )
        _    <- chatRepo
                  .addMessage(entry)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      yield ()
    }
