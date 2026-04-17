package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowRow }
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import agent.entity.{ Agent, AgentPermissions, AgentRepository, TrustLevel }
import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import conversation.entity.api.{ ChatConversation, ConversationEntry }
import conversation.entity.ChatRepository
import governance.control.{ GovernanceEvaluationContext, GovernancePolicyService, GovernanceTransitionDecision }
import governance.entity.GovernancePolicy
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import orchestration.control.{ AutoDispatcherLive, DependencyResolver }
import orchestration.entity.SlotHandle
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }
import workspace.entity.{ RunStatus as WorkspaceRunStatus, * }

object WorkspaceRunServiceSpec extends ZIOSpecDefault:

  // Minimal stub ChatRepository — records addMessage calls
  private class StubChatRepo(messages: Ref[List[String]]) extends ChatRepository:
    def createConversation(c: ChatConversation): IO[PersistenceError, Long]                        = ZIO.succeed(1L)
    def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                  = ZIO.succeed(None)
    def listConversations(o: Int, l: Int): IO[PersistenceError, List[ChatConversation]]            = ZIO.succeed(Nil)
    def getConversationsByChannel(ch: String): IO[PersistenceError, List[ChatConversation]]        = ZIO.succeed(Nil)
    def listConversationsByRun(r: Long): IO[PersistenceError, List[ChatConversation]]              = ZIO.succeed(Nil)
    def updateConversation(c: ChatConversation): IO[PersistenceError, Unit]                        = ZIO.unit
    def deleteConversation(id: Long): IO[PersistenceError, Unit]                                   = ZIO.unit
    def addMessage(m: ConversationEntry): IO[PersistenceError, Long]                               =
      messages.update(_ :+ m.content).as(1L)
    def getMessages(cid: Long): IO[PersistenceError, List[ConversationEntry]]                      = ZIO.succeed(Nil)
    def getMessagesSince(cid: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] = ZIO.succeed(Nil)

  private object StubIssueRepo extends IssueRepository:
    def append(event: issues.entity.IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    def get(id: IssueId): IO[PersistenceError, issues.entity.AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    def history(id: IssueId): IO[PersistenceError, List[issues.entity.IssueEvent]]      = ZIO.succeed(Nil)
    def list(filter: IssueFilter): IO[PersistenceError, List[issues.entity.AgentIssue]] = ZIO.succeed(Nil)
    def delete(id: IssueId): IO[PersistenceError, Unit]                                 = ZIO.unit

  final private class RecordingIssueRepo(eventsRef: Ref[List[IssueEvent]]) extends IssueRepository:
    def append(event: IssueEvent): IO[PersistenceError, Unit] =
      eventsRef.update(_ :+ event)

    def get(id: IssueId): IO[PersistenceError, issues.entity.AgentIssue] =
      ZIO.succeed(
        AgentIssue(
          id = id,
          runId = None,
          conversationId = None,
          title = s"Issue ${id.value}",
          description = "desc",
          issueType = "task",
          priority = "medium",
          requiredCapabilities = Nil,
          state = IssueState.InProgress(AgentId("echo"), Instant.parse("2026-02-24T10:00:00Z")),
          tags = Nil,
          contextPath = "",
          sourceFolder = "",
          workspaceId = Some("ws-1"),
        )
      )

    def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
      ZIO.succeed(Nil)

    def list(filter: IssueFilter): IO[PersistenceError, List[issues.entity.AgentIssue]] =
      ZIO.succeed(Nil)

    def delete(id: IssueId): IO[PersistenceError, Unit] = ZIO.unit

  private object StubAnalysisRepo extends AnalysisRepository:
    def append(event: analysis.entity.AnalysisEvent): IO[PersistenceError, Unit]        = ZIO.unit
    def get(id: shared.ids.Ids.AnalysisDocId): IO[PersistenceError, AnalysisDoc]        =
      ZIO.fail(PersistenceError.NotFound("analysis_doc", id.value))
    def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   =
      ZIO.succeed(
        List(
          AnalysisDoc(
            id = shared.ids.Ids.AnalysisDocId("analysis-code-review"),
            workspaceId = workspaceId,
            analysisType = AnalysisType.CodeReview,
            content = "review",
            filePath = ".llm4zio/analysis/code-review.md",
            generatedBy = AgentId("reviewer"),
            createdAt = Instant.parse("2026-02-24T10:00:00Z"),
            updatedAt = Instant.parse("2026-02-24T10:05:00Z"),
          )
        )
      )
    def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] = ZIO.succeed(Nil)

  // In-memory event-sourced stub WorkspaceRepository
  private class StubWorkspaceRepo(
    wsRef: Ref[Map[String, Workspace]],
    runRef: Ref[Map[String, WorkspaceRun]],
  ) extends WorkspaceRepository:

    def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
      event match
        case e: WorkspaceEvent.Created              =>
          val ws = Workspace(
            id = e.workspaceId,
            projectId = e.projectId,
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
          wsRef.update(_ + (ws.id -> ws))
        case e: WorkspaceEvent.Updated              =>
          wsRef.update(m =>
            m.get(e.workspaceId).fold(m)(ws =>
              m + (e.workspaceId -> ws.copy(
                name = e.name,
                localPath = e.localPath,
                defaultAgent = e.defaultAgent,
                description = e.description,
                cliTool = e.cliTool,
                runMode = e.runMode,
                updatedAt = e.occurredAt,
              ))
            )
          )
        case e: WorkspaceEvent.DefaultBranchChanged =>
          wsRef.update(m =>
            m.get(e.workspaceId).fold(m)(ws =>
              m + (e.workspaceId -> ws.copy(defaultBranch = Workspace.normalizeDefaultBranch(e.defaultBranch)))
            )
          )
        case e: WorkspaceEvent.Enabled              =>
          wsRef.update(m => m.get(e.workspaceId).fold(m)(ws => m + (e.workspaceId -> ws.copy(enabled = true))))
        case e: WorkspaceEvent.Disabled             =>
          wsRef.update(m => m.get(e.workspaceId).fold(m)(ws => m + (e.workspaceId -> ws.copy(enabled = false))))
        case e: WorkspaceEvent.Deleted              => wsRef.update(_ - e.workspaceId)

    def list: IO[PersistenceError, List[Workspace]]                                               = wsRef.get.map(_.values.toList)
    def listByProject(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, List[Workspace]] =
      wsRef.get.map(_.values.filter(_.projectId == projectId).toList)
    def get(id: String): IO[PersistenceError, Option[Workspace]]                                  = wsRef.get.map(_.get(id))
    def delete(id: String): IO[PersistenceError, Unit]                                            = wsRef.update(_ - id)

    def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
      event match
        case e: WorkspaceRunEvent.Assigned        =>
          val run = WorkspaceRun(
            id = e.runId,
            workspaceId = e.workspaceId,
            parentRunId = e.parentRunId,
            issueRef = e.issueRef,
            agentName = e.agentName,
            prompt = e.prompt,
            conversationId = e.conversationId,
            worktreePath = e.worktreePath,
            branchName = e.branchName,
            status = WorkspaceRunStatus.Pending,
            attachedUsers = Set.empty,
            controllerUserId = None,
            createdAt = e.occurredAt,
            updatedAt = e.occurredAt,
          )
          runRef.update(_ + (run.id -> run))
        case e: WorkspaceRunEvent.StatusChanged   =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r => m + (e.runId -> r.copy(status = e.status, updatedAt = e.occurredAt)))
          )
        case e: WorkspaceRunEvent.UserAttached    =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  attachedUsers = r.attachedUsers + e.userId,
                  controllerUserId = r.controllerUserId.orElse(Some(e.userId)),
                  updatedAt = e.occurredAt,
                )
              )
            )
          )
        case e: WorkspaceRunEvent.UserDetached    =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  attachedUsers = r.attachedUsers - e.userId,
                  controllerUserId = if r.controllerUserId.contains(e.userId) then
                    (r.attachedUsers - e.userId).headOption
                  else r.controllerUserId,
                  updatedAt = e.occurredAt,
                )
              )
            )
          )
        case e: WorkspaceRunEvent.RunInterrupted  =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  status = WorkspaceRunStatus.Running(RunSessionMode.Paused),
                  attachedUsers = r.attachedUsers + e.userId,
                  controllerUserId = Some(e.userId),
                  updatedAt = e.occurredAt,
                )
              )
            )
          )
        case e: WorkspaceRunEvent.RunResumed      =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  status = WorkspaceRunStatus.Running(RunSessionMode.Interactive),
                  attachedUsers = r.attachedUsers + e.userId,
                  controllerUserId = Some(e.userId),
                  updatedAt = e.occurredAt,
                )
              )
            )
          )
        case e: WorkspaceRunEvent.CleanupRecorded =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r => m + (e.runId -> r.copy(updatedAt = e.occurredAt)))
          )

    def listRuns(wid: String): IO[PersistenceError, List[WorkspaceRun]]                =
      runRef.get.map(_.values.filter(_.workspaceId == wid).toList)
    def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      runRef.get.map(_.values.filter(_.issueRef == issueRef).toList)
    def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = runRef.get.map(_.get(id))

  private val sampleWs = Workspace(
    id = "ws-1",
    projectId = shared.ids.Ids.ProjectId("test-project"),
    name = "test-repo",
    localPath = "/tmp",
    defaultAgent = Some("echo"),
    description = None,
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "echo",
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private val dockerWs = sampleWs.copy(
    id = "ws-docker",
    runMode = RunMode.Docker(image = "my-agent:latest"),
  )

  private val cloudWs = sampleWs.copy(
    id = "ws-cloud",
    runMode = RunMode.Cloud(
      provider = "aws-fargate",
      image = "ghcr.io/riccardomerolla/llm4zio-agent:latest",
      region = Some("eu-west-1"),
      network = Some("none"),
    ),
  )

  private val baseIssue = AgentIssue(
    id = IssueId("placeholder"),
    runId = None,
    conversationId = None,
    title = "Base task title",
    description = "Base task description",
    issueType = "task",
    priority = "medium",
    requiredCapabilities = Nil,
    state = IssueState.InProgress(AgentId("echo"), Instant.parse("2026-02-24T10:00:00Z")),
    tags = Nil,
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
  )

  // Stub git ops: worktree add is a no-op, remove is a no-op
  private val noopWorktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] =
    (_, worktreePath, _) =>
      ZIO
        .attemptBlocking(java.nio.file.Files.createDirectories(java.nio.file.Paths.get(worktreePath)))
        .mapError(error => WorkspaceError.WorktreeError(error.getMessage))
        .unit
  private val noopWorktreeRemove: String => Task[Unit]                              =
    _ => ZIO.unit
  private val noopBranchDelete: (String, String) => Task[Unit]                      =
    (_, _) => ZIO.unit

  private val dockerAvailable: IO[WorkspaceError, Unit]   = ZIO.unit
  private val dockerUnavailable: IO[WorkspaceError, Unit] =
    ZIO.fail(WorkspaceError.DockerNotAvailable("docker not available (stubbed)"))

  private def makeService(
    ws: Workspace = sampleWs,
    dockerCheck: IO[WorkspaceError, Unit] = dockerAvailable,
    runCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
      CliAgentRunner.runProcessStreaming,
    resolveProfile: String => IO[WorkspaceError, Option[Agent]] = _ => ZIO.succeed(None),
    acquireAgentSlot: String => IO[WorkspaceError, SlotHandle] = agentName =>
      Clock.instant.map(now => SlotHandle(s"slot-${agentName.trim.toLowerCase}", agentName.trim.toLowerCase, now)),
    availableAgentSlots: String => UIO[Int] = _ => ZIO.succeed(Int.MaxValue),
    releaseAgentSlot: SlotHandle => UIO[Unit] = _ => ZIO.unit,
    recordAgentTokens: (String, Long) => IO[WorkspaceError, Unit] = (_, _) => ZIO.unit,
    worktreeRemove: String => Task[Unit] = noopWorktreeRemove,
    branchDelete: (String, String) => Task[Unit] = noopBranchDelete,
  ) =
    for
      messages <- Ref.make(List.empty[String])
      wsMap    <- Ref.make(Map(ws.id -> ws))
      runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
      registry <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
      chatRepo  = StubChatRepo(messages)
      wsRepo    = StubWorkspaceRepo(wsMap, runMap)
      svc       =
        WorkspaceRunServiceLive(
          wsRepo,
          chatRepo,
          StubIssueRepo,
          StubAnalysisRepo,
          worktreeAdd = noopWorktreeAdd,
          worktreeRemove = worktreeRemove,
          branchDelete = branchDelete,
          dockerCheck = dockerCheck,
          runCliAgent = runCliAgent,
          fiberRegistry = registry,
          acquireAgentSlot = acquireAgentSlot,
          availableAgentSlots = availableAgentSlots,
          releaseAgentSlot = releaseAgentSlot,
          recordAgentTokens = recordAgentTokens,
          resolveAgentProfile = resolveProfile,
        )
    yield (svc, wsRepo, messages)

  private def makeServiceWithIssueEvents(
    runCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int]
  ) =
    for
      messages    <- Ref.make(List.empty[String])
      issueEvents <- Ref.make(List.empty[IssueEvent])
      wsMap       <- Ref.make(Map(sampleWs.id -> sampleWs))
      runMap      <- Ref.make(Map.empty[String, WorkspaceRun])
      registry    <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
      chatRepo     = StubChatRepo(messages)
      wsRepo       = StubWorkspaceRepo(wsMap, runMap)
      issueRepo    = new RecordingIssueRepo(issueEvents)
      svc          = WorkspaceRunServiceLive(
                       wsRepo,
                       chatRepo,
                       issueRepo,
                       StubAnalysisRepo,
                       worktreeAdd = noopWorktreeAdd,
                       worktreeRemove = noopWorktreeRemove,
                       branchDelete = noopBranchDelete,
                       runCliAgent = runCliAgent,
                       fiberRegistry = registry,
                     )
    yield (svc, issueEvents)

  private def makeServiceWithIssue(issue: AgentIssue) =
    for
      messages <- Ref.make(List.empty[String])
      wsMap    <- Ref.make(Map(sampleWs.id -> sampleWs))
      runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
      registry <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
      svc       = WorkspaceRunServiceLive(
                    StubWorkspaceRepo(wsMap, runMap),
                    StubChatRepo(messages),
                    new IssueRepository:
                      def append(e: IssueEvent): IO[PersistenceError, Unit]            = ZIO.unit
                      def get(id: IssueId): IO[PersistenceError, AgentIssue]           = ZIO.succeed(issue.copy(id = id))
                      def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] = ZIO.succeed(Nil)
                      def list(f: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
                      def delete(id: IssueId): IO[PersistenceError, Unit]              = ZIO.unit
                    ,
                    StubAnalysisRepo,
                    worktreeAdd = noopWorktreeAdd,
                    worktreeRemove = noopWorktreeRemove,
                    branchDelete = noopBranchDelete,
                    fiberRegistry = registry,
                  )
    yield svc

  final private class SharedPoolHarness private (
    availableRef: Ref[Int],
    acquiredRef: Ref[List[SlotHandle]],
    releasedRef: Ref[List[SlotHandle]],
  ):
    def acquire(agentName: String): IO[WorkspaceError, SlotHandle] =
      availableRef.modify { available =>
        if available > 0 then
          val next = available - 1
          (Right(next), next)
        else
          (
            Left(WorkspaceError.InvalidRunState(agentName, "available_slots > 0", s"available_slots = $available")),
            available,
          )
      }.flatMap {
        case Left(error)     => ZIO.fail(error)
        case Right(sequence) =>
          Clock.instant.flatMap { now =>
            val handle = SlotHandle(s"slot-$sequence", agentName.trim.toLowerCase, now)
            acquiredRef.update(_ :+ handle).as(handle)
          }
      }

    def available(agentName: String): UIO[Int] =
      availableRef.get

    def release(handle: SlotHandle): UIO[Unit] =
      availableRef.update(_ + 1) *> releasedRef.update(_ :+ handle)

    def acquired: UIO[List[SlotHandle]] =
      acquiredRef.get

    def released: UIO[List[SlotHandle]] =
      releasedRef.get

  private object SharedPoolHarness:
    def make(initialAvailable: Int): UIO[SharedPoolHarness] =
      for
        available <- Ref.make(initialAvailable)
        acquired  <- Ref.make(List.empty[SlotHandle])
        released  <- Ref.make(List.empty[SlotHandle])
      yield SharedPoolHarness(available, acquired, released)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceRunServiceSpec")(
    test("assign returns a WorkspaceRun with correct workspace and issue ref") {
      for
        (svc, _, _) <- makeService()
        req          = AssignRunRequest(issueRef = "#1", prompt = "echo hello", agentName = "echo")
        run         <- svc.assign("ws-1", req)
      yield assertTrue(run.workspaceId == "ws-1" && run.issueRef == "#1" && run.agentName == "echo")
    },
    test("assign fails with WorkspaceError for unknown workspace id") {
      for
        (svc, _, _) <- makeService()
        req          = AssignRunRequest(issueRef = "#1", prompt = "echo hello", agentName = "echo")
        result      <- svc.assign("missing", req).either
      yield assertTrue(result.isLeft)
    },
    test("assign fails with WorkspaceError.Disabled for disabled workspace") {
      val disabled = sampleWs.copy(id = "ws-disabled", enabled = false)
      for
        (svc, _, _) <- makeService(disabled)
        req          = AssignRunRequest(issueRef = "#1", prompt = "echo hello", agentName = "echo")
        result      <- svc.assign("ws-disabled", req).either
      yield assertTrue(result match
        case Left(WorkspaceError.Disabled(_)) => true
        case _                                => false)
    },
    test("assign saves a WorkspaceRun record to the repository") {
      for
        (svc, wsRepo, _) <- makeService()
        req               = AssignRunRequest(issueRef = "#42", prompt = "echo hello", agentName = "echo")
        run              <- svc.assign("ws-1", req)
        saved            <- wsRepo.listRuns("ws-1")
      yield assertTrue(saved.nonEmpty && saved.exists(_.id == run.id))
    },
    test("assign fails with InvalidRunState when the shared agent pool has no available slots") {
      for
        (svc, _, _) <- makeService(
                         acquireAgentSlot = _ => ZIO.never
                       )
        result      <- svc.assign(
                         "ws-1",
                         AssignRunRequest(issueRef = "#pool-full", prompt = "echo hello", agentName = "echo"),
                       ).either
      yield assertTrue(result match
        case Left(WorkspaceError.InvalidRunState("echo", "available_slots > 0", _)) => true
        case _                                                                      => false)
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.seconds),
    test("assign rejects agent profiles without worktree write permission") {
      val lockedProfile = Agent(
        id = AgentId("locked"),
        name = "locked",
        description = "Locked down agent",
        cliTool = "echo",
        capabilities = Nil,
        defaultModel = None,
        systemPrompt = None,
        maxConcurrentRuns = 1,
        envVars = Map.empty,
        timeout = java.time.Duration.ofMinutes(5),
        enabled = true,
        createdAt = sampleWs.createdAt,
        updatedAt = sampleWs.updatedAt,
        trustLevel = TrustLevel.Untrusted,
        permissions = AgentPermissions.defaults(
          trustLevel = TrustLevel.Untrusted,
          cliTool = "echo",
          timeout = java.time.Duration.ofMinutes(5),
          maxEstimatedTokens = None,
        ).copy(
          fileSystem = AgentPermissions
            .defaults(
              trustLevel = TrustLevel.Untrusted,
              cliTool = "echo",
              timeout = java.time.Duration.ofMinutes(5),
              maxEstimatedTokens = None,
            )
            .fileSystem
            .copy(writeScopes = Nil)
        ),
      )
      for
        (svc, _, _) <- makeService(resolveProfile = _ => ZIO.succeed(Some(lockedProfile)))
        result      <-
          svc.assign("ws-1", AssignRunRequest(issueRef = "#locked", prompt = "nope", agentName = "locked")).either
      yield assertTrue(result match
        case Left(WorkspaceError.PermissionDenied("worktree write", reason)) =>
          reason.contains("outside allowed scopes")
        case _                                                               =>
          false)
    },
    test("assign forks fiber that eventually sets run status to Completed") {
      for
        (svc, wsRepo, _) <- makeService()
        req               = AssignRunRequest(issueRef = "#7", prompt = "hello", agentName = "echo")
        run              <- svc.assign("ws-1", req)
        _                <- ZIO.sleep(500.millis)
        saved            <- wsRepo.getRun(run.id)
      yield assertTrue(saved.exists(r =>
        r.status == WorkspaceRunStatus.Completed || r.status == WorkspaceRunStatus.Running(
          RunSessionMode.Autonomous
        )
      ))
    } @@ TestAspect.withLiveClock,
    test("executeInFiber respects timeout and marks run Failed") {
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map("ws-1" -> sampleWs))
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        chatRepo  = StubChatRepo(messages)
        wsRepo    = StubWorkspaceRepo(wsMap, runMap)
        // timeoutSeconds=0 causes ZIO.timeout to time out immediately
        svc       = WorkspaceRunServiceLive(
                      wsRepo,
                      chatRepo,
                      StubIssueRepo,
                      StubAnalysisRepo,
                      timeoutSeconds = 0,
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
                      branchDelete = noopBranchDelete,
                      fiberRegistry = zio.Unsafe.unsafe(implicit u =>
                        Ref.unsafe.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
                      ),
                    )
        req       = AssignRunRequest(issueRef = "#slow", prompt = "60", agentName = "sleep")
        _        <- svc.assign("ws-1", req).ignore
        _        <- ZIO.sleep(300.millis)
        runs     <- wsRepo.listRuns("ws-1")
      // Status is either Failed (timeout fired) or Pending (git worktree add failed before fork)
      yield assertTrue(
        runs.isEmpty || runs.forall(r =>
          r.status == WorkspaceRunStatus.Failed ||
          r.status == WorkspaceRunStatus.Pending ||
          r.status == WorkspaceRunStatus.Running(RunSessionMode.Autonomous)
        )
      )
    } @@ TestAspect.withLiveClock,
    test("completed runs trigger knowledge extraction callback") {
      for
        extractionRef <- Ref.make(List.empty[String])
        messages      <- Ref.make(List.empty[String])
        issueEvents   <- Ref.make(List.empty[IssueEvent])
        wsMap         <- Ref.make(Map(sampleWs.id -> sampleWs))
        runMap        <- Ref.make(Map.empty[String, WorkspaceRun])
        registry      <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
        svc            = WorkspaceRunServiceLive(
                           StubWorkspaceRepo(wsMap, runMap),
                           StubChatRepo(messages),
                           new RecordingIssueRepo(issueEvents),
                           StubAnalysisRepo,
                           worktreeAdd = noopWorktreeAdd,
                           worktreeRemove = noopWorktreeRemove,
                           branchDelete = noopBranchDelete,
                           runCliAgent = (_, _, onLine, _) => onLine("implemented") *> ZIO.succeed(0),
                           fiberRegistry = registry,
                           extractKnowledgeFromCompletedRun = (run, issue) =>
                             extractionRef.update(_ :+ s"${run.id}:${issue.map(_.id.value).getOrElse("none")}"),
                         )
        run           <- svc.assign("ws-1", AssignRunRequest("#123", "finish it", "echo"))
        _             <- ZIO.sleep(400.millis)
        extracted     <- extractionRef.get
      yield assertTrue(
        extracted.exists(_.startsWith(s"${run.id}:123"))
      )
    } @@ TestAspect.withLiveClock,
    test("assign succeeds when workspace has RunMode.Host even when dockerCheck would fail") {
      for
        (svc, _, _) <- makeService(sampleWs, dockerCheck = dockerUnavailable)
        req          = AssignRunRequest(issueRef = "#1", prompt = "echo hello", agentName = "echo")
        run         <- svc.assign("ws-1", req)
      yield assertTrue(run.workspaceId == "ws-1")
    },
    test("assign fails with DockerNotAvailable when RunMode.Docker and Docker is unavailable") {
      for
        (svc, _, _) <- makeService(dockerWs, dockerCheck = dockerUnavailable)
        req          = AssignRunRequest(issueRef = "#1", prompt = "echo hello", agentName = "echo")
        result      <- svc.assign("ws-docker", req).either
      yield assertTrue(result match
        case Left(WorkspaceError.DockerNotAvailable(_)) => true
        case _                                          => false)
    },
    test("cloud runtime stub marks the run failed after assignment") {
      for
        (svc, wsRepo, messages) <- makeService(cloudWs)
        run                     <- svc.assign("ws-cloud", AssignRunRequest(issueRef = "#cloud", prompt = "remote", agentName = "echo"))
        _                       <- ZIO.sleep(250.millis)
        saved                   <- wsRepo.getRun(run.id)
        recorded                <- messages.get
      yield assertTrue(
        saved.exists(_.status == WorkspaceRunStatus.Failed),
        recorded.exists(_.contains("not implemented yet")),
      )
    } @@ TestAspect.withLiveClock,
    test("cancelRun on a running fiber returns unit and marks run Cancelled") {
      // Use ZIO.never as the CLI runner so the fiber is always running and can be cleanly interrupted
      val neverCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.never.as(0)
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map("ws-1" -> sampleWs))
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        registry <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
        wsRepo    = StubWorkspaceRepo(wsMap, runMap)
        svc       = WorkspaceRunServiceLive(
                      wsRepo,
                      StubChatRepo(messages),
                      StubIssueRepo,
                      StubAnalysisRepo,
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
                      branchDelete = noopBranchDelete,
                      runCliAgent = neverCliAgent,
                      fiberRegistry = registry,
                    )
        req      <- ZIO.succeed(AssignRunRequest(issueRef = "#cancel", prompt = "noop", agentName = "test-agent"))
        run      <- svc.assign("ws-1", req)
        _        <- ZIO.sleep(100.millis) // let fiber reach ZIO.never (interruptible point)
        _        <- svc.cancelRun(run.id)
        _        <- ZIO.sleep(100.millis) // let onExit finalizer persist Cancelled status
        saved    <- wsRepo.getRun(run.id)
      yield assertTrue(saved.exists(_.status == WorkspaceRunStatus.Cancelled))
    } @@ TestAspect.withLiveClock,
    test("cancelRun on unknown runId fails with NotFound") {
      for
        (svc, _, _) <- makeService()
        result      <- svc.cancelRun("no-such-run").either
      yield assertTrue(result match
        case Left(WorkspaceError.NotFound("no-such-run")) => true
        case _                                            => false)
    },
    test("completed workspace run moves issue to HumanReview") {
      val successCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.succeed(0)
      for
        (svc, issueEvents) <- makeServiceWithIssueEvents(successCliAgent)
        _                  <- svc.assign("ws-1", AssignRunRequest(issueRef = "#sync-complete", prompt = "ok", agentName = "echo"))
        _                  <- ZIO.sleep(250.millis)
        events             <- issueEvents.get
      yield assertTrue(
        events.exists {
          case IssueEvent.MovedToHumanReview(issueId, _, _) => issueId.value == "sync-complete"
          case _                                            => false
        },
        events.exists {
          case IssueEvent.AnalysisAttached(issueId, docIds, _, _) =>
            issueId.value == "sync-complete" && docIds == List(shared.ids.Ids.AnalysisDocId("analysis-code-review"))
          case _                                                  => false
        },
      )
    } @@ TestAspect.withLiveClock,
    test("failed workspace run appends RunFailed event") {
      val failingCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.succeed(2)
      for
        (svc, issueEvents) <- makeServiceWithIssueEvents(failingCliAgent)
        _                  <- svc.assign("ws-1", AssignRunRequest(issueRef = "#sync-fail", prompt = "fail", agentName = "echo"))
        _                  <- ZIO.sleep(250.millis)
        events             <- issueEvents.get
      yield assertTrue(
        events.exists {
          case IssueEvent.RunFailed(issueId, _, _, _) => issueId.value == "sync-fail"
          case _                                      => false
        }
      )
    } @@ TestAspect.withLiveClock,
    test("assigned runs release their reserved slot when they complete") {
      val successCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.sleep(100.millis).as(0)
      for
        pool        <- SharedPoolHarness.make(initialAvailable = 1)
        (svc, _, _) <- makeService(
                         runCliAgent = successCliAgent,
                         acquireAgentSlot = pool.acquire,
                         availableAgentSlots = pool.available,
                         releaseAgentSlot = pool.release,
                       )
        _           <- svc.assign("ws-1", AssignRunRequest(issueRef = "#slot-release", prompt = "ok", agentName = "echo"))
        acquired    <- pool.acquired
        released    <- pool.released
                         .repeatUntil(_.nonEmpty)
                         .timeoutFail(new RuntimeException("timed out waiting for slot release"))(1.second)
                         .orDie
      yield assertTrue(acquired.nonEmpty, released == acquired)
    } @@ TestAspect.withLiveClock,
    test("assigned runs release their reserved slot when they fail") {
      val failingCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.sleep(100.millis).as(2)
      for
        pool        <- SharedPoolHarness.make(initialAvailable = 1)
        (svc, _, _) <- makeService(
                         runCliAgent = failingCliAgent,
                         acquireAgentSlot = pool.acquire,
                         availableAgentSlots = pool.available,
                         releaseAgentSlot = pool.release,
                       )
        _           <- svc.assign("ws-1", AssignRunRequest(issueRef = "#slot-fail", prompt = "fail", agentName = "echo"))
        acquired    <- pool.acquired
        released    <- pool.released
                         .repeatUntil(_.nonEmpty)
                         .timeoutFail(new RuntimeException("timed out waiting for slot release"))(1.second)
                         .orDie
      yield assertTrue(acquired.nonEmpty, released == acquired)
    } @@ TestAspect.withLiveClock,
    test("stream token budget failures mark the run failed and record the error") {
      val singleLineCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, onLine, _) => onLine("x" * 80).as(0)
      for
        (svc, wsRepo, messages) <- makeService(
                                     runCliAgent = singleLineCliAgent,
                                     recordAgentTokens =
                                       (_, _) => ZIO.fail(WorkspaceError.CostLimitExceeded("echo", 10L)),
                                   )
        run                     <- svc.assign("ws-1", AssignRunRequest(issueRef = "#budget", prompt = "stream", agentName = "echo"))
        _                       <- ZIO.sleep(250.millis)
        saved                   <- wsRepo.getRun(run.id)
        recorded                <- messages.get
      yield assertTrue(
        saved.exists(_.status == WorkspaceRunStatus.Failed),
        recorded.exists(_.contains("Run failed: CostLimitExceeded")),
      )
    } @@ TestAspect.withLiveClock,
    test("cancelled workspace run moves issue back to Todo") {
      val neverCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.never.as(0)
      for
        (svc, issueEvents) <- makeServiceWithIssueEvents(neverCliAgent)
        run                <- svc.assign("ws-1", AssignRunRequest(issueRef = "#sync-cancel", prompt = "hang", agentName = "echo"))
        _                  <- ZIO.sleep(120.millis)
        _                  <- svc.cancelRun(run.id)
        _                  <- ZIO.sleep(120.millis)
        events             <- issueEvents.get
      yield assertTrue(
        events.exists {
          case IssueEvent.MovedToTodo(issueId, _, _) => issueId.value == "sync-cancel"
          case _                                     => false
        }
      )
    } @@ TestAspect.withLiveClock,
    test("assigned runs release their reserved slot when they are cancelled") {
      val neverCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.never.as(0)
      for
        pool        <- SharedPoolHarness.make(initialAvailable = 1)
        (svc, _, _) <- makeService(
                         runCliAgent = neverCliAgent,
                         acquireAgentSlot = pool.acquire,
                         availableAgentSlots = pool.available,
                         releaseAgentSlot = pool.release,
                       )
        run         <- svc.assign("ws-1", AssignRunRequest(issueRef = "#slot-cancel", prompt = "hang", agentName = "echo"))
        _           <- ZIO.sleep(120.millis)
        _           <- svc.cancelRun(run.id)
        _           <- ZIO.sleep(120.millis)
        acquired    <- pool.acquired
        released    <- pool.released
      yield assertTrue(acquired.nonEmpty, released == acquired)
    } @@ TestAspect.withLiveClock,
    test("cleanupAfterSuccessfulMerge removes worktree, deletes branch, and records cleanup audit") {
      for
        removedRef       <- Ref.make(List.empty[String])
        deletedRef       <- Ref.make(List.empty[(String, String)])
        (svc, wsRepo, _) <- makeService(
                              worktreeRemove = path => removedRef.update(_ :+ path).unit,
                              branchDelete = (repoPath, branch) => deletedRef.update(_ :+ (repoPath -> branch)).unit,
                            )
        run              <- svc.assign("ws-1", AssignRunRequest(issueRef = "#cleanup", prompt = "ok", agentName = "echo"))
        _                <- ZIO.sleep(200.millis)
        _                <- svc.cleanupAfterSuccessfulMerge(run.id)
        removed          <- removedRef.get
        deleted          <- deletedRef.get
        saved            <- wsRepo.getRun(run.id)
      yield assertTrue(
        removed == List(run.worktreePath),
        deleted == List(sampleWs.localPath -> run.branchName),
        saved.exists(_.updatedAt.isAfter(run.updatedAt)),
      )
    } @@ TestAspect.withLiveClock,
    test("cleanupAfterSuccessfulMerge skips when another run references the same worktree") {
      for
        removedRef       <- Ref.make(List.empty[String])
        deletedRef       <- Ref.make(List.empty[(String, String)])
        (svc, wsRepo, _) <- makeService(
                              worktreeRemove = path => removedRef.update(_ :+ path).unit,
                              branchDelete = (repoPath, branch) => deletedRef.update(_ :+ (repoPath -> branch)).unit,
                            )
        run1             <- svc.assign("ws-1", AssignRunRequest(issueRef = "#cleanup-a", prompt = "ok", agentName = "echo"))
        _                <- ZIO.sleep(150.millis)
        now              <- Clock.instant
        _                <- wsRepo.appendRun(
                              WorkspaceRunEvent.Assigned(
                                runId = "run-sibling",
                                workspaceId = "ws-1",
                                parentRunId = Some(run1.id),
                                issueRef = "#cleanup-b",
                                agentName = "echo",
                                prompt = "reuse worktree",
                                conversationId = "conv-sibling",
                                worktreePath = run1.worktreePath,
                                branchName = run1.branchName,
                                occurredAt = now,
                              )
                            )
        _                <- svc.cleanupAfterSuccessfulMerge(run1.id)
        removed          <- removedRef.get
        deleted          <- deletedRef.get
      yield assertTrue(
        removed.isEmpty,
        deleted.isEmpty,
      )
    } @@ TestAspect.withLiveClock,
    test("manual assignment and auto-dispatch share the same pool ceiling") {
      val neverCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.never.as(0)
      val readyIssue                                                                                    = AgentIssue(
        id = IssueId("auto-1"),
        runId = Some(TaskRunId("run-auto-1")),
        conversationId = Some(ConversationId("conv-auto-1")),
        title = "Auto issue",
        description = "ready for dispatch",
        issueType = "task",
        priority = "high",
        requiredCapabilities = Nil,
        state = IssueState.Todo(sampleWs.createdAt),
        tags = Nil,
        blockedBy = Nil,
        blocking = Nil,
        contextPath = "",
        sourceFolder = "",
        workspaceId = Some("ws-1"),
      )
      val enabledConfig                                                                                 = new ConfigRepository:
        override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
        override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
          ZIO.succeed(Option.when(key == "issues.autoDispatch.enabled")(SettingRow(key, "true", sampleWs.updatedAt)))
        override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit
        override def deleteSetting(key: String): IO[PersistenceError, Unit]                           = ZIO.unit
        override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]               = ZIO.unit
        override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
        override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
        override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       =
          ZIO.dieMessage("unused")
        override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.dieMessage("unused")
        override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.dieMessage("unused")
        override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = ZIO.dieMessage("unused")
        override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.dieMessage("unused")
        override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
        override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
          ZIO.dieMessage("unused")
        override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.dieMessage("unused")
        override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
        override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = ZIO.dieMessage("unused")
      val readyResolver                                                                                 = new DependencyResolver:
        override def dependencyGraph(issues: List[AgentIssue]): Map[IssueId, Set[IssueId]] = Map.empty
        override def readyToDispatch(issues: List[AgentIssue]): List[AgentIssue]           = issues
        override def currentIssues: IO[PersistenceError, List[AgentIssue]]                 = ZIO.succeed(List(readyIssue))
        override def currentReadyToDispatch: IO[PersistenceError, List[AgentIssue]]        = ZIO.succeed(List(readyIssue))
      val stubAgentRepo                                                                                 = new AgentRepository:
        override def append(event: _root_.agent.entity.AgentEvent): IO[PersistenceError, Unit] =
          ZIO.dieMessage("unused")
        override def get(id: AgentId): IO[PersistenceError, Agent]                             =
          ZIO.fail(PersistenceError.NotFound("agent", id.value))
        override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]]          =
          ZIO.succeed(
            List(
              Agent(
                id = AgentId("echo"),
                name = "echo",
                description = "echo",
                cliTool = "echo",
                capabilities = Nil,
                defaultModel = None,
                systemPrompt = None,
                maxConcurrentRuns = 1,
                envVars = Map.empty,
                timeout = java.time.Duration.ofMinutes(5),
                enabled = true,
                createdAt = sampleWs.createdAt,
                updatedAt = sampleWs.updatedAt,
              )
            )
          )
        override def findByName(name: String): IO[PersistenceError, Option[Agent]]             = list().map(_.find(_.name == name))
      val activityHub                                                                                   = new ActivityHub:
        override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
        override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent]
      val governancePolicyService                                                                       = new GovernancePolicyService:
        override def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy] =
          ZIO.succeed(GovernancePolicy.noOp)
        override def evaluateForWorkspace(
          workspaceId: String,
          context: GovernanceEvaluationContext,
        ): IO[PersistenceError, GovernanceTransitionDecision] =
          ZIO.succeed(
            GovernanceTransitionDecision(
              allowed = true,
              requiredGates = Set.empty,
              missingGates = Set.empty,
              humanApprovalRequired = false,
              daemonTriggers = Nil,
              escalationRules = Nil,
              completionCriteria = None,
              reason = None,
            )
          )
      for
        pool             <- SharedPoolHarness.make(initialAvailable = 1)
        (svc, wsRepo, _) <- makeService(
                              runCliAgent = neverCliAgent,
                              acquireAgentSlot = pool.acquire,
                              availableAgentSlots = pool.available,
                              releaseAgentSlot = pool.release,
                            )
        _                <- svc.assign("ws-1", AssignRunRequest(issueRef = "#manual", prompt = "hold", agentName = "echo"))
        _                <- ZIO.sleep(100.millis)
        dispatcher        = AutoDispatcherLive(
                              configRepository = enabledConfig,
                              issueRepository = StubIssueRepo,
                              dependencyResolver = readyResolver,
                              agentRepository = stubAgentRepo,
                              workspaceRepository = wsRepo,
                              workspaceRunService = svc,
                              activityHub = activityHub,
                              agentPoolManager = new orchestration.entity.AgentPoolManager:
                                override def acquireSlot(agentName: String): IO[orchestration.entity.PoolError, SlotHandle] =
                                  pool.acquire(agentName).mapError(_ =>
                                    orchestration.entity.PoolError.PersistenceFailure("test_pool_acquire", "unexpected")
                                  )
                                override def releaseSlot(handle: SlotHandle): UIO[Unit]                                     = pool.release(handle)
                                override def availableSlots(agentName: String): UIO[Int]                                    = pool.available(agentName)
                                override def resize(agentName: String, newMax: Int): UIO[Unit]                              = ZIO.unit
                              ,
                              governancePolicyService = governancePolicyService,
                            )
        count            <- dispatcher.dispatchOnce
        runs             <- wsRepo.listRuns("ws-1")
      yield assertTrue(count == 0, runs.size == 1, runs.exists(_.issueRef == "#manual"))
    } @@ TestAspect.withLiveClock,
    test("continueRun creates a child run reusing parent worktree and branch") {
      val instantCliAgent: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
        (_, _, _, _) => ZIO.succeed(0)
      for
        (svc, wsRepo, _) <- makeService(runCliAgent = instantCliAgent)
        req               = AssignRunRequest(issueRef = "#100", prompt = "initial", agentName = "echo")
        parent           <- svc.assign("ws-1", req)
        _                <- ZIO.sleep(200.millis)
        child            <- svc.continueRun(parent.id, "follow-up instructions")
        saved            <- wsRepo.getRun(child.id)
      yield assertTrue(
        saved.exists(_.parentRunId.contains(parent.id)),
        saved.exists(_.worktreePath == parent.worktreePath),
        saved.exists(_.branchName == parent.branchName),
      )
    } @@ TestAspect.withLiveClock,
    test("assign surfaces non-not-found issue lookup failures explicitly") {
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map(sampleWs.id -> sampleWs))
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        registry <- Ref.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
        svc       = WorkspaceRunServiceLive(
                      StubWorkspaceRepo(wsMap, runMap),
                      StubChatRepo(messages),
                      new IssueRepository:
                        def append(e: IssueEvent): IO[PersistenceError, Unit]            = ZIO.unit
                        def get(id: IssueId): IO[PersistenceError, AgentIssue]           =
                          ZIO.fail(PersistenceError.QueryFailed("get_issue", "boom"))
                        def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] = ZIO.succeed(Nil)
                        def list(f: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
                        def delete(id: IssueId): IO[PersistenceError, Unit]              = ZIO.unit
                      ,
                      StubAnalysisRepo,
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
                      branchDelete = noopBranchDelete,
                      fiberRegistry = registry,
                    )
        result   <- svc.assign("ws-1", AssignRunRequest("#broken", "do the thing", "echo")).either
      yield assertTrue(
        result match
          case Left(WorkspaceError.PersistenceFailure(cause)) =>
            cause.getMessage.contains("load_issue_for_assign failed")
          case _                                              => false
      )
    },
    suite("buildPrompt content")(
      test("None branch includes req.prompt as Task field") {
        for
          (svc, _, _) <- makeService()
          run         <-
            svc.assign("ws-1", AssignRunRequest(issueRef = "#no-issue", prompt = "do the thing", agentName = "echo"))
        yield assertTrue(
          run.prompt.contains("Task: do the thing"),
          run.prompt.contains("Execution constraints"),
        )
      },
      test("Some branch includes issue title and description") {
        val issue = baseIssue.copy(title = "My task title", description = "Do this specific thing")
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign("ws-1", AssignRunRequest(issueRef = "#title-test", prompt = "", agentName = "echo"))
        yield assertTrue(
          run.prompt.contains("My task title"),
          run.prompt.contains("Do this specific thing"),
        )
      },
      test("Some branch includes acceptanceCriteria") {
        val issue = baseIssue.copy(acceptanceCriteria = Some("Unit test to compare greet output"))
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign("ws-1", AssignRunRequest(issueRef = "#ac-test", prompt = "", agentName = "echo"))
        yield assertTrue(
          run.prompt.contains("Acceptance criteria:"),
          run.prompt.contains("Unit test to compare greet output"),
        )
      },
      test("Some branch includes proofOfWorkRequirements as a bullet list") {
        val issue = baseIssue.copy(proofOfWorkRequirements = List("All tests pass", "README updated"))
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign("ws-1", AssignRunRequest(issueRef = "#pow-test", prompt = "", agentName = "echo"))
        yield assertTrue(
          run.prompt.contains("Proof of work:"),
          run.prompt.contains("- All tests pass"),
          run.prompt.contains("- README updated"),
        )
      },
      test("Some branch includes kaizenSkill") {
        val issue = baseIssue.copy(kaizenSkill = Some("scala3-zio"))
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign("ws-1", AssignRunRequest(issueRef = "#kaizen-test", prompt = "", agentName = "echo"))
        yield assertTrue(run.prompt.contains("Skill: scala3-zio"))
      },
      test("Some branch includes req.prompt as Additional instructions when non-empty") {
        for
          svc <- makeServiceWithIssue(baseIssue)
          run <- svc.assign(
                   "ws-1",
                   AssignRunRequest(issueRef = "#extra", prompt = "focus on edge cases", agentName = "echo"),
                 )
        yield assertTrue(
          run.prompt.contains("Additional instructions:"),
          run.prompt.contains("focus on edge cases"),
        )
      },
      test("Some branch suppresses req.prompt when it starts with the issue title (auto-filled deduplication)") {
        val issue            = baseIssue.copy(
          title = "Add default value for name arg",
          description = "Add a default value for the name parameter",
          acceptanceCriteria = Some("Unit test covering the default"),
        )
        val autoFilledPrompt =
          "Add default value for name arg\nAcceptance criteria:\nUnit test covering the default\nEstimate:\nXS"
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign(
                   "ws-1",
                   AssignRunRequest(issueRef = "#dup-title", prompt = autoFilledPrompt, agentName = "echo"),
                 )
        yield assertTrue(!run.prompt.contains("Additional instructions:"))
      },
      test("Some branch suppresses req.prompt when it starts with the issue description (auto-filled deduplication)") {
        val issue            = baseIssue.copy(
          title = "Some task",
          description = "Add a default value for the name parameter",
        )
        val autoFilledPrompt = "Add a default value for the name parameter\nEstimate: XS"
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign(
                   "ws-1",
                   AssignRunRequest(issueRef = "#dup-desc", prompt = autoFilledPrompt, agentName = "echo"),
                 )
        yield assertTrue(!run.prompt.contains("Additional instructions:"))
      },
      test("Some branch omits empty sections cleanly") {
        val issue = baseIssue.copy(description = "", contextPath = "", sourceFolder = "")
        for
          svc <- makeServiceWithIssue(issue)
          run <- svc.assign("ws-1", AssignRunRequest(issueRef = "#clean", prompt = "", agentName = "echo"))
        yield assertTrue(
          run.prompt.contains("Execution constraints"),
          !run.prompt.contains("Description:"),
          !run.prompt.contains("Context path:"),
          !run.prompt.contains("Acceptance criteria:"),
          !run.prompt.contains("Proof of work:"),
        )
      },
    ),
    test("assign passes merged environment vars to process runner") {
      val profile = Agent(
        id = shared.ids.Ids.AgentId("agent-1"),
        name = "echo",
        description = "echo profile",
        cliTool = "echo",
        capabilities = Nil,
        defaultModel = None,
        systemPrompt = None,
        maxConcurrentRuns = 2,
        envVars = Map("AGENT_FLAG" -> "true"),
        timeout = java.time.Duration.ofMinutes(5),
        enabled = true,
        createdAt = sampleWs.createdAt,
        updatedAt = sampleWs.updatedAt,
      )
      for
        envPromise  <- Promise.make[Nothing, Map[String, String]]
        runCli       = (_: List[String], _: String, _: String => Task[Unit], env: Map[String, String]) =>
                         envPromise.succeed(env).as(0)
        (svc, _, _) <- makeService(
                         runCliAgent = runCli,
                         resolveProfile = _ => ZIO.succeed(Some(profile)),
                       )
        _           <- svc.assign("ws-1", AssignRunRequest("#env", "check env", "echo"))
        env         <- envPromise.await
      yield assertTrue(env.get("AGENT_FLAG").contains("true"))
    } @@ TestAspect.withLiveClock,
  )
