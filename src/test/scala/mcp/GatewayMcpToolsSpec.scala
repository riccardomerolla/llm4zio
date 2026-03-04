package mcp

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import agent.entity.AgentRepository
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import llm4zio.tools.ToolRegistry
import memory.entity.MemoryRepository
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, IssueId }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.{ WorkspaceError, WorkspaceRepository, WorkspaceRun }

object GatewayMcpToolsSpec extends ZIOSpecDefault:

  // ── Stubs ─────────────────────────────────────────────────────────────────

  private val stubIssueRepo: IssueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]   =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]      = ZIO.unit

  private val stubAgentRepo: AgentRepository = new AgentRepository:
    import agent.entity.*
    private val testAgent = Agent(
      id = AgentId("a1"),
      name = "test-agent",
      description = "A test agent",
      cliTool = "claude-code",
      capabilities = List("scala"),
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = 2,
      envVars = Map.empty,
      timeout = java.time.Duration.ofMinutes(30),
      enabled = true,
      createdAt = java.time.Instant.EPOCH,
      updatedAt = java.time.Instant.EPOCH,
    )
    override def append(event: AgentEvent): IO[PersistenceError, Unit]            = ZIO.unit
    override def get(id: AgentId): IO[PersistenceError, Agent]                    =
      ZIO.fail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(List(testAgent))
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]    =
      list().map(_.find(_.name == name))

  private val stubWorkspaceRepo: WorkspaceRepository = new WorkspaceRepository:
    import workspace.entity.*
    private val testWorkspace = Workspace(
      id = "ws1",
      name = "main-repo",
      localPath = "/repos/main",
      defaultAgent = None,
      description = None,
      enabled = true,
      runMode = RunMode.Host,
      cliTool = "claude-code",
      createdAt = java.time.Instant.EPOCH,
      updatedAt = java.time.Instant.EPOCH,
    )
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]               = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                             = ZIO.succeed(List(testWorkspace))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                = list.map(_.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                          = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]         = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]          = ZIO.succeed(None)

  private val stubRunService: WorkspaceRunService = new WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(workspaceId))
    override def continueRun(runId: String, followUpPrompt: String, agentNameOverride: Option[String]): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(runId))
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
      ZIO.fail(WorkspaceError.NotFound(runId))

  private val stubMemoryRepo: MemoryRepository = new MemoryRepository:
    import memory.entity.*
    override def save(entry: MemoryEntry): IO[Throwable, Unit] = ZIO.unit
    override def searchRelevant(userId: UserId, query: String, limit: Int, filter: MemoryFilter): IO[Throwable, List[ScoredMemory]] =
      ZIO.succeed(Nil)
    override def listForUser(userId: UserId, filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]] =
      ZIO.succeed(Nil)
    override def deleteById(userId: UserId, id: memory.entity.MemoryId): IO[Throwable, Unit] = ZIO.unit
    override def deleteBySession(sessionId: memory.entity.SessionId): IO[Throwable, Unit] = ZIO.unit

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec = suite("GatewayMcpTools")(
    suite("tool registration")(
      test("registers all 7 gateway tools") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          listed   <- registry.list
          names     = listed.map(_.name).toSet
        yield assertTrue(
          names.contains("assign_issue"),
          names.contains("run_agent"),
          names.contains("get_run_status"),
          names.contains("list_agents"),
          names.contains("list_workspaces"),
          names.contains("search_conversations"),
          names.contains("get_metrics"),
        )
      },
    ),
    suite("list_agents")(
      test("returns registered agents as JSON array") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "1", name = "list_agents", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("test-agent"))
      },
    ),
    suite("list_workspaces")(
      test("returns workspaces as JSON array") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "2", name = "list_workspaces", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("main-repo"))
      },
    ),
    suite("assign_issue")(
      test("creates issue event and returns issue id") {
        var appended: Option[IssueEvent] = None
        val capturingRepo                = new IssueRepository:
          override def append(event: IssueEvent): IO[PersistenceError, Unit] =
            ZIO.succeed { appended = Some(event) }
          override def get(id: IssueId): IO[PersistenceError, AgentIssue]   =
            ZIO.fail(PersistenceError.NotFound("issue", id.value))
          override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
          override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(capturingRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj(
                        "title"       -> Json.Str("Fix bug"),
                        "description" -> Json.Str("Reproduce and fix the crash"),
                        "priority"    -> Json.Str("high"),
                      )
          result   <- registry.execute(llm4zio.core.ToolCall(id = "3", name = "assign_issue", arguments = args.toJson))
          json      = result.result.toOption.get
        yield assertTrue(
          appended.isDefined,
          appended.get.isInstanceOf[IssueEvent.Created],
          json.toJson.contains("issueId"),
        )
      },
    ),
    suite("get_run_status")(
      test("returns not_found when run does not exist") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("runId" -> Json.Str("unknown-run"))
          result   <- registry.execute(llm4zio.core.ToolCall(id = "4", name = "get_run_status", arguments = args.toJson))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("not_found"))
      },
    ),
    suite("search_conversations")(
      test("returns empty results from stub memory repository") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("query" -> Json.Str("test query"))
          result   <- registry.execute(llm4zio.core.ToolCall(id = "5", name = "search_conversations", arguments = args.toJson))
          json      = result.result.toOption.get
        yield assertTrue(json.isInstanceOf[Json.Arr])
      },
    ),
    suite("get_metrics")(
      test("returns gateway metrics JSON") {
        for
          registry <- ToolRegistry.make
          tools     = GatewayMcpTools(stubIssueRepo, stubAgentRepo, stubWorkspaceRepo, stubRunService, stubMemoryRepo)
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "6", name = "get_metrics", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(
          json.toJson.contains("agents"),
          json.toJson.contains("workspaces"),
        )
      },
    ),
  )
