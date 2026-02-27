package workspace.control

import java.nio.file.Paths

import zio.*
import zio.json.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import issues.entity.{ AgentIssue as DomainIssue, IssueRepository }
import shared.ids.Ids.IssueId
import workspace.entity.*

case class AssignRunRequest(issueRef: String, prompt: String, agentName: String) derives JsonCodec

trait WorkspaceRunService:
  def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun]
  def continueRun(runId: String, followUpPrompt: String): IO[WorkspaceError, Unit]

object WorkspaceRunService:
  val live: ZLayer[WorkspaceRepository & ChatRepository & IssueRepository, Nothing, WorkspaceRunService] =
    ZLayer.fromFunction((repo: WorkspaceRepository, chat: ChatRepository, issueRepo: IssueRepository) =>
      WorkspaceRunServiceLive(repo, chat, issueRepo)
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
  issueRepo: IssueRepository,
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
      issue  <- {
                  val refStr = req.issueRef.stripPrefix("#")
                  if refStr.isEmpty then ZIO.succeed(None)
                  else
                    issueRepo.get(IssueId(refStr))
                      .map(Some(_))
                      .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                      .catchAll(_ => ZIO.succeed(None))
                }
      runId   = java.util.UUID.randomUUID().toString
      short   = runId.take(8)
      branch  = s"agent/${req.issueRef.stripPrefix("#")}-$short"
      wtPath  = s"${sys.props("user.home")}/.cache/agent-worktrees/${ws.name}/$runId"
      _      <- worktreeAdd(ws.localPath, wtPath, branch)
      prompt  = buildPrompt(req, issue, ws.localPath, wtPath)
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
      _      <- chatRepo
                  .addMessage(
                    ConversationEntry(
                      conversationId = convId.toString,
                      sender = "user",
                      senderType = SenderType.User,
                      content = prompt,
                      messageType = MessageType.Text,
                      createdAt = now,
                      updatedAt = now,
                    )
                  )
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _      <- wsRepo
                  .appendRun(
                    WorkspaceRunEvent.Assigned(
                      runId = runId,
                      workspaceId = workspaceId,
                      issueRef = req.issueRef,
                      agentName = req.agentName,
                      prompt = prompt,
                      conversationId = convId.toString,
                      worktreePath = wtPath,
                      branchName = branch,
                      occurredAt = now,
                    )
                  )
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      run    <- wsRepo
                  .getRun(runId)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                  .flatMap(
                    _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed)
                  )
      _      <- chatRepo
                  .addMessage(
                    ConversationEntry(
                      conversationId = convId.toString,
                      sender = "system",
                      senderType = SenderType.System,
                      content =
                        s"Agent `${req.agentName}` (via `${ws.cliTool}`) started on branch `${run.branchName}` in `${run.worktreePath}`",
                      messageType = MessageType.Status,
                      createdAt = now,
                      updatedAt = now,
                    )
                  )
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _      <- executeInFiber(run, ws.runMode, ws.cliTool).forkDaemon
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
      _   <- executeInFiber(run.copy(prompt = followUpPrompt), ws.runMode, ws.cliTool).forkDaemon
    yield ()

  private def buildPrompt(
    req: AssignRunRequest,
    issue: Option[DomainIssue],
    repoPath: String,
    worktreePath: String,
  ): String =
    issue match
      case None    =>
        // No issue record found; fall back to the raw title from the UI
        s"""Issue: ${req.issueRef}
           |Task: ${req.prompt}
           |
           |Repository: $repoPath
           |Working directory: $worktreePath""".stripMargin
      case Some(i) =>
        s"""Issue ${req.issueRef}: ${i.title}${
          if i.description.nonEmpty then s"\nDescription:\n${i.description}" else ""
        }${
          if i.contextPath.nonEmpty then s"\nContext path: ${i.contextPath}" else ""
        }${
          if i.sourceFolder.nonEmpty then s"\nSource folder: ${i.sourceFolder}" else ""
        }
        Repository: $repoPath
        Working directory: $worktreePath"""

  private def updateRunStatus(runId: String, status: RunStatus): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      _   <- wsRepo
               .appendRun(WorkspaceRunEvent.StatusChanged(runId, status, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
    yield ()

  private def executeInFiber(run: WorkspaceRun, runMode: RunMode, cliTool: String): IO[WorkspaceError, Unit] =
    val argv    = CliAgentRunner.buildArgv(cliTool, run.prompt, run.worktreePath, runMode)
    val argvStr = argv.map(a => if a.contains(" ") then s"'$a'" else a).mkString(" ")
    for
      _                <- updateRunStatus(run.id, RunStatus.Running)
      _                <- ZIO.logInfo(s"[run:${run.id}] launching: $argvStr  (cwd=${run.worktreePath})")
      resultOrTimeout  <- CliAgentRunner
                            .runProcess(argv, run.worktreePath)
                            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                            .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
                            .tapError(e => ZIO.logError(s"[run:${run.id}] process error: $e"))
      (lines, exitCode) = resultOrTimeout.getOrElse((List("Run timed out"), 1))
      _                <- ZIO.logWarning(s"[run:${run.id}] timed out after ${timeoutSeconds}s")
                            .when(resultOrTimeout.isEmpty)
      _                <- ZIO.logInfo(s"[run:${run.id}] finished exit=$exitCode lines=${lines.size}")
                            .when(resultOrTimeout.isDefined)
      _                <- streamLinesToConversation(run.conversationId, lines)
      status            = if resultOrTimeout.isDefined && exitCode == 0 then RunStatus.Completed else RunStatus.Failed
      _                <- updateRunStatus(run.id, status)
      _                <- ZIO.logInfo(s"[run:${run.id}] status=$status")
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
