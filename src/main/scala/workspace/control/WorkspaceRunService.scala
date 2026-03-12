package workspace.control

import java.nio.file.Paths

import zio.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import agent.entity.AgentRepository
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueRepository }
import shared.ids.Ids.{ EventId, IssueId, TaskRunId }
import workspace.entity.*

case class AssignRunRequest(issueRef: String, prompt: String, agentName: String) derives JsonCodec

trait WorkspaceRunService:
  def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun]
  def continueRun(
    runId: String,
    followUpPrompt: String,
    agentNameOverride: Option[String] = None,
  ): IO[WorkspaceError, WorkspaceRun]
  def cancelRun(runId: String): IO[WorkspaceError, Unit]

object WorkspaceRunService:
  val live
    : ZLayer[
      WorkspaceRepository & ChatRepository & IssueRepository & ActivityHub & GitWatcher & AgentRepository,
      Nothing,
      WorkspaceRunService,
    ] =
    ZLayer {
      for
        repo      <- ZIO.service[WorkspaceRepository]
        chat      <- ZIO.service[ChatRepository]
        issueRepo <- ZIO.service[IssueRepository]
        activity  <- ZIO.service[ActivityHub]
        watcher   <- ZIO.service[GitWatcher]
        agents    <- ZIO.service[AgentRepository]
        registry  <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
      yield WorkspaceRunServiceLive(
        repo,
        chat,
        issueRepo,
        activityPublish = event => activity.publish(event),
        gitWatcher = watcher,
        fiberRegistry = registry,
        resolveAgentProfile = name =>
          agents.findByName(name)
            .mapError(err => WorkspaceError.PersistenceFailure(RuntimeException(err.toString))),
      )
    }

  def cancelRun(runId: String): ZIO[WorkspaceRunService, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[WorkspaceRunService](_.cancelRun(runId))

object WorkspaceRunServiceLive:
  def worktreePath(workspaceName: String, runId: String): String =
    worktreePath(
      workspaceName = workspaceName,
      runId = runId,
      userHome = sys.props.getOrElse("user.home", "."),
      localAppData = sys.env.get("LOCALAPPDATA"),
      osName = java.lang.System.getProperty("os.name", ""),
    )

  private[workspace] def worktreePath(
    workspaceName: String,
    runId: String,
    userHome: String,
    localAppData: Option[String],
    osName: String,
  ): String =
    HostPlatform.defaultWorktreeRoot(userHome, localAppData, osName).resolve(workspaceName).resolve(runId).toString

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
  // Injectable for testing: replaces CliAgentRunner.runProcessStreaming; signature (argv, cwd, onLine) => exitCode
  runCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
    CliAgentRunner.runProcessStreaming,
  // Injectable for testing: publishes workspace run lifecycle events to ActivityHub/WebSocket subscribers
  activityPublish: ActivityEvent => UIO[Unit] = _ => ZIO.unit,
  gitWatcher: GitWatcher = GitWatcher.noop,
  // Tracks live run fibers by runId for cancellation; defaults to an empty registry
  fiberRegistry: Ref[Map[String, Fiber[WorkspaceError, Unit]]] =
    zio.Unsafe.unsafe(implicit u =>
      Ref.unsafe.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
    ),
  resolveAgentProfile: String => IO[WorkspaceError, Option[_root_.agent.entity.Agent]] = _ => ZIO.succeed(None),
) extends WorkspaceRunService:

  override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
    for
      ws      <- wsRepo
                   .get(workspaceId)
                   .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                   .flatMap(
                     _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(workspaceId)))(ZIO.succeed)
                   )
      _       <- ZIO.unless(ws.enabled)(ZIO.fail(WorkspaceError.Disabled(workspaceId)))
      _       <- ws.runMode match
                   case RunMode.Docker(_, _, _, _) => dockerCheck
                   case RunMode.Host               => ZIO.unit
      profile <- resolveAgentProfile(req.agentName)
      _       <- profile.fold[IO[WorkspaceError, Unit]](ZIO.unit)(enforceAgentConcurrency)
      issue   <- {
        val refStr = req.issueRef.stripPrefix("#")
        if refStr.isEmpty then ZIO.succeed(None)
        else
          issueRepo.get(IssueId(refStr))
            .map(Some(_))
            .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
            .catchAll(_ => ZIO.succeed(None))
      }
      runId    = java.util.UUID.randomUUID().toString
      short    = runId.take(8)
      branch   = s"agent/${sanitizeBranchPart(req.agentName)}-${req.issueRef.stripPrefix("#")}-$short"
      wtPath   = WorkspaceRunServiceLive.worktreePath(ws.name, runId)
      _       <- worktreeAdd(ws.localPath, wtPath, branch)
      prompt   = buildPrompt(req, issue, ws.localPath, wtPath)
      _       <- injectAgentPromptFile(ws.name, req.issueRef, branch, wtPath, profile)
      now     <- Clock.instant
      conv     = ChatConversation(
                   title = s"[${ws.name}] ${req.issueRef}",
                   runId = Some(runId),
                   createdAt = now,
                   updatedAt = now,
                 )
      convId  <- chatRepo
                   .createConversation(conv)
                   .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _       <- chatRepo
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
      _       <- wsRepo
                   .appendRun(
                     WorkspaceRunEvent.Assigned(
                       runId = runId,
                       workspaceId = workspaceId,
                       parentRunId = None,
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
      run     <- wsRepo
                   .getRun(runId)
                   .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                   .flatMap(
                     _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed)
                   )
      _       <- chatRepo
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
      fiber   <- executeInFiber(
                   run = run,
                   runMode = ws.runMode,
                   cliTool = ws.cliTool,
                   repoPath = ws.localPath,
                   profile = profile,
                 )
                   .onExit {
                     case Exit.Failure(c) if c.isInterruptedOnly =>
                       (updateRunStatus(run.id, RunStatus.Cancelled) *>
                         maybeCleanupWorktree(run, RunStatus.Cancelled) *>
                         appendToConversation(run.conversationId, "Run cancelled by user.").ignore).ignore
                     case _                                      => ZIO.unit
                   }
                   .ensuring(fiberRegistry.update(_ - run.id))
                   .forkDaemon
      _       <- fiberRegistry.update(_ + (run.id -> fiber))
    yield run

  override def continueRun(
    runId: String,
    followUpPrompt: String,
    agentNameOverride: Option[String] = None,
  ): IO[WorkspaceError, WorkspaceRun] =
    for
      run           <- wsRepo
                         .getRun(runId)
                         .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                         .flatMap(
                           _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed)
                         )
      _             <- ensureNoActiveRunOnWorktree(run)
      effectiveAgent = agentNameOverride.map(_.trim).filter(_.nonEmpty).getOrElse(run.agentName)
      profile       <- resolveAgentProfile(effectiveAgent)
      _             <- profile.fold[IO[WorkspaceError, Unit]](ZIO.unit)(enforceAgentConcurrency)
      ws            <- wsRepo
                         .get(run.workspaceId)
                         .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                         .flatMap(
                           _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(run.workspaceId)))(ZIO.succeed)
                         )
      historyPrompt <- buildContinuationPrompt(run, followUpPrompt)
      newRunId       = java.util.UUID.randomUUID().toString
      now           <- Clock.instant
      conv          <- chatRepo
                         .createConversation(
                           ChatConversation(
                             title = s"[${ws.name}] ${run.issueRef} (continuation)",
                             runId = Some(newRunId),
                             createdAt = now,
                             updatedAt = now,
                           )
                         )
                         .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _             <- chatRepo
                         .addMessage(
                           ConversationEntry(
                             conversationId = conv.toString,
                             sender = "user",
                             senderType = SenderType.User,
                             content = historyPrompt,
                             messageType = MessageType.Text,
                             metadata = Some(s"""{"continuationFrom":"${run.id}"}"""),
                             createdAt = now,
                             updatedAt = now,
                           )
                         )
                         .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _             <- wsRepo
                         .appendRun(
                           WorkspaceRunEvent.Assigned(
                             runId = newRunId,
                             workspaceId = run.workspaceId,
                             parentRunId = Some(run.id),
                             issueRef = run.issueRef,
                             agentName = effectiveAgent,
                             prompt = historyPrompt,
                             conversationId = conv.toString,
                             worktreePath = run.worktreePath,
                             branchName = run.branchName,
                             occurredAt = now,
                           )
                         )
                         .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      continuedRun  <-
        wsRepo
          .getRun(newRunId)
          .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
          .flatMap(
            _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(newRunId)))(ZIO.succeed)
          )
      _             <- injectAgentPromptFile(ws.name, run.issueRef, run.branchName, run.worktreePath, profile)
      fiber         <- executeInFiber(
                         run = continuedRun,
                         runMode = ws.runMode,
                         cliTool = ws.cliTool,
                         repoPath = ws.localPath,
                         profile = profile,
                       )
                         .onExit {
                           case Exit.Failure(c) if c.isInterruptedOnly =>
                             (updateRunStatus(continuedRun.id, RunStatus.Cancelled) *>
                               maybeCleanupWorktree(continuedRun, RunStatus.Cancelled) *>
                               appendToConversation(continuedRun.conversationId, "Run cancelled by user.").ignore).ignore
                           case _                                      => ZIO.unit
                         }
                         .ensuring(fiberRegistry.update(_ - continuedRun.id))
                         .forkDaemon
      _             <- fiberRegistry.update(_ + (continuedRun.id -> fiber))
      _             <- appendToConversation(run.conversationId, s"Created continuation run `${continuedRun.id}`").ignore
    yield continuedRun

  private def buildPrompt(
    req: AssignRunRequest,
    issue: Option[DomainIssue],
    repoPath: String,
    worktreePath: String,
  ): String =
    val directoryGuardrail =
      s"""Execution constraints:
         |- You MUST create/modify/delete files ONLY under: $worktreePath
         |- Do NOT write to: $repoPath
         |- If an absolute path is needed, always use the working directory path above.
         |""".stripMargin
    issue match
      case None    =>
        // No issue record found; fall back to the raw title from the UI
        s"""Issue: ${req.issueRef}
           |Task: ${req.prompt}
           |
           |$directoryGuardrail
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
        $directoryGuardrail
        Repository: $repoPath
        Working directory: $worktreePath"""

  private def sanitizeBranchPart(value: String): String =
    value.trim.toLowerCase.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")

  private def enforceAgentConcurrency(agent: _root_.agent.entity.Agent): IO[WorkspaceError, Unit] =
    for
      workspaces <- wsRepo.list.mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      runs       <- ZIO.foreach(workspaces)(ws =>
                      wsRepo.listRuns(ws.id).mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                    ).map(_.flatten)
      active      = runs.count(run =>
                      run.agentName.equalsIgnoreCase(agent.name) &&
                      (run.status == RunStatus.Pending || run.status.isInstanceOf[RunStatus.Running])
                    )
      _          <-
        if active >= agent.maxConcurrentRuns then
          ZIO.fail(
            WorkspaceError.InvalidRunState(
              runId = agent.name,
              expected = s"active_runs < ${agent.maxConcurrentRuns}",
              actual = s"active_runs = $active",
            )
          )
        else ZIO.unit
    yield ()

  private def injectAgentPromptFile(
    workspaceName: String,
    issueRef: String,
    branchName: String,
    worktreePath: String,
    profile: Option[_root_.agent.entity.Agent],
  ): IO[WorkspaceError, Unit] =
    profile.flatMap(_.systemPrompt.map(_.trim).filter(_.nonEmpty)) match
      case None         => ZIO.unit
      case Some(prompt) =>
        val rendered = renderPromptTemplate(
          prompt,
          Map(
            "workspace" -> workspaceName,
            "issue"     -> issueRef,
            "branch"    -> branchName,
          ),
        )
        val target   = Paths.get(worktreePath).resolve("CLAUDE.md")
        ZIO
          .attemptBlockingIO {
            val previous = if java.nio.file.Files.exists(target) then java.nio.file.Files.readString(target) else ""
            val marker   = "\n\n<!-- llm4zio:agent-system-prompt -->\n"
            val next     =
              if previous.trim.isEmpty then rendered + "\n"
              else previous + marker + rendered + "\n"
            java.nio.file.Files.writeString(target, next)
          }
          .mapError(e => WorkspaceError.WorktreeError(s"Failed to inject CLAUDE.md: ${e.getMessage}"))
          .unit

  private def renderPromptTemplate(template: String, values: Map[String, String]): String =
    values.foldLeft(template) { case (acc, (k, v)) => acc.replace(s"{{$k}}", v) }

  private def workspaceLevelEnvVars: Map[String, String] =
    sys.env

  override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
    fiberRegistry.get.map(_.get(runId)).flatMap {
      case None        => ZIO.fail(WorkspaceError.NotFound(runId))
      case Some(fiber) => fiber.interrupt.unit
    }

  private def ensureNoActiveRunOnWorktree(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    wsRepo
      .listRuns(run.workspaceId)
      .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      .flatMap { runs =>
        val hasActive = runs.exists(other =>
          other.id != run.id &&
          other.worktreePath == run.worktreePath &&
          (other.status == RunStatus.Pending || other.status.isInstanceOf[RunStatus.Running])
        )
        if hasActive then
          ZIO.fail(
            WorkspaceError.InvalidRunState(
              run.id,
              "no active run on worktree",
              "another continuation is already running",
            )
          )
        else ZIO.unit
      }

  private def buildContinuationPrompt(parentRun: WorkspaceRun, followUpPrompt: String): IO[WorkspaceError, String] =
    for
      history    <- chatRepo
                      .getMessages(parentRun.conversationId.toLongOption.getOrElse(0L))
                      .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      gitCtx     <- loadGitStatus(parentRun.worktreePath).orElseSucceed("git status unavailable")
      historyText = history
                      .takeRight(120)
                      .map(m => s"- ${m.senderType}:${m.sender}: ${m.content}")
                      .mkString("\n")
    yield s"""Continuation run for ${parentRun.issueRef}
             |Parent run: ${parentRun.id}
             |Branch: ${parentRun.branchName}
             |Worktree: ${parentRun.worktreePath}
             |
             |Conversation history:
             |$historyText
             |
             |Current worktree state:
             |$gitCtx
             |
             |New instructions:
             |$followUpPrompt
             |""".stripMargin

  private def loadGitStatus(worktreePath: String): Task[String] =
    ZIO.attemptBlockingIO {
      val pb   = new ProcessBuilder("git", "-C", worktreePath, "status", "--short", "--branch")
      pb.redirectErrorStream(true)
      val p    = pb.start()
      val out  = scala.io.Source.fromInputStream(p.getInputStream).mkString.trim
      val code = p.waitFor()
      if code == 0 then out else s"git status failed (exit=$code): $out"
    }

  private def updateRunStatus(runId: String, status: RunStatus): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      _   <- wsRepo
               .appendRun(WorkspaceRunEvent.StatusChanged(runId, status, now))
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _   <- publishRunLifecycle(runId, status)
      _   <- syncIssueLifecycle(runId, status).catchAll(err =>
               ZIO.logWarning(s"[run:$runId] failed to sync issue lifecycle for status $status: $err")
             )
    yield ()

  private def executeInFiber(
    run: WorkspaceRun,
    runMode: RunMode,
    cliTool: String,
    repoPath: String = "",
    profile: Option[_root_.agent.entity.Agent] = None,
  ): IO[WorkspaceError, Unit] =
    val envVars = workspaceLevelEnvVars ++ profile.map(_.envVars).getOrElse(Map.empty)
    val argv    = CliAgentRunner.buildArgv(
      cliTool = cliTool,
      prompt = run.prompt,
      worktreePath = run.worktreePath,
      runMode = runMode,
      repoPath = repoPath,
      envVars = envVars,
      dockerMemoryLimit = profile.flatMap(_.dockerMemoryLimit),
      dockerCpuLimit = profile.flatMap(_.dockerCpuLimit),
    )
    val argvStr = argv.map(a => if a.contains(" ") then s"'$a'" else a).mkString(" ")
    val timeout = profile.map(_.timeout).getOrElse(java.time.Duration.ofSeconds(timeoutSeconds))
    (for
      _        <- updateRunStatus(run.id, RunStatus.Running(RunSessionMode.Autonomous))
      _        <- gitWatcher.registerRun(run.id, run.worktreePath)
      _        <- ZIO.logInfo(s"[run:${run.id}] launching: $argvStr  (cwd=${run.worktreePath})")
      linesRef <- Ref.make(0)
      exitOpt  <- runCliAgent(
                    argv,
                    run.worktreePath,
                    line =>
                      linesRef.update(_ + 1) *>
                        appendToConversation(run.conversationId, line)
                          .tapError(e => ZIO.logWarning(s"[run:${run.id}] failed to persist line to chat: $e"))
                          .ignore,
                    envVars,
                  )
                    .timeout(timeout)
                    .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
                    .tapError(e => ZIO.logError(s"[run:${run.id}] process error: $e"))
      _        <- appendToConversation(run.conversationId, s"Run timed out after ${timeout.toSeconds}s")
                    .when(exitOpt.isEmpty)
      _        <- ZIO.logWarning(s"[run:${run.id}] timed out after ${timeout.toSeconds}s")
                    .when(exitOpt.isEmpty)
      count    <- linesRef.get
      exitCode  = exitOpt.getOrElse(1)
      _        <- ZIO.logInfo(s"[run:${run.id}] finished exit=$exitCode lines=$count")
                    .when(exitOpt.isDefined)
      status    = if exitOpt.isDefined && exitCode == 0 then RunStatus.Completed else RunStatus.Failed
      _        <- updateRunStatus(run.id, status)
      _        <- maybeCleanupWorktree(run, status)
      _        <- ZIO.logInfo(s"[run:${run.id}] status=$status")
    yield ()).ensuring(gitWatcher.unregisterRun(run.id))

  private def appendToConversation(conversationId: String, line: String): IO[WorkspaceError, Unit] =
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

  private def publishRunLifecycle(runId: String, status: RunStatus): UIO[Unit] =
    Clock.instant.flatMap { now =>
      val eventType = status match
        case RunStatus.Running(_) => ActivityEventType.RunStarted
        case RunStatus.Completed  => ActivityEventType.RunCompleted
        case RunStatus.Failed     => ActivityEventType.RunFailed
        case _                    => ActivityEventType.MessageSent
      activityPublish(
        ActivityEvent(
          id = EventId.generate,
          eventType = eventType,
          source = "workspace-run-service",
          runId = Some(TaskRunId(runId)),
          summary = s"Run $runId status changed to $status",
          payload = Some(status.toJson),
          createdAt = now,
        )
      )
    }

  private def syncIssueLifecycle(runId: String, status: RunStatus): IO[WorkspaceError, Unit] =
    status match
      case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled =>
        for
          runOpt <- wsRepo
                      .getRun(runId)
                      .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
          _      <- runOpt match
                      case Some(run) =>
                        issueIdFromIssueRef(run.issueRef) match
                          case Some(issueId) => appendIssueLifecycleEvent(issueId, runId, status)
                          case None          => ZIO.unit
                      case None      => ZIO.unit
        yield ()
      case _                                                            =>
        ZIO.unit

  private def issueIdFromIssueRef(issueRef: String): Option[IssueId] =
    Option(issueRef)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.stripPrefix("#"))
      .filter(_.nonEmpty)
      .map(IssueId.apply)

  private def appendIssueLifecycleEvent(
    issueId: IssueId,
    runId: String,
    status: RunStatus,
  ): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      ev   = status match
               case RunStatus.Completed =>
                 IssueEvent.MovedToHumanReview(issueId = issueId, movedAt = now, occurredAt = now)
               case RunStatus.Failed    =>
                 IssueEvent.MovedToRework(
                   issueId = issueId,
                   movedAt = now,
                   reason = s"Workspace run $runId failed",
                   occurredAt = now,
                 )
               case RunStatus.Cancelled =>
                 IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now)
               case _                   =>
                 IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now)
      _   <- issueRepo
               .append(ev)
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
    yield ()

  private def maybeCleanupWorktree(run: WorkspaceRun, status: RunStatus): IO[WorkspaceError, Unit] =
    status match
      case RunStatus.Cancelled =>
        wsRepo
          .listRuns(run.workspaceId)
          .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
          .flatMap { runs =>
            val hasActiveSibling = runs.exists(other =>
              other.id != run.id &&
              other.worktreePath == run.worktreePath &&
              (other.status == RunStatus.Pending || other.status.isInstanceOf[RunStatus.Running])
            )
            if hasActiveSibling then ZIO.unit
            else worktreeRemove(run.worktreePath).ignore
          }
      case _                   => ZIO.unit
