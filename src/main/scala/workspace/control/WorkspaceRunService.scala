package workspace.control

import java.nio.file.{ Path, Paths }

import zio.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import agent.entity.{ AgentPathScope, AgentPermissions, AgentRepository, NetworkAccessScope, TrustLevel }
import analysis.entity.AnalysisRepository
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import decision.control.DecisionInbox
import issues.entity.{ AgentIssue as DomainIssue, IssueRepository }
import knowledge.control.KnowledgeExtractionService
import orchestration.control.{ AgentExecutionState, AgentPoolManager, OrchestratorControlPlane, PoolError, SlotHandle }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import workspace.control.WorkspaceErrorSupport.*
import workspace.control.WorkspaceRunLifecycleSupport.*
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
  def cleanupAfterSuccessfulMerge(runId: String): UIO[Unit]      = ZIO.unit
  def registerSlot(runId: String, handle: SlotHandle): UIO[Unit] = ZIO.unit

object WorkspaceRunService:
  val live
    : ZLayer[
      WorkspaceRepository & ChatRepository & IssueRepository & ActivityHub & GitWatcher & AgentRepository &
        AnalysisRepository & DecisionInbox & KnowledgeExtractionService &
        AgentPoolManager & OrchestratorControlPlane,
      Nothing,
      WorkspaceRunService,
    ] =
    ZLayer {
      for
        repo         <- ZIO.service[WorkspaceRepository]
        chat         <- ZIO.service[ChatRepository]
        issueRepo    <- ZIO.service[IssueRepository]
        activity     <- ZIO.service[ActivityHub]
        watcher      <- ZIO.service[GitWatcher]
        agents       <- ZIO.service[AgentRepository]
        analysis     <- ZIO.service[AnalysisRepository]
        decisions    <- ZIO.service[DecisionInbox]
        knowledge    <- ZIO.service[KnowledgeExtractionService]
        pool         <- ZIO.service[AgentPoolManager]
        controlPlane <- ZIO.service[OrchestratorControlPlane]
        registry     <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
        slots        <- Ref.make(Map.empty[String, SlotHandle])
      yield WorkspaceRunServiceLive(
        repo,
        chat,
        issueRepo,
        analysis,
        activityPublish = event => activity.publish(event),
        gitWatcher = watcher,
        fiberRegistry = registry,
        slotRegistry = slots,
        acquireAgentSlot = agentName =>
          pool.acquireSlot(agentName).mapError(poolErrorToWorkspace),
        availableAgentSlots = agentName => pool.availableSlots(agentName),
        releaseAgentSlot = pool.releaseSlot,
        recordAgentTokens = (agentName, tokens) =>
          pool.recordTokenUsage(agentName, tokens).mapError(poolErrorToWorkspace),
        resolveAgentProfile = name =>
          agents.findByName(name)
            .mapWorkspacePersistence("resolve_agent_profile"),
        onIssueEnteredHumanReview = issue =>
          decisions
            .openIssueReviewDecision(issue)
            .mapWorkspacePersistence("open_issue_review_decision")
            .unit,
        extractKnowledgeFromCompletedRun = (run, issue) =>
          knowledge
            .extractFromCompletedRun(run, issue)
            .mapWorkspacePersistence("extract_completed_run_knowledge")
            .unit,
        notifyControlPlane = (agentName, state, runId, convId, msg, tokens) =>
          controlPlane.notifyWorkspaceAgent(agentName, state, runId, convId, msg, tokens),
      )
    }

  def cancelRun(runId: String): ZIO[WorkspaceRunService, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[WorkspaceRunService](_.cancelRun(runId))

  private def poolErrorToWorkspace(error: PoolError): WorkspaceError =
    error match
      case PoolError.AgentNotFound(agentName)            =>
        WorkspaceError.AgentNotFound(agentName.trim)
      case PoolError.AgentPaused(agentName, reason)      =>
        WorkspaceError.PermissionDenied(agentName.trim, reason)
      case PoolError.CostLimitExceeded(agentName, limit) =>
        WorkspaceError.CostLimitExceeded(agentName.trim, limit)
      case PoolError.InvalidCapacity(agentName, raw)     =>
        WorkspaceError.InvalidRunState(
          runId = agentName.trim,
          expected = "agent pool capacity > 0",
          actual = s"configured capacity = $raw",
        )
      case PoolError.PersistenceFailure(_, cause)        =>
        WorkspaceError.PersistenceFailure(RuntimeException(s"agent_pool failed: $cause"))

object WorkspaceRunServiceLive:
  final case class WorkspaceExecutionFailure(error: WorkspaceError)
    extends RuntimeException(error.toString)

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

  val defaultBranchDelete: (String, String) => Task[Unit] =
    (repoPath, branchName) =>
      ZIO.attemptBlockingIO {
        val pb = new ProcessBuilder("git", "branch", "-d", branchName)
        pb.directory(Paths.get(repoPath).toFile)
        pb.start().waitFor()
        ()
      }

final case class WorkspaceRunServiceLive(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
  issueRepo: IssueRepository,
  analysisRepository: AnalysisRepository,
  timeoutSeconds: Long = 1800,
  // Injectable for testing: (repoPath, worktreePath, branch) => effect
  worktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] = WorkspaceRunServiceLive.defaultWorktreeAdd,
  worktreeRemove: String => Task[Unit] = WorkspaceRunServiceLive.defaultWorktreeRemove,
  branchDelete: (String, String) => Task[Unit] = WorkspaceRunServiceLive.defaultBranchDelete,
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
  slotRegistry: Ref[Map[String, SlotHandle]] =
    zio.Unsafe.unsafe(implicit u =>
      Ref.unsafe.make(Map.empty[String, SlotHandle])
    ),
  acquireAgentSlot: String => IO[WorkspaceError, SlotHandle] = agentName =>
    Clock.instant.map(now => SlotHandle(java.util.UUID.randomUUID().toString, agentName.trim.toLowerCase, now)),
  availableAgentSlots: String => UIO[Int] = _ => ZIO.succeed(Int.MaxValue),
  releaseAgentSlot: SlotHandle => UIO[Unit] = _ => ZIO.unit,
  recordAgentTokens: (String, Long) => IO[WorkspaceError, Unit] = (_, _) => ZIO.unit,
  resolveAgentProfile: String => IO[WorkspaceError, Option[_root_.agent.entity.Agent]] = _ => ZIO.succeed(None),
  onIssueEnteredHumanReview: DomainIssue => IO[WorkspaceError, Unit] = _ => ZIO.unit,
  extractKnowledgeFromCompletedRun: (WorkspaceRun, Option[DomainIssue]) => IO[WorkspaceError, Unit] =
    (_, _) => ZIO.unit,
  notifyControlPlane: (String, AgentExecutionState, Option[String], Option[String], Option[String], Long) => UIO[Unit] =
    (_, _, _, _, _, _) => ZIO.unit,
  resolveExecutionRuntime: (RunMode, AgentPermissions, Option[TrustLevel]) => ExecutionRuntime.Resolution =
    ExecutionRuntime.resolve,
) extends WorkspaceRunService:

  private val lifecycle = WorkspaceRunLifecycleSupport(
    wsRepo = wsRepo,
    chatRepo = chatRepo,
    issueRepo = issueRepo,
    analysisRepository = analysisRepository,
    worktreeRemove = worktreeRemove,
    branchDelete = branchDelete,
    activityPublish = activityPublish,
    slotRegistry = slotRegistry,
    releaseAgentSlot = releaseAgentSlot,
    onIssueEnteredHumanReview = onIssueEnteredHumanReview,
    extractKnowledgeFromCompletedRun = extractKnowledgeFromCompletedRun,
  )

  override def registerSlot(runId: String, handle: SlotHandle): UIO[Unit] =
    slotRegistry.update(_ + (runId -> handle))

  override def cleanupAfterSuccessfulMerge(runId: String): UIO[Unit] =
    wsRepo
      .getRun(runId)
      .mapWorkspacePersistence("load_run_for_post_merge_cleanup")
      .flatMap {
        case Some(run) => lifecycle.maybeCleanupWorktree(run, CleanupMode.AfterMergeSuccess)
        case None      => ZIO.logWarning(s"[run:$runId] skipping post-merge cleanup: run not found")
      }
      .catchAll(error => ZIO.logError(s"[run:$runId] post-merge cleanup failed: $error"))
      .unit

  override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
    for
      ws      <- wsRepo
                   .get(workspaceId)
                   .mapWorkspacePersistence("load_workspace_for_assign")
                   .flatMap(
                     _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(workspaceId)))(ZIO.succeed)
                   )
      _       <- ZIO.unless(ws.enabled)(ZIO.fail(WorkspaceError.Disabled(workspaceId)))
      profile <- resolveAgentProfile(req.agentName)
      slot    <- reserveAgentSlot(req.agentName)
      run     <- (for
                   issue      <- loadIssueForAssign(req.issueRef)
                   runId       = java.util.UUID.randomUUID().toString
                   short       = runId.take(8)
                   branch      = s"agent/${sanitizeBranchPart(req.agentName)}-${req.issueRef.stripPrefix("#")}-$short"
                   wtPath      = WorkspaceRunServiceLive.worktreePath(ws.name, runId)
                   permissions = resolvedPermissions(profile, ws.cliTool)
                   runtime    <- enforcePermissions(
                                   agentName = req.agentName,
                                   workspace = ws,
                                   worktreePath = wtPath,
                                   permissions = permissions,
                                   trustLevel = profile.map(_.trustLevel),
                                 )
                   _          <- worktreeAdd(ws.localPath, wtPath, branch)
                   prompt      = buildPrompt(req, issue, ws.localPath, wtPath, permissions)
                   context     = executionContext(
                                   runId = runId,
                                   cliTool = ws.cliTool,
                                   prompt = prompt,
                                   worktreePath = wtPath,
                                   repoPath = ws.localPath,
                                   profile = profile,
                                   permissions = permissions,
                                 )
                   _          <- runtimePreflight(runtime, context)
                   _          <- injectAgentPromptFile(ws.name, req.issueRef, branch, wtPath, profile)
                   now        <- Clock.instant
                   conv        = ChatConversation(
                                   title = s"[${ws.name}] ${req.issueRef}",
                                   runId = Some(runId),
                                   createdAt = now,
                                   updatedAt = now,
                                 )
                   convId     <- chatRepo
                                   .createConversation(conv)
                                   .mapWorkspacePersistence("create_run_conversation")
                   _          <- chatRepo
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
                                   .mapWorkspacePersistence("append_run_prompt_message")
                   _          <- wsRepo
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
                                   .mapWorkspacePersistence("append_assigned_run")
                   run        <- wsRepo
                                   .getRun(runId)
                                   .mapWorkspacePersistence("load_assigned_run")
                                   .flatMap(
                                     _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(
                                       ZIO.succeed
                                     )
                                   )
                   _          <- chatRepo
                                   .addMessage(
                                     ConversationEntry(
                                       conversationId = convId.toString,
                                       sender = "system",
                                       senderType = SenderType.System,
                                       content =
                                         s"Agent `${req.agentName}` (via `${ws.cliTool}`) started on branch `${run.branchName}` in `${run.worktreePath}` using `${runtime.runtime.name}` runtime",
                                       messageType = MessageType.Status,
                                       createdAt = now,
                                       updatedAt = now,
                                     )
                                   )
                                   .mapWorkspacePersistence("append_run_started_message")
                   _          <- registerSlot(run.id, slot)
                   fiber      <- executeInFiber(
                                   run = run,
                                   runtimeResolution = runtime,
                                   cliTool = ws.cliTool,
                                   repoPath = ws.localPath,
                                   profile = profile,
                                   permissions = permissions,
                                 )
                                   .onExit {
                                     case Exit.Failure(c) if c.isInterruptedOnly =>
                                       (notifyControlPlane(
                                         run.agentName,
                                         AgentExecutionState.Aborted,
                                         Some(run.id),
                                         Some(run.conversationId),
                                         Some("Cancelled"),
                                         0L,
                                       ) *>
                                         lifecycle.updateRunStatus(run.id, RunStatus.Cancelled) *>
                                         lifecycle.maybeCleanupWorktree(run, CleanupMode.OnCancelled) *>
                                         appendToConversation(run.conversationId, "Run cancelled by user.").ignore).ignore
                                     case _                                      => ZIO.unit
                                   }
                                   .ensuring(fiberRegistry.update(_ - run.id))
                                   .forkDaemon
                   _          <- fiberRegistry.update(_ + (run.id -> fiber))
                 yield run).tapError(_ => releaseAgentSlot(slot))
    yield run

  private def loadIssueForAssign(issueRef: String): IO[WorkspaceError, Option[DomainIssue]] =
    val refStr = issueRef.stripPrefix("#").trim
    if refStr.isEmpty then ZIO.none
    else
      issueRepo.get(IssueId(refStr))
        .map(Some(_))
        .catchSome {
          case PersistenceError.NotFound("issue", _) =>
            ZIO.none
        }
        .mapWorkspacePersistence("load_issue_for_assign")

  override def continueRun(
    runId: String,
    followUpPrompt: String,
    agentNameOverride: Option[String] = None,
  ): IO[WorkspaceError, WorkspaceRun] =
    for
      run           <- wsRepo
                         .getRun(runId)
                         .mapWorkspacePersistence("load_run_for_continuation")
                         .flatMap(
                           _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed)
                         )
      _             <- lifecycle.ensureNoActiveRunOnWorktree(run)
      effectiveAgent = agentNameOverride.map(_.trim).filter(_.nonEmpty).getOrElse(run.agentName)
      profile       <- resolveAgentProfile(effectiveAgent)
      slot          <- reserveAgentSlot(effectiveAgent)
      ws            <- wsRepo
                         .get(run.workspaceId)
                         .mapWorkspacePersistence("load_workspace_for_continuation")
                         .flatMap(
                           _.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(run.workspaceId)))(ZIO.succeed)
                         )
      permissions    = resolvedPermissions(profile, ws.cliTool)
      runtime       <- enforcePermissions(
                         agentName = effectiveAgent,
                         workspace = ws,
                         worktreePath = run.worktreePath,
                         permissions = permissions,
                         trustLevel = profile.map(_.trustLevel),
                       )
      continuedRun  <- (for
                         historyPrompt <- lifecycle.buildContinuationPrompt(run, followUpPrompt)
                         newRunId       = java.util.UUID.randomUUID().toString
                         context        = executionContext(
                                            runId = newRunId,
                                            cliTool = ws.cliTool,
                                            prompt = historyPrompt,
                                            worktreePath = run.worktreePath,
                                            repoPath = ws.localPath,
                                            profile = profile,
                                            permissions = permissions,
                                          )
                         _             <- runtimePreflight(runtime, context)
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
                                            .mapWorkspacePersistence("create_continuation_conversation")
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
                                            .mapWorkspacePersistence("append_continuation_prompt_message")
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
                                            .mapWorkspacePersistence("append_continuation_run")
                         continuedRun  <-
                           wsRepo
                             .getRun(newRunId)
                             .mapWorkspacePersistence("load_continuation_run")
                             .flatMap(
                               _.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(newRunId)))(
                                 ZIO.succeed
                               )
                             )
                         _             <- injectAgentPromptFile(
                                            ws.name,
                                            run.issueRef,
                                            run.branchName,
                                            run.worktreePath,
                                            profile,
                                          )
                         _             <- registerSlot(continuedRun.id, slot)
                         fiber         <- executeInFiber(
                                            run = continuedRun,
                                            runtimeResolution = runtime,
                                            cliTool = ws.cliTool,
                                            repoPath = ws.localPath,
                                            profile = profile,
                                            permissions = permissions,
                                          )
                                            .onExit {
                                              case Exit.Failure(c) if c.isInterruptedOnly =>
                                                (notifyControlPlane(
                                                  continuedRun.agentName,
                                                  AgentExecutionState.Aborted,
                                                  Some(continuedRun.id),
                                                  Some(continuedRun.conversationId),
                                                  Some("Cancelled"),
                                                  0L,
                                                ) *>
                                                  lifecycle.updateRunStatus(continuedRun.id, RunStatus.Cancelled) *>
                                                  lifecycle.maybeCleanupWorktree(continuedRun, CleanupMode.OnCancelled) *>
                                                  appendToConversation(
                                                    continuedRun.conversationId,
                                                    "Run cancelled by user.",
                                                  ).ignore).ignore
                                              case _                                      => ZIO.unit
                                            }
                                            .ensuring(fiberRegistry.update(_ - continuedRun.id))
                                            .forkDaemon
                         _             <- fiberRegistry.update(_ + (continuedRun.id -> fiber))
                         _             <- appendToConversation(
                                            run.conversationId,
                                            s"Created continuation run `${continuedRun.id}`",
                                          ).ignore
                       yield continuedRun).tapError(_ => releaseAgentSlot(slot))
    yield continuedRun

  private def buildPrompt(
    req: AssignRunRequest,
    issue: Option[DomainIssue],
    repoPath: String,
    worktreePath: String,
    permissions: AgentPermissions,
  ): String =
    val directoryGuardrail  =
      s"""Execution constraints:
         |- You MUST create/modify/delete files ONLY under: $worktreePath
         |- Do NOT write to: $repoPath
         |- If an absolute path is needed, always use the working directory path above.
         |""".stripMargin
    val permissionGuardrail =
      s"""Permission guardrails:
         |- Trust level: ${permissions.network match
          case NetworkAccessScope.Disabled          => "restricted"
          case NetworkAccessScope.WorkspaceServices => "standard"
          case NetworkAccessScope.Unrestricted      => "elevated"
        }
         |- Read scopes: ${permissions.fileSystem.readScopes.map(renderScope).mkString(", ")}
         |- Write scopes: ${permissions.fileSystem.writeScopes.map(renderScope).mkString(", ")}
         |- Network: ${permissions.network}
         |- Git: branch=${permissions.git.createBranch}, commit=${permissions.git.commit}, push=${permissions.git.push}, rollback=${permissions.git.rollback}
         |- Token budget: ${permissions.resources.maxEstimatedTokens.map(_.toString).getOrElse("unbounded")}
         |""".stripMargin
    issue match
      case None    =>
        // No issue record found; fall back to the raw title from the UI
        s"""Issue: ${req.issueRef}
           |Task: ${req.prompt}
           |
           |$directoryGuardrail
           |$permissionGuardrail
           |Repository: $repoPath
           |Working directory: $worktreePath""".stripMargin
      case Some(i) =>
        val extras = List(
          if i.description.nonEmpty then Some(s"Description:\n${i.description}") else None,
          if i.contextPath.nonEmpty then Some(s"Context path: ${i.contextPath}") else None,
          if i.sourceFolder.nonEmpty then Some(s"Source folder: ${i.sourceFolder}") else None,
          i.acceptanceCriteria.filter(_.nonEmpty).map(ac => s"Acceptance criteria:\n$ac"),
          Option.when(i.proofOfWorkRequirements.nonEmpty)(
            s"Proof of work:\n${i.proofOfWorkRequirements.map("- " + _).mkString("\n")}"
          ),
          i.kaizenSkill.map(skill => s"Skill: $skill"),
          // Suppress req.prompt when it is a reformatted copy of the issue text
          // (e.g. auto-filled by the dispatch system).  Genuine operator additions
          // start with different text, so comparing prefixes is a reliable heuristic.
          {
            val trimmed           = req.prompt.trim
            val isRedundantPrompt =
              trimmed.isEmpty ||
              (i.title.nonEmpty && trimmed.startsWith(i.title.trim)) ||
              (i.description.nonEmpty && trimmed.startsWith(i.description.trim))
            Option.unless(isRedundantPrompt)(s"Additional instructions:\n${req.prompt}")
          },
        ).flatten.mkString("\n")
        s"""Issue ${req.issueRef}: ${i.title}${if extras.nonEmpty then s"\n$extras" else ""}
           |
           |$directoryGuardrail
           |$permissionGuardrail
           |Repository: $repoPath
           |Working directory: $worktreePath""".stripMargin

  private def sanitizeBranchPart(value: String): String =
    value.trim.toLowerCase.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")

  private def reserveAgentSlot(agentName: String): IO[WorkspaceError, SlotHandle] =
    for
      available <- availableAgentSlots(agentName)
      _         <-
        if available <= 0 then
          ZIO.fail(
            WorkspaceError.InvalidRunState(
              runId = agentName.trim,
              expected = "available_slots > 0",
              actual = s"available_slots = $available",
            )
          )
        else ZIO.unit
      handle    <- acquireAgentSlot(agentName)
    yield handle

  private def resolvedPermissions(
    profile: Option[_root_.agent.entity.Agent],
    cliTool: String,
  ): AgentPermissions =
    profile.map(_.permissions).getOrElse(
      AgentPermissions.defaults(
        trustLevel = profile.map(_.trustLevel).getOrElse(_root_.agent.entity.TrustLevel.Standard),
        cliTool = cliTool,
        timeout = profile.map(_.timeout).getOrElse(java.time.Duration.ofSeconds(timeoutSeconds)),
        maxEstimatedTokens = None,
      )
    )

  private def enforcePermissions(
    agentName: String,
    workspace: Workspace,
    worktreePath: String,
    permissions: AgentPermissions,
    trustLevel: Option[TrustLevel],
  ): IO[WorkspaceError, ExecutionRuntime.Resolution] =
    for
      _ <- ensurePathAllowed(
             path = workspace.localPath,
             scopes = permissions.fileSystem.readScopes,
             workspaceRoot = workspace.localPath,
             worktreePath = worktreePath,
             label = "workspace read",
           )
      _ <- ensurePathAllowed(
             path = worktreePath,
             scopes = permissions.fileSystem.writeScopes,
             workspaceRoot = workspace.localPath,
             worktreePath = worktreePath,
             label = "worktree write",
           )
      _ <- CliAgentRunner
             .validatePermissions(workspace.cliTool, Some(permissions))
             .fold[IO[WorkspaceError, Unit]](
               reason => ZIO.fail(WorkspaceError.PermissionDenied(agentName, reason)),
               _ => ZIO.unit,
             )
      _ <- workspace.runMode match
             case RunMode.Host if permissions.network != NetworkAccessScope.Unrestricted =>
               ZIO.logWarning(
                 s"Host-mode run for $agentName cannot fully enforce network scope ${permissions.network}; applying prompt and path guardrails only."
               )
             case _                                                                      =>
               ZIO.unit
    yield resolveExecutionRuntime(workspace.runMode, permissions, trustLevel)

  private def executionContext(
    runId: String,
    cliTool: String,
    prompt: String,
    worktreePath: String,
    repoPath: String,
    profile: Option[_root_.agent.entity.Agent],
    permissions: AgentPermissions,
  ): ExecutionRuntime.Context =
    ExecutionRuntime.Context(
      runId = runId,
      cliTool = cliTool,
      prompt = prompt,
      worktreePath = worktreePath,
      repoPath = repoPath,
      runCommand = runCliAgent,
      envVars = workspaceLevelEnvVars ++ profile.map(_.envVars).getOrElse(Map.empty),
      permissions = permissions,
      resources = ExecutionRuntime.Resources(
        dockerMemoryLimit = profile.flatMap(_.dockerMemoryLimit),
        dockerCpuLimit = profile.flatMap(_.dockerCpuLimit),
      ),
    )

  private def runtimePreflight(
    resolution: ExecutionRuntime.Resolution,
    context: ExecutionRuntime.Context,
  ): IO[WorkspaceError, Unit] =
    resolution.mode match
      case _: RunMode.Docker => dockerCheck *> resolution.runtime.preflight(context)
      case _                 => resolution.runtime.preflight(context)

  private def ensurePathAllowed(
    path: String,
    scopes: List[AgentPathScope],
    workspaceRoot: String,
    worktreePath: String,
    label: String,
  ): IO[WorkspaceError, Unit] =
    val candidate = Paths.get(path).normalize()
    if scopes.contains(AgentPathScope.CrossWorkspace) then ZIO.unit
    else
      val allowedRoots = scopes.flatMap(resolveScopePath(_, workspaceRoot, worktreePath))
      if allowedRoots.exists(root => candidate.startsWith(root)) then ZIO.unit
      else
        ZIO.fail(
          WorkspaceError.PermissionDenied(
            label,
            s"$candidate is outside allowed scopes: ${scopes.map(renderScope).mkString(", ")}",
          )
        )

  private def resolveScopePath(scope: AgentPathScope, workspaceRoot: String, worktreePath: String): Option[Path] =
    scope match
      case AgentPathScope.Worktree        => Some(Paths.get(worktreePath).normalize())
      case AgentPathScope.WorkspaceRoot   => Some(Paths.get(workspaceRoot).normalize())
      case AgentPathScope.WorkspaceConfig => Some(Paths.get(workspaceRoot).resolve(".llm4zio").normalize())
      case AgentPathScope.CrossWorkspace  => None
      case AgentPathScope.Absolute(path)  => Some(Paths.get(path).normalize())

  private def renderScope(scope: AgentPathScope): String =
    scope match
      case AgentPathScope.Worktree        => "worktree"
      case AgentPathScope.WorkspaceRoot   => "workspace-root"
      case AgentPathScope.WorkspaceConfig => "workspace-config"
      case AgentPathScope.CrossWorkspace  => "cross-workspace"
      case AgentPathScope.Absolute(path)  => path

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

  private def executeInFiber(
    run: WorkspaceRun,
    runtimeResolution: ExecutionRuntime.Resolution,
    cliTool: String,
    repoPath: String = "",
    profile: Option[_root_.agent.entity.Agent] = None,
    permissions: AgentPermissions,
  ): IO[WorkspaceError, Unit] =
    val context = executionContext(
      runId = run.id,
      cliTool = cliTool,
      prompt = run.prompt,
      worktreePath = run.worktreePath,
      repoPath = repoPath,
      profile = profile,
      permissions = permissions,
    )
    val timeout = profile.map(_.timeout).getOrElse(java.time.Duration.ofSeconds(timeoutSeconds))
    lifecycle.captureGitSnapshot(run.worktreePath, permissions)
      .catchAll(error =>
        ZIO.logWarning(s"[run:${run.id}] git snapshot unavailable: $error").as(None)
      )
      .flatMap { snapshot =>
        val program = ZIO.acquireReleaseWith(runtimeResolution.runtime.provision(context))(provisioned =>
          runtimeResolution.runtime.cleanup(context, provisioned)
        ) { provisioned =>
          for
            _           <- notifyControlPlane(
                             run.agentName,
                             AgentExecutionState.Executing,
                             Some(run.id),
                             Some(run.conversationId),
                             Some(s"Running via ${runtimeResolution.runtime.name} runtime"),
                             0L,
                           )
            _           <- lifecycle.updateRunStatus(run.id, RunStatus.Running(RunSessionMode.Autonomous))
            _           <- gitWatcher.registerRun(run.id, run.worktreePath)
            _           <- ZIO.logInfo(
                             s"[run:${run.id}] launching via ${runtimeResolution.runtime.name} runtime (cwd=${run.worktreePath})"
                           )
            linesRef    <- Ref.make(0)
            tokenRef    <- Ref.make(0L)
            runResult   <- runtimeResolution.runtime
                             .execute(
                               context,
                               provisioned,
                               line =>
                                 for
                                   _           <- linesRef.update(_ + 1)
                                   estimated    = (line.length / 4).toLong.max(0L)
                                   totalTokens <- tokenRef.updateAndGet(_ + estimated)
                                   _           <- recordAgentTokens(run.agentName, estimated)
                                                    .mapError(WorkspaceRunServiceLive.WorkspaceExecutionFailure.apply)
                                   _           <- appendToConversation(run.conversationId, line)
                                                    .tapError(e =>
                                                      ZIO.logWarning(s"[run:${run.id}] failed to persist line to chat: $e")
                                                    )
                                                    .mapError(WorkspaceRunServiceLive.WorkspaceExecutionFailure.apply)
                                                    .ignore
                                   _           <- notifyControlPlane(
                                                    run.agentName,
                                                    AgentExecutionState.Executing,
                                                    Some(run.id),
                                                    Some(run.conversationId),
                                                    Some(s"Streaming output (${totalTokens} estimated tokens)"),
                                                    totalTokens,
                                                  )
                                 yield (),
                             )
                             .timeout(timeout)
                             .tapError(e => ZIO.logError(s"[run:${run.id}] process error: $e"))
                             .either
            artifacts   <- runtimeResolution.runtime
                             .collectArtifacts(context, provisioned)
                             .catchAll(error =>
                               ZIO.logWarning(s"[run:${run.id}] artifact collection failed: $error").as(Nil)
                             )
            _           <- ZIO.foreachDiscard(artifacts)(artifact =>
                             appendToConversation(
                               run.conversationId,
                               s"Collected artifact `${artifact.name}` from `${artifact.location}`",
                             ).ignore
                           )
            _           <- runResult match
                             case Left(error)    =>
                               appendToConversation(run.conversationId, s"Run failed: $error").ignore
                             case Right(None)    =>
                               appendToConversation(run.conversationId, s"Run timed out after ${timeout.toSeconds}s")
                             case Right(Some(_)) =>
                               ZIO.unit
            _           <- ZIO.logWarning(s"[run:${run.id}] timed out after ${timeout.toSeconds}s")
                             .when(runResult == Right(None))
            count       <- linesRef.get
            accTokens   <- tokenRef.get
            exitCode     = runResult.toOption.flatten.map(_.exitCode).getOrElse(1)
            _           <- ZIO.logInfo(s"[run:${run.id}] finished exit=$exitCode lines=$count")
                             .when(runResult.toOption.flatten.isDefined)
            status       = runResult match
                             case Right(Some(result)) if result.exitCode == 0 => RunStatus.Completed
                             case _                                           => RunStatus.Failed
            finalState   = if status == RunStatus.Completed then AgentExecutionState.Idle else AgentExecutionState.Failed
            finalMessage = runResult match
                             case Left(error)         => s"Failed: $error"
                             case Right(None)         => s"Timed out after ${timeout.toSeconds}s"
                             case Right(Some(result)) =>
                               s"Exit code ${result.exitCode} via ${runtimeResolution.runtime.name} runtime"
            _           <- lifecycle.rollbackToSnapshot(run, snapshot, permissions)
                             .catchAll(error => ZIO.logWarning(s"[run:${run.id}] rollback failed: $error"))
                             .when(status != RunStatus.Completed)
            _           <- notifyControlPlane(
                             run.agentName,
                             finalState,
                             Some(run.id),
                             Some(run.conversationId),
                             Some(finalMessage),
                             accTokens,
                           )
            _           <- lifecycle.updateRunStatus(run.id, status)
            _           <- ZIO.logInfo(s"[run:${run.id}] status=$status")
          yield ()
        }
        program.onInterrupt(
          lifecycle.rollbackToSnapshot(run, snapshot, permissions)
            .catchAll(error => ZIO.logWarning(s"[run:${run.id}] rollback failed during interrupt: $error"))
            .ignore
        ).ensuring(gitWatcher.unregisterRun(run.id))
      }

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
                .mapWorkspacePersistence("append_run_conversation_message")
    yield ()
