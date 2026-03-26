package mcp

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import agent.entity.AgentRepository
import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import decision.control.DecisionInbox
import decision.entity.{ Decision, DecisionFilter, DecisionResolutionKind }
import evolution.control.{ EvolutionEngine, EvolutionProposalRequest }
import evolution.entity.{
  EvolutionAuditRecord,
  EvolutionProposal,
  EvolutionProposalEvent,
  EvolutionProposalFilter,
  EvolutionProposalStatus,
}
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import knowledge.control.{ ArchitecturalContext, KnowledgeDecisionMatch, KnowledgeEdge, KnowledgeGraphService }
import knowledge.entity.{ DecisionLog, DecisionMaker, DecisionMakerKind }
import llm4zio.tools.ToolRegistry
import memory.entity.MemoryRepository
import shared.errors.PersistenceError
import shared.ids.Ids.*
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.{ WorkspaceError, WorkspaceRepository, WorkspaceRun }

object GatewayMcpToolsSpec extends ZIOSpecDefault:

  // ── Stubs ─────────────────────────────────────────────────────────────────

  private val stubIssueRepo: IssueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  private val stubAgentRepo: AgentRepository = new AgentRepository:
    import agent.entity.*
    private val testAgent                                                         = Agent(
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
    private val testWorkspace                                                                   = Workspace(
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
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(List(testWorkspace))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       = list.map(_.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  private val stubRunService: WorkspaceRunService = new WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(workspaceId))
    override def continueRun(runId: String, followUpPrompt: String, agentNameOverride: Option[String])
      : IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(runId))
    override def cancelRun(runId: String): IO[WorkspaceError, Unit]                                   =
      ZIO.fail(WorkspaceError.NotFound(runId))

  private val stubMemoryRepo: MemoryRepository = new MemoryRepository:
    import memory.entity.*
    override def save(entry: MemoryEntry): IO[Throwable, Unit]                               = ZIO.unit
    override def searchRelevant(userId: UserId, query: String, limit: Int, filter: MemoryFilter)
      : IO[Throwable, List[ScoredMemory]] =
      ZIO.succeed(Nil)
    override def listForUser(userId: UserId, filter: MemoryFilter, page: Int, pageSize: Int)
      : IO[Throwable, List[MemoryEntry]] =
      ZIO.succeed(Nil)
    override def deleteById(userId: UserId, id: memory.entity.MemoryId): IO[Throwable, Unit] = ZIO.unit
    override def deleteBySession(sessionId: memory.entity.SessionId): IO[Throwable, Unit]    = ZIO.unit

  private val stubDecisionInbox: DecisionInbox = new DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision]              = ZIO.fail(
      PersistenceError.QueryFailed("decision", "not implemented in test")
    )
    override def resolve(
      id: shared.ids.Ids.DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision] = ZIO.fail(PersistenceError.QueryFailed("decision", "not implemented in test"))
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def escalate(id: shared.ids.Ids.DecisionId, reason: String): IO[PersistenceError, Decision] = ZIO.fail(
      PersistenceError.QueryFailed("decision", "not implemented in test")
    )
    override def get(id: shared.ids.Ids.DecisionId): IO[PersistenceError, Decision]                      =
      ZIO.fail(PersistenceError.NotFound("decision", id.value))
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]                      = ZIO.succeed(Nil)
    override def runMaintenance(now: java.time.Instant): IO[PersistenceError, List[Decision]]            = ZIO.succeed(Nil)

  private val stubEvolutionEngine: EvolutionEngine = new EvolutionEngine:
    private val seedProposal = EvolutionProposal(
      id = EvolutionProposalId("proposal-1"),
      projectId = ProjectId("project-1"),
      title = "Add daemon",
      rationale = "Need more automation",
      target = evolution.entity.EvolutionTarget.WorkflowDefinitionTarget(
        projectId = ProjectId("project-1"),
        workflow = _root_.config.entity.WorkflowDefinition(name = "test", steps = List("chat"), isBuiltin = false),
      ),
      template = None,
      proposer = EvolutionAuditRecord("mcp", "seed", java.time.Instant.EPOCH),
      status = EvolutionProposalStatus.Proposed,
      decisionId = Some(DecisionId("decision-1")),
      createdAt = java.time.Instant.EPOCH,
      updatedAt = java.time.Instant.EPOCH,
    )

    override def propose(request: EvolutionProposalRequest): IO[evolution.control.EvolutionError, EvolutionProposal] =
      ZIO.succeed(seedProposal.copy(title = request.title))
    override def approve(
      proposalId: EvolutionProposalId,
      actor: String,
      summary: String,
    ): IO[evolution.control.EvolutionError, EvolutionProposal] =
      ZIO.succeed(seedProposal.copy(status = EvolutionProposalStatus.Approved))
    override def apply(
      proposalId: EvolutionProposalId,
      actor: String,
      summary: String,
    ): IO[evolution.control.EvolutionError, EvolutionProposal] =
      ZIO.succeed(seedProposal.copy(status = EvolutionProposalStatus.Applied))
    override def rollback(
      proposalId: EvolutionProposalId,
      actor: String,
      summary: String,
    ): IO[evolution.control.EvolutionError, EvolutionProposal] =
      ZIO.succeed(seedProposal.copy(status = EvolutionProposalStatus.RolledBack))
    override def get(proposalId: EvolutionProposalId): IO[evolution.control.EvolutionError, EvolutionProposal]       =
      ZIO.succeed(seedProposal)
    override def list(
      filter: EvolutionProposalFilter
    ): IO[evolution.control.EvolutionError, List[EvolutionProposal]] = ZIO.succeed(List(seedProposal))
    override def history(
      proposalId: EvolutionProposalId
    ): IO[evolution.control.EvolutionError, List[EvolutionProposalEvent]] =
      ZIO.succeed(
        List(
          EvolutionProposalEvent.Proposed(
            proposalId = seedProposal.id,
            projectId = seedProposal.projectId,
            title = seedProposal.title,
            rationale = seedProposal.rationale,
            target = seedProposal.target,
            template = None,
            proposedBy = "mcp",
            summary = "created",
            decisionId = seedProposal.decisionId,
            occurredAt = java.time.Instant.EPOCH,
          )
        )
      )

  private val stubAnalysisRepo: AnalysisRepository = new AnalysisRepository:
    private val docs                                                                             = List(
      AnalysisDoc(
        id = AnalysisDocId("analysis-architecture"),
        workspaceId = "ws1",
        analysisType = AnalysisType.Architecture,
        content = "# Executive Summary\n\nSystem boundaries are clear.\n\n## Risks\n\nModerate coupling remains.",
        filePath = ".llm4zio/analysis/architecture.md",
        generatedBy = AgentId("architect"),
        createdAt = java.time.Instant.parse("2026-03-13T10:00:00Z"),
        updatedAt = java.time.Instant.parse("2026-03-13T10:05:00Z"),
      ),
      AnalysisDoc(
        id = AnalysisDocId("analysis-security"),
        workspaceId = "ws1",
        analysisType = AnalysisType.Security,
        content = "Threat model reviewed.\n\nSecrets handling looks acceptable.",
        filePath = ".llm4zio/analysis/security.md",
        generatedBy = AgentId("security"),
        createdAt = java.time.Instant.parse("2026-03-13T11:00:00Z"),
        updatedAt = java.time.Instant.parse("2026-03-13T11:10:00Z"),
      ),
    )
    override def append(event: analysis.entity.AnalysisEvent): IO[PersistenceError, Unit]        = ZIO.unit
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       =
      ZIO.fromOption(docs.find(_.id == id)).orElseFail(PersistenceError.NotFound("analysis_doc", id.value))
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   =
      ZIO.succeed(docs.filter(_.workspaceId == workspaceId))
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
      ZIO.succeed(docs.filter(_.analysisType == analysisType))

  private val stubDecisionLog = DecisionLog(
    id = DecisionLogId("decision-log-1"),
    title = "Use derived knowledge graph edges",
    context = "Need decision retrieval without extra infrastructure",
    decisionTaken = "Derive edges from structured ids and semantic memory links",
    rationale = "Enough for MCP and web retrieval",
    consequences = List("Graph stays read-only"),
    decisionDate = java.time.Instant.parse("2026-03-26T09:00:00Z"),
    decisionMaker = DecisionMaker(DecisionMakerKind.Agent, "architect"),
    workspaceId = Some("ws1"),
    issueIds = List(IssueId("issue-1")),
    createdAt = java.time.Instant.parse("2026-03-26T09:00:00Z"),
    updatedAt = java.time.Instant.parse("2026-03-26T09:05:00Z"),
  )

  private val stubKnowledgeGraph: KnowledgeGraphService = new KnowledgeGraphService:
    override def searchDecisions(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, List[KnowledgeDecisionMatch]] =
      ZIO.succeed(List(KnowledgeDecisionMatch(stubDecisionLog, 0.91)))

    override def getArchitecturalContext(
      query: String,
      workspaceId: Option[String],
      limit: Int,
    ): IO[PersistenceError, ArchitecturalContext] =
      ZIO.succeed(
        ArchitecturalContext(
          decisions = List(KnowledgeDecisionMatch(stubDecisionLog, 0.91)),
          knowledgeEntries = Nil,
          analysisDocs = Nil,
          edges = List(KnowledgeEdge("decision-log-1", "mem-1", "semantic_decision", 0.8, explicit = false)),
        )
      )

  private def tools =
    GatewayMcpTools(
      stubIssueRepo,
      stubAgentRepo,
      stubWorkspaceRepo,
      stubRunService,
      stubDecisionInbox,
      stubEvolutionEngine,
      stubMemoryRepo,
      stubAnalysisRepo,
      stubKnowledgeGraph,
    )

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GatewayMcpTools")(
    suite("tool registration")(
      test("registers knowledge retrieval tools with the gateway set") {
        for
          registry <- ToolRegistry.make
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
          names.contains("get_analysis_docs"),
          names.contains("get_analysis_summary"),
          names.contains("propose_evolution"),
          names.contains("list_proposals"),
          names.contains("get_evolution_history"),
          names.contains("search_decisions"),
          names.contains("get_architectural_context"),
        )
      }
    ),
    suite("list_agents")(
      test("returns registered agents as JSON array") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "1", name = "list_agents", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("test-agent"))
      }
    ),
    suite("list_workspaces")(
      test("returns workspaces as JSON array") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "2", name = "list_workspaces", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("main-repo"))
      }
    ),
    suite("assign_issue")(
      test("creates issue event and returns issue id") {
        for
          appendedRef  <- Ref.make(Option.empty[IssueEvent])
          capturingRepo = new IssueRepository:
                            override def append(event: IssueEvent): IO[PersistenceError, Unit]             =
                              appendedRef.set(Some(event))
                            override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
                              ZIO.fail(PersistenceError.NotFound("issue", id.value))
                            override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      =
                              ZIO.succeed(Nil)
                            override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
                              ZIO.succeed(Nil)
                            override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit
          registry     <- ToolRegistry.make
          tools         = GatewayMcpTools(
                            capturingRepo,
                            stubAgentRepo,
                            stubWorkspaceRepo,
                            stubRunService,
                            stubDecisionInbox,
                            stubEvolutionEngine,
                            stubMemoryRepo,
                            stubAnalysisRepo,
                            stubKnowledgeGraph,
                          )
          _            <- registry.registerAll(tools.all)
          args          = Json.Obj(
                            "title"       -> Json.Str("Fix bug"),
                            "description" -> Json.Str("Reproduce and fix the crash"),
                            "priority"    -> Json.Str("high"),
                          )
          result       <- registry.execute(llm4zio.core.ToolCall(id = "3", name = "assign_issue", arguments = args.toJson))
          json          = result.result.toOption.get
          appended     <- appendedRef.get
        yield assertTrue(
          appended.isDefined,
          appended.get.isInstanceOf[IssueEvent.Created],
          json.toJson.contains("issueId"),
        )
      }
    ),
    suite("get_run_status")(
      test("returns not_found when run does not exist") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("runId" -> Json.Str("unknown-run"))
          result   <- registry.execute(llm4zio.core.ToolCall(id = "4", name = "get_run_status", arguments = args.toJson))
          json      = result.result.toOption.get
        yield assertTrue(json.toJson.contains("not_found"))
      }
    ),
    suite("search_conversations")(
      test("returns empty results from stub memory repository") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("query" -> Json.Str("test query"))
          result   <-
            registry.execute(llm4zio.core.ToolCall(id = "5", name = "search_conversations", arguments = args.toJson))
          json      = result.result.toOption.get
        yield assertTrue(json.isInstanceOf[Json.Arr])
      }
    ),
    suite("get_metrics")(
      test("returns gateway metrics JSON") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "6", name = "get_metrics", arguments = "{}"))
          json      = result.result.toOption.get
        yield assertTrue(
          json.toJson.contains("agents"),
          json.toJson.contains("workspaces"),
        )
      }
    ),
    suite("get_analysis_docs")(
      test("returns workspace analysis docs and supports type filtering") {
        for
          registry  <- ToolRegistry.make
          _         <- registry.registerAll(tools.all)
          allArgs    = Json.Obj("workspaceId" -> Json.Str("ws1"))
          allResult <-
            registry.execute(llm4zio.core.ToolCall(id = "7", name = "get_analysis_docs", arguments = allArgs.toJson))
          filterArgs = Json.Obj("workspaceId" -> Json.Str("ws1"), "analysisType" -> Json.Str("security"))
          oneResult <-
            registry.execute(llm4zio.core.ToolCall(id = "8", name = "get_analysis_docs", arguments = filterArgs.toJson))
          allJson    = allResult.result.toOption.get.toJson
          oneJson    = oneResult.result.toOption.get.toJson
        yield assertTrue(
          allJson.contains("Architecture"),
          allJson.contains("Security"),
          allJson.contains(".llm4zio/analysis/architecture.md"),
          oneJson.contains("Security"),
          !oneJson.contains("Architecture"),
        )
      }
    ),
    suite("get_analysis_summary")(
      test("returns condensed summary from executive summary and first paragraph") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("workspaceId" -> Json.Str("ws1"))
          result   <-
            registry.execute(llm4zio.core.ToolCall(id = "9", name = "get_analysis_summary", arguments = args.toJson))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Architecture: System boundaries are clear."),
          json.contains("Security: Threat model reviewed."),
          json.contains("\"documents\":2"),
        )
      }
    ),
    suite("evolution tools")(
      test("list_proposals returns proposal rows") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "10", name = "list_proposals", arguments = "{}"))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("proposal-1"),
          json.contains("Add daemon"),
        )
      },
      test("get_evolution_history returns recorded events") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("proposalId" -> Json.Str("proposal-1"))
          result   <- registry.execute(
                        llm4zio.core.ToolCall(id = "11", name = "get_evolution_history", arguments = args.toJson)
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Proposed"),
          json.contains("proposal-1"),
        )
      },
    ),
    suite("knowledge tools")(
      test("search_decisions returns structured decision matches") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("query" -> Json.Str("knowledge graph"))
          result   <-
            registry.execute(llm4zio.core.ToolCall(id = "12", name = "search_decisions", arguments = args.toJson))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("decision-log-1"),
          json.contains("Use derived knowledge graph edges"),
          json.contains("Derive edges from structured ids and semantic memory links"),
        )
      },
      test("get_architectural_context returns graph edges") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("query" -> Json.Str("architecture"))
          result   <- registry.execute(
                        llm4zio.core.ToolCall(id = "13", name = "get_architectural_context", arguments = args.toJson)
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("semantic_decision"),
          json.contains("decision-log-1"),
        )
      },
    ),
  )
