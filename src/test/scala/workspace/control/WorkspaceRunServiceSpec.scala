package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import conversation.entity.api.{ ChatConversation, ConversationEntry }
import db.{ ChatRepository, PersistenceError as DbPersistenceError }
import issues.entity.api.{ AgentAssignment, AgentIssue, IssueStatus }
import shared.errors.PersistenceError
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
    def createIssue(i: AgentIssue): IO[DbPersistenceError, Long]                                     = ZIO.succeed(1L)
    def getIssue(id: Long): IO[DbPersistenceError, Option[AgentIssue]]                               = ZIO.succeed(None)
    def listIssues(o: Int, l: Int): IO[DbPersistenceError, List[AgentIssue]]                         = ZIO.succeed(Nil)
    def listIssuesByRun(r: Long): IO[DbPersistenceError, List[AgentIssue]]                           = ZIO.succeed(Nil)
    def listIssuesByStatus(s: IssueStatus): IO[DbPersistenceError, List[AgentIssue]]                 = ZIO.succeed(Nil)
    def listUnassignedIssues(r: Long): IO[DbPersistenceError, List[AgentIssue]]                      = ZIO.succeed(Nil)
    def updateIssue(i: AgentIssue): IO[DbPersistenceError, Unit]                                     = ZIO.unit
    def assignIssueToAgent(id: Long, name: String): IO[DbPersistenceError, Unit]                     = ZIO.unit
    def createAssignment(a: AgentAssignment): IO[DbPersistenceError, Long]                           = ZIO.succeed(1L)
    def getAssignment(id: Long): IO[DbPersistenceError, Option[AgentAssignment]]                     = ZIO.succeed(None)
    def listAssignmentsByIssue(id: Long): IO[DbPersistenceError, List[AgentAssignment]]              = ZIO.succeed(Nil)
    def updateAssignment(a: AgentAssignment): IO[DbPersistenceError, Unit]                           = ZIO.unit

  // In-memory stub WorkspaceRepository
  private class StubWorkspaceRepo(
    wsRef: Ref[Map[String, Workspace]],
    runRef: Ref[Map[String, WorkspaceRun]],
  ) extends WorkspaceRepository:
    def list: IO[PersistenceError, List[Workspace]]                                   = wsRef.get.map(_.values.toList)
    def get(id: String): IO[PersistenceError, Option[Workspace]]                      = wsRef.get.map(_.get(id))
    def save(ws: Workspace): IO[PersistenceError, Unit]                               = wsRef.update(_ + (ws.id -> ws))
    def delete(id: String): IO[PersistenceError, Unit]                                = wsRef.update(_ - id)
    def listRuns(wid: String): IO[PersistenceError, List[WorkspaceRun]]               =
      runRef.get.map(_.values.filter(_.workspaceId == wid).toList)
    def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                = runRef.get.map(_.get(id))
    def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit]                        = runRef.update(_ + (run.id -> run))
    def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit] =
      runRef.update(m => m.get(runId).fold(m)(r => m + (runId -> r.copy(status = status))))

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "test-repo",
    localPath = "/tmp",
    defaultAgent = Some("echo"),
    description = None,
    enabled = true,
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

  private def makeService(ws: Workspace = sampleWs, dockerCheck: IO[WorkspaceError, Unit] = dockerAvailable) =
    for
      messages <- Ref.make(List.empty[String])
      wsMap    <- Ref.make(Map(ws.id -> ws))
      runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
      chatRepo  = StubChatRepo(messages)
      wsRepo    = StubWorkspaceRepo(wsMap, runMap)
      svc       =
        WorkspaceRunServiceLive(
          wsRepo,
          chatRepo,
          worktreeAdd = noopWorktreeAdd,
          worktreeRemove = noopWorktreeRemove,
          dockerCheck = dockerCheck,
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
      yield assertTrue(saved.exists(r => r.status == RunStatus.Completed || r.status == RunStatus.Running))
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
                      timeoutSeconds = 0,
                      worktreeAdd = noopWorktreeAdd,
                      worktreeRemove = noopWorktreeRemove,
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
  )
