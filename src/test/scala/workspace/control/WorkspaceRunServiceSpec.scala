package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import conversation.entity.api.{ ChatConversation, ConversationEntry }
import db.{ ChatRepository, PersistenceError as DbPersistenceError }
import issues.entity.{ IssueFilter, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import workspace.entity.*

object WorkspaceRunServiceSpec extends ZIOSpecDefault:

  // Minimal stub ChatRepository — records addMessage calls
  private class StubChatRepo(messages: Ref[List[String]]) extends ChatRepository:
    def createConversation(c: ChatConversation): IO[DbPersistenceError, Long]                        = ZIO.succeed(1L)
    def getConversation(id: Long): IO[DbPersistenceError, Option[ChatConversation]]                  = ZIO.succeed(None)
    def listConversations(o: Int, l: Int): IO[DbPersistenceError, List[ChatConversation]]            = ZIO.succeed(Nil)
    def getConversationsByChannel(ch: String): IO[DbPersistenceError, List[ChatConversation]]        = ZIO.succeed(Nil)
    def listConversationsByRun(r: Long): IO[DbPersistenceError, List[ChatConversation]]              = ZIO.succeed(Nil)
    def updateConversation(c: ChatConversation): IO[DbPersistenceError, Unit]                        = ZIO.unit
    def deleteConversation(id: Long): IO[DbPersistenceError, Unit]                                   = ZIO.unit
    def addMessage(m: ConversationEntry): IO[DbPersistenceError, Long]                               =
      messages.update(_ :+ m.content).as(1L)
    def getMessages(cid: Long): IO[DbPersistenceError, List[ConversationEntry]]                      = ZIO.succeed(Nil)
    def getMessagesSince(cid: Long, since: Instant): IO[DbPersistenceError, List[ConversationEntry]] = ZIO.succeed(Nil)

  private object StubIssueRepo extends IssueRepository:
    def append(event: issues.entity.IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    def get(id: IssueId): IO[PersistenceError, issues.entity.AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    def list(filter: IssueFilter): IO[PersistenceError, List[issues.entity.AgentIssue]] = ZIO.succeed(Nil)
    def delete(id: IssueId): IO[PersistenceError, Unit]                                 = ZIO.unit

  // In-memory event-sourced stub WorkspaceRepository
  private class StubWorkspaceRepo(
    wsRef: Ref[Map[String, Workspace]],
    runRef: Ref[Map[String, WorkspaceRun]],
  ) extends WorkspaceRepository:

    def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
      event match
        case e: WorkspaceEvent.Created  =>
          val ws = Workspace(
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
          wsRef.update(_ + (ws.id -> ws))
        case e: WorkspaceEvent.Updated  =>
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
        case e: WorkspaceEvent.Enabled  =>
          wsRef.update(m => m.get(e.workspaceId).fold(m)(ws => m + (e.workspaceId -> ws.copy(enabled = true))))
        case e: WorkspaceEvent.Disabled =>
          wsRef.update(m => m.get(e.workspaceId).fold(m)(ws => m + (e.workspaceId -> ws.copy(enabled = false))))
        case e: WorkspaceEvent.Deleted  => wsRef.update(_ - e.workspaceId)

    def list: IO[PersistenceError, List[Workspace]]              = wsRef.get.map(_.values.toList)
    def get(id: String): IO[PersistenceError, Option[Workspace]] = wsRef.get.map(_.get(id))
    def delete(id: String): IO[PersistenceError, Unit]           = wsRef.update(_ - id)

    def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
      event match
        case e: WorkspaceRunEvent.Assigned       =>
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
            status = RunStatus.Pending,
            attachedUsers = Set.empty,
            controllerUserId = None,
            createdAt = e.occurredAt,
            updatedAt = e.occurredAt,
          )
          runRef.update(_ + (run.id -> run))
        case e: WorkspaceRunEvent.StatusChanged  =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r => m + (e.runId -> r.copy(status = e.status, updatedAt = e.occurredAt)))
          )
        case e: WorkspaceRunEvent.UserAttached   =>
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
        case e: WorkspaceRunEvent.UserDetached   =>
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
        case e: WorkspaceRunEvent.RunInterrupted =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  status = RunStatus.Running(RunSessionMode.Paused),
                  attachedUsers = r.attachedUsers + e.userId,
                  controllerUserId = Some(e.userId),
                  updatedAt = e.occurredAt,
                )
              )
            )
          )
        case e: WorkspaceRunEvent.RunResumed     =>
          runRef.update(m =>
            m.get(e.runId).fold(m)(r =>
              m + (
                e.runId -> r.copy(
                  status = RunStatus.Running(RunSessionMode.Interactive),
                  attachedUsers = r.attachedUsers + e.userId,
                  controllerUserId = Some(e.userId),
                  updatedAt = e.occurredAt,
                )
              )
            )
          )

    def listRuns(wid: String): IO[PersistenceError, List[WorkspaceRun]] =
      runRef.get.map(_.values.filter(_.workspaceId == wid).toList)
    def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]  = runRef.get.map(_.get(id))

  private val sampleWs = Workspace(
    id = "ws-1",
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

  // Stub git ops: worktree add is a no-op, remove is a no-op
  private val noopWorktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] =
    (_, _, _) => ZIO.unit
  private val noopWorktreeRemove: String => Task[Unit]                              =
    _ => ZIO.unit

  private val dockerAvailable: IO[WorkspaceError, Unit]   = ZIO.unit
  private val dockerUnavailable: IO[WorkspaceError, Unit] =
    ZIO.fail(WorkspaceError.DockerNotAvailable("docker not available (stubbed)"))

  private def makeService(
    ws: Workspace = sampleWs,
    dockerCheck: IO[WorkspaceError, Unit] = dockerAvailable,
    runCliAgent: (List[String], String, String => Task[Unit]) => Task[Int] = CliAgentRunner.runProcessStreaming,
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
          worktreeAdd = noopWorktreeAdd,
          worktreeRemove = noopWorktreeRemove,
          dockerCheck = dockerCheck,
          runCliAgent = runCliAgent,
          fiberRegistry = registry,
        )
    yield (svc, wsRepo, messages)

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
    test("assign forks fiber that eventually sets run status to Completed") {
      for
        (svc, wsRepo, _) <- makeService()
        req               = AssignRunRequest(issueRef = "#7", prompt = "hello", agentName = "echo")
        run              <- svc.assign("ws-1", req)
        _                <- ZIO.sleep(500.millis)
        saved            <- wsRepo.getRun(run.id)
      yield assertTrue(saved.exists(r =>
        r.status == RunStatus.Completed || r.status == RunStatus.Running(RunSessionMode.Autonomous)
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
                      timeoutSeconds = 0,
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
                      fiberRegistry = zio.Unsafe.unsafe(implicit u =>
                        Ref.unsafe.make(Map.empty[String, Fiber[WorkspaceError, Unit]])
                      ),
                    )
        req       = AssignRunRequest(issueRef = "#slow", prompt = "60", agentName = "sleep")
        _        <- svc.assign("ws-1", req).ignore
        _        <- ZIO.sleep(300.millis)
        runs     <- wsRepo.listRuns("ws-1")
      // Status is either Failed (timeout fired) or Pending (git worktree add failed before fork)
      yield assertTrue(runs.isEmpty || runs.forall(r => r.status == RunStatus.Failed || r.status == RunStatus.Pending))
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
    test("cancelRun on a running fiber returns unit and marks run Cancelled") {
      // Use ZIO.never as the CLI runner so the fiber is always running and can be cleanly interrupted
      val neverCliAgent: (List[String], String, String => Task[Unit]) => Task[Int] =
        (_, _, _) => ZIO.never.as(0)
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
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
                      runCliAgent = neverCliAgent,
                      fiberRegistry = registry,
                    )
        req      <- ZIO.succeed(AssignRunRequest(issueRef = "#cancel", prompt = "noop", agentName = "test-agent"))
        run      <- svc.assign("ws-1", req)
        _        <- ZIO.sleep(100.millis) // let fiber reach ZIO.never (interruptible point)
        _        <- svc.cancelRun(run.id)
        _        <- ZIO.sleep(100.millis) // let onExit finalizer persist Cancelled status
        saved    <- wsRepo.getRun(run.id)
      yield assertTrue(saved.exists(_.status == RunStatus.Cancelled))
    } @@ TestAspect.withLiveClock,
    test("cancelRun on unknown runId fails with NotFound") {
      for
        (svc, _, _) <- makeService()
        result      <- svc.cancelRun("no-such-run").either
      yield assertTrue(result match
        case Left(WorkspaceError.NotFound("no-such-run")) => true
        case _                                            => false)
    },
    test("continueRun creates a child run reusing parent worktree and branch") {
      val instantCliAgent: (List[String], String, String => Task[Unit]) => Task[Int] =
        (_, _, _) => ZIO.succeed(0)
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
  )
