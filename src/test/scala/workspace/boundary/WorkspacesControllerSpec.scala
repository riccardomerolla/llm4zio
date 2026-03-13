package workspace.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import _root_.config.entity.*
import analysis.control.{ WorkspaceAnalysisScheduler, WorkspaceAnalysisState, WorkspaceAnalysisStatus }
import analysis.entity.AnalysisType
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import orchestration.control.AgentRegistry
import shared.ids.Ids.IssueId
import taskrun.entity.TaskStep
import workspace.control.{ AssignRunRequest, GitService, WorkspaceRunService }
import workspace.entity.*

object WorkspacesControllerSpec extends ZIOSpecDefault:

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("code-agent"),
    description = None,
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "claude",
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private val sampleRun = WorkspaceRun(
    id = "run-1",
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = "#1",
    agentName = "code-agent",
    prompt = "do work",
    conversationId = "42",
    worktreePath = sys.props("user.dir"),
    branchName = "agent/1-run",
    status = RunStatus.Running(RunSessionMode.Autonomous),
    attachedUsers = Set.empty,
    controllerUserId = None,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private class StubWorkspaceRepo(ref: Ref[Map[String, Workspace]], runRef: Ref[Map[String, WorkspaceRun]])
    extends WorkspaceRepository:
    def append(event: WorkspaceEvent): IO[shared.errors.PersistenceError, Unit]                      =
      event match
        case e: WorkspaceEvent.Created =>
          ref.update(_ + (e.workspaceId -> Workspace(
            e.workspaceId,
            e.name,
            e.localPath,
            e.defaultAgent,
            e.description,
            true,
            e.runMode,
            e.cliTool,
            e.occurredAt,
            e.occurredAt,
          )))
        case e: WorkspaceEvent.Deleted => ref.update(_ - e.workspaceId)
        case _                         => ZIO.unit
    def list: IO[shared.errors.PersistenceError, List[Workspace]]                                    = ref.get.map(_.values.toList)
    def get(id: String): IO[shared.errors.PersistenceError, Option[Workspace]]                       = ref.get.map(_.get(id))
    def delete(id: String): IO[shared.errors.PersistenceError, Unit]                                 = ref.update(_ - id)
    def appendRun(event: WorkspaceRunEvent): IO[shared.errors.PersistenceError, Unit]                = ZIO.unit
    def listRuns(wid: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]]                = runRef.get.map(_.values.toList)
    def listRunsByIssueRef(issueRef: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]] =
      runRef.get.map(_.values.toList.filter(_.issueRef == issueRef))
    def getRun(id: String): IO[shared.errors.PersistenceError, Option[WorkspaceRun]]                 = runRef.get.map(_.get(id))

  private class StubRunService extends WorkspaceRunService:
    def assign(wid: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(wid))
    def continueRun(
      runId: String,
      followUp: String,
      agentNameOverride: Option[String],
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(runId))
    def cancelRun(runId: String): IO[WorkspaceError, Unit]                           = ZIO.unit

  private object StubAgentRegistry extends AgentRegistry:
    def registerAgent(r: RegisterAgentRequest): UIO[AgentInfo]                  =
      ZIO.succeed(AgentInfo(r.name, r.name, r.displayName, r.description, r.agentType, r.usesAI, r.tags))
    def findByName(name: String): UIO[Option[AgentInfo]]                        = ZIO.succeed(None)
    def findAgents(q: AgentQuery): UIO[List[AgentInfo]]                         = ZIO.succeed(Nil)
    def getAllAgents: UIO[List[AgentInfo]]                                      = ZIO.succeed(AgentRegistry.builtInAgents)
    def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]                = ZIO.succeed(Nil)
    def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]                 = ZIO.succeed(Nil)
    def findAgentsForTransformation(i: String, o: String): UIO[List[AgentInfo]] = ZIO.succeed(Nil)
    def recordInvocation(name: String, ok: Boolean, ms: Long): UIO[Unit]        = ZIO.unit
    def updateHealth(name: String, ok: Boolean, msg: Option[String]): UIO[Unit] = ZIO.unit
    def setAgentEnabled(name: String, enabled: Boolean): UIO[Unit]              = ZIO.unit
    def getMetrics(name: String): UIO[Option[AgentMetrics]]                     = ZIO.succeed(None)
    def getHealth(name: String): UIO[Option[AgentHealth]]                       = ZIO.succeed(None)
    def loadCustomAgents(rows: List[db.CustomAgentRow]): UIO[Int]               = ZIO.succeed(0)
    def getRankedAgents(q: AgentQuery): UIO[List[AgentInfo]]                    = ZIO.succeed(Nil)

  private object StubIssueRepository extends IssueRepository:
    def append(event: IssueEvent): IO[shared.errors.PersistenceError, Unit]             = ZIO.unit
    def get(id: IssueId): IO[shared.errors.PersistenceError, AgentIssue]                =
      ZIO.fail(shared.errors.PersistenceError.NotFound("issue", id.value))
    def history(id: IssueId): IO[shared.errors.PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    def list(filter: IssueFilter): IO[shared.errors.PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    def delete(id: IssueId): IO[shared.errors.PersistenceError, Unit]                   = ZIO.unit

  private object StubGitService extends GitService:
    def status(repoPath: String): IO[GitError, GitStatus]                                   =
      ZIO.succeed(
        GitStatus(
          branch = "feature/test",
          staged = List(FileChange("A.scala", ChangeStatus.Modified)),
          unstaged = List(FileChange("B.scala", ChangeStatus.Added)),
          untracked = List("README.md"),
        )
      )
    def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                      =
      ZIO.succeed(GitDiff(Nil))
    def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]              =
      ZIO.succeed(GitDiffStat(List(DiffFileStat("A.scala", 5, 2))))
    def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String] =
      ZIO.succeed(s"diff --git a/$filePath b/$filePath")
    def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                  =
      ZIO.succeed(
        List(
          GitLogEntry(
            hash = "abc123",
            shortHash = "abc123",
            author = "riccardo",
            message = "feat: test",
            date = Instant.parse("2026-03-02T08:00:00Z"),
          )
        )
      )
    def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                           =
      ZIO.succeed(GitBranchInfo("feature/test", List("main", "feature/test"), isDetached = false))
    def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]     =
      ZIO.succeed("file-content")
    def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]        =
      ZIO.succeed(AheadBehind(ahead = 3, behind = 1))

  final private class StubAnalysisScheduler(triggerRef: Ref[List[(String, Boolean)]])
    extends WorkspaceAnalysisScheduler:
    override def triggerForWorkspaceEvent(workspaceId: String): UIO[Unit] =
      triggerRef.update(_ :+ (workspaceId -> false))

    override def triggerManual(workspaceId: String): UIO[Unit] =
      triggerRef.update(_ :+ (workspaceId -> true))

    override def statusForWorkspace(workspaceId: String)
      : IO[shared.errors.PersistenceError, List[WorkspaceAnalysisStatus]] =
      ZIO.succeed(
        List(
          WorkspaceAnalysisStatus(
            workspaceId = workspaceId,
            analysisType = AnalysisType.CodeReview,
            state = WorkspaceAnalysisState.Completed,
            completedAt = Some(Instant.parse("2026-02-24T10:15:00Z")),
            lastUpdatedAt = Instant.parse("2026-02-24T10:15:00Z"),
          )
        )
      )

  private def makeRoutes(
    wsRef: Ref[Map[String, Workspace]],
    runRef: Ref[Map[String, WorkspaceRun]],
    triggerRef: Ref[List[(String, Boolean)]],
  ) =
    WorkspacesController.routes(
      StubWorkspaceRepo(wsRef, runRef),
      StubRunService(),
      StubAgentRegistry,
      StubIssueRepository,
      StubGitService,
      StubAnalysisScheduler(triggerRef),
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspacesControllerSpec")(
    test("GET /settings/workspaces returns 200") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/settings/workspaces")))
        resp       <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /api/workspaces returns JSON list") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces")))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("my-api"))
    },
    test("DELETE /api/workspaces/:id returns 204") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request(method = Method.DELETE, url = URL(Path.decode("/api/workspaces/ws-1")))
        resp       <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /api/workspaces/:id/runs returns 200") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs")))
        resp       <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /runs redirects to command center") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/runs")))
        resp       <- routes.runZIO(req)
        location    = resp.headers.header(Header.Location).map(_.renderedValue)
      yield assertTrue(resp.status == Status.MovedPermanently && location.contains("/"))
    },
    test("GET /api/runs filters by status") {
      val completedRun = sampleRun.copy(id = "run-2", status = RunStatus.Completed)
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun, "run-2" -> completedRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL.decode("/api/runs?status=completed").getOrElse(URL.root))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("run-2") && !body.contains("run-1"))
    },
    test("WorkspaceCreateRequest with default RunMode.Host round-trips through JSON") {
      val req     = WorkspaceCreateRequest(
        name = "my-api",
        localPath = "/tmp/my-api",
        defaultAgent = Some("code-agent"),
        description = None,
      )
      val decoded = req.toJson.fromJson[WorkspaceCreateRequest]
      assertTrue(decoded == Right(req) && decoded.exists(_.runMode == RunMode.Host))
    },
    test("WorkspaceCreateRequest with RunMode.Docker round-trips through JSON") {
      val req     = WorkspaceCreateRequest(
        name = "sandboxed",
        localPath = "/tmp/sandboxed",
        defaultAgent = Some("code-agent"),
        description = None,
        runMode = RunMode.Docker(image = "opencode:latest", network = Some("none")),
      )
      val decoded = req.toJson.fromJson[WorkspaceCreateRequest]
      assertTrue(decoded == Right(req))
    },
    test("GET git status endpoint returns GitStatus JSON") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs/run-1/git/status")))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("feature/test"))
    },
    test("GET git diff endpoint supports staged query") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL.decode("/api/workspaces/ws-1/runs/run-1/git/diff?staged=true").getOrElse(URL.root))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("A.scala"))
    },
    test("GET git file diff endpoint returns text/plain") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs/run-1/git/diff/src%2Fmain%2Fscala%2FA.scala")))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("diff --git"))
    },
    test("GET git log endpoint returns entries") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL.decode("/api/workspaces/ws-1/runs/run-1/git/log?limit=10").getOrElse(URL.root))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("feat: test"))
    },
    test("GET git branch endpoint returns branch and ahead/behind") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs/run-1/git/branch")))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("\"ahead\":3") && body.contains("\"behind\":1"))
    },
    test("POST apply endpoint rejects active runs") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.post(URL(Path.decode("/api/workspaces/ws-1/runs/run-1/apply")), Body.empty)
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Conflict && body.contains("Run is still active"))
    },
    test("GET git file diff endpoint rejects invalid file path") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs/run-1/git/diff/..%2Fsecret")))
        resp       <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.BadRequest)
    },
    test("GET /settings/workspaces/:id returns detail page") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.get(URL(Path.decode("/settings/workspaces/ws-1")))
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("Analysis Status") && body.contains("Re-analyze"))
    },
    test("POST /api/workspaces/:id/reanalyze triggers scheduler") {
      for
        wsRef      <- Ref.make(Map("ws-1" -> sampleWs))
        runRef     <- Ref.make(Map("run-1" -> sampleRun))
        triggerRef <- Ref.make(List.empty[(String, Boolean)])
        routes      = makeRoutes(wsRef, runRef, triggerRef)
        req         = Request.post(URL(Path.decode("/api/workspaces/ws-1/reanalyze")), Body.empty)
        resp       <- routes.runZIO(req)
        body       <- resp.body.asString
        _          <- Live.live(ZIO.sleep(50.millis))
        triggers   <- triggerRef.get
      yield assertTrue(
        resp.status == Status.Ok && body.contains("Analysis Status") && triggers.contains("ws-1" -> true)
      )
    } @@ TestAspect.withLiveClock,
  )
