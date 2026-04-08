package mcp

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import agent.entity.AgentRepository
import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import daemon.control.DaemonAgentScheduler
import daemon.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import evolution.control.{ EvolutionEngine, EvolutionProposalRequest }
import evolution.entity.{
  EvolutionAuditRecord,
  EvolutionProposal,
  EvolutionProposalEvent,
  EvolutionProposalFilter,
  EvolutionProposalStatus,
}
import governance.control.{ GovernanceEvaluationContext, GovernancePolicyService, GovernanceTransitionDecision }
import governance.entity.*
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import knowledge.control.KnowledgeGraphService
import knowledge.entity.*
import llm4zio.tools.ToolRegistry
import memory.entity.MemoryRepository
import plan.entity.*
import sdlc.control.SdlcDashboardService
import sdlc.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import specification.entity.*
import workspace.control.WorkspaceRunService
import workspace.entity.{ AssignRunRequest, WorkspaceError, WorkspaceRepository, WorkspaceRun }

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
    private val testWorkspace                                                                              = Workspace(
      id = "ws1",
      projectId = shared.ids.Ids.ProjectId("test-project"),
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
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                               = ZIO.succeed(List(testWorkspace))
    override def listByProject(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(List(testWorkspace).filter(_.projectId == projectId))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                                  = list.map(_.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                            = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                           = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]                   = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]            =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                            = ZIO.succeed(None)

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

  private val stubGovernancePolicy = GovernancePolicy(
    id = GovernancePolicyId("gov-1"),
    projectId = ProjectId("project-1"),
    name = "Workspace Governance",
    version = 1,
    transitionRules = List(
      GovernanceTransitionRule(
        transition = GovernanceTransition(
          GovernanceLifecycleStage.Backlog,
          GovernanceLifecycleStage.Todo,
          GovernanceLifecycleAction.Dispatch,
        ),
        requiredGates = List(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
      )
    ),
    daemonTriggers = List(
      GovernanceDaemonTrigger(
        id = "daemon-trigger-1",
        transition = GovernanceTransition(
          GovernanceLifecycleStage.Backlog,
          GovernanceLifecycleStage.Todo,
          GovernanceLifecycleAction.Dispatch,
        ),
        agentName = "planner",
      )
    ),
    escalationRules = Nil,
    completionCriteria = Nil,
    isDefault = false,
    createdAt = java.time.Instant.EPOCH,
    updatedAt = java.time.Instant.EPOCH,
  )

  private val stubSpecification = Specification(
    id = SpecificationId("spec-1"),
    title = "Improve MCP coverage",
    content = "Expand gateway MCP tools across ADE domains.",
    status = SpecificationStatus.Approved,
    version = 1,
    revisions = List(
      SpecificationRevision(
        version = 1,
        title = "Improve MCP coverage",
        content = "Expand gateway MCP tools across ADE domains.",
        author = SpecificationAuthor(SpecificationAuthorKind.Agent, "architect", "Architect"),
        status = SpecificationStatus.Approved,
        changedAt = java.time.Instant.EPOCH,
      )
    ),
    linkedIssueIds = Nil,
    linkedPlanRef = Some("plan:plan-1"),
    author = SpecificationAuthor(SpecificationAuthorKind.Agent, "architect", "Architect"),
    reviewComments = Nil,
    createdAt = java.time.Instant.EPOCH,
    updatedAt = java.time.Instant.EPOCH,
  )

  private val stubPlan = Plan(
    id = PlanId("plan-1"),
    conversationId = 42L,
    workspaceId = Some("ws1"),
    specificationId = Some(stubSpecification.id),
    summary = "Add MCP tools",
    rationale = "Expose ADE capabilities over MCP",
    status = PlanStatus.Draft,
    version = 1,
    drafts = List(
      PlanTaskDraft(
        draftId = "draft-1",
        title = "Implement governance tools",
        description = "Add governance inspection and evaluation tools",
      )
    ),
    validation = None,
    linkedIssueIds = Nil,
    versions = List(
      PlanVersion(
        version = 1,
        summary = "Add MCP tools",
        rationale = "Expose ADE capabilities over MCP",
        drafts = List(
          PlanTaskDraft(
            draftId = "draft-1",
            title = "Implement governance tools",
            description = "Add governance inspection and evaluation tools",
          )
        ),
        validation = None,
        status = PlanStatus.Draft,
        changedAt = java.time.Instant.EPOCH,
      )
    ),
    createdAt = java.time.Instant.EPOCH,
    updatedAt = java.time.Instant.EPOCH,
  )

  private val stubDaemonStatus = DaemonAgentStatus(
    spec = DaemonAgentSpec(
      id = DaemonAgentSpecId("project-1__planner-daemon"),
      daemonKey = "planner-daemon",
      projectId = ProjectId("project-1"),
      name = "Planner Daemon",
      purpose = "Continuously improve planning coverage",
      trigger = DaemonTriggerCondition.EventDriven("governance:dispatch"),
      workspaceIds = List("ws1"),
      agentName = "planner",
      prompt = "Plan the next wave of MCP work",
      limits = DaemonExecutionLimits(),
      builtIn = false,
      governed = true,
    ),
    enabled = true,
    runtime = DaemonAgentRuntime(health = DaemonHealth.Healthy),
  )

  private val stubDashboardSnapshot = SdlcSnapshot(
    generatedAt = java.time.Instant.EPOCH,
    thresholds = Thresholds(6, 2, 24L, 12L, 8L, 4L),
    lifecycle = List(
      LifecycleStage(
        key = "plan",
        label = "Plan",
        count = 1,
        href = "/plans",
        description = "Plans waiting for execution",
      )
    ),
    churnAlerts = List(
      ChurnAlert(
        issueId = "issue-1",
        title = "Stabilize MCP tools",
        transitionCount = 7,
        bounceCount = 3,
        currentState = "rework",
        lastChangedAt = java.time.Instant.EPOCH,
      )
    ),
    stoppages = List(
      StoppageAlert(
        kind = "blocked",
        issueId = "issue-2",
        title = "Wire MCP dashboard tools",
        currentState = "human_review",
        ageHours = 13L,
        blockedBy = List("manual approval"),
      )
    ),
    escalations = List(
      EscalationIndicator(
        kind = "decision",
        referenceId = "decision-1",
        title = "Approve daemon rollout",
        urgency = "high",
        ageHours = 5L,
        summary = "Waiting on governance decision",
      )
    ),
    agentPerformance = List(
      AgentPerformance(
        agentName = "planner",
        throughput = 3,
        successRate = 0.95,
        averageCycleHours = 2.5,
        activeIssues = 1,
        costUsd = 4.2,
      )
    ),
    governance = GovernanceOverview(
      passCount = 4,
      failCount = 1,
      passRate = 0.8,
      activePolicyCount = 2,
    ),
    daemonHealth = DaemonHealthOverview(
      runningCount = 2,
      stoppedCount = 1,
      erroredCount = 0,
    ),
    evolution = EvolutionOverview(
      pendingProposalCount = 1,
      recentlyApplied = List(
        RecentEvolution(
          proposalId = "proposal-1",
          title = "Add daemon",
          status = "Applied",
          appliedAt = java.time.Instant.EPOCH,
        )
      ),
    ),
    recentActivity = Nil,
    specificationCount = 1,
    planCount = 1,
    issueCount = 2,
    pendingDecisionCount = 1,
    specificationTrend = TrendIndicator(
      direction = TrendDirection.Flat,
      currentPeriodCount = 1,
      previousPeriodCount = 1,
      periodLabel = "7d",
    ),
    planTrend = TrendIndicator(
      direction = TrendDirection.Up,
      currentPeriodCount = 2,
      previousPeriodCount = 1,
      periodLabel = "7d",
    ),
    issueTrend = TrendIndicator(
      direction = TrendDirection.Up,
      currentPeriodCount = 3,
      previousPeriodCount = 1,
      periodLabel = "7d",
    ),
    pendingDecisionTrend = TrendIndicator(
      direction = TrendDirection.Down,
      currentPeriodCount = 0,
      previousPeriodCount = 2,
      periodLabel = "7d",
    ),
  )

  private val stubGovernanceService: GovernancePolicyService = new GovernancePolicyService:
    override def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy] =
      ZIO.succeed(stubGovernancePolicy)

    override def evaluateForWorkspace(
      workspaceId: String,
      context: GovernanceEvaluationContext,
    ): IO[PersistenceError, GovernanceTransitionDecision] =
      ZIO.succeed(
        GovernanceTransitionDecision(
          allowed = context.satisfiedGates.contains(GovernanceGate.SpecReview) && context.satisfiedGates.contains(
            GovernanceGate.PlanningReview
          ),
          requiredGates = Set(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
          missingGates = Set(
            Option.when(!context.satisfiedGates.contains(GovernanceGate.SpecReview))(GovernanceGate.SpecReview),
            Option.when(!context.satisfiedGates.contains(GovernanceGate.PlanningReview))(GovernanceGate.PlanningReview),
          ).flatten,
          humanApprovalRequired = false,
          daemonTriggers = stubGovernancePolicy.daemonTriggers,
          escalationRules = Nil,
          completionCriteria = None,
          reason = None,
        )
      )

  private val stubSpecificationRepo: SpecificationRepository = new SpecificationRepository:
    override def append(event: SpecificationEvent): IO[PersistenceError, Unit]                                        = ZIO.unit
    override def get(id: SpecificationId): IO[PersistenceError, Specification]                                        =
      ZIO.fromOption(Option.when(id == stubSpecification.id)(stubSpecification))
        .orElseFail(PersistenceError.NotFound("specification", id.value))
    override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]]                         = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Specification]]                                                      = ZIO.succeed(List(stubSpecification))
    override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
      ZIO.succeed(
        SpecificationDiff(
          fromVersion = fromVersion,
          toVersion = toVersion,
          beforeContent = "before",
          afterContent = "after",
        )
      )

  private val stubPlanRepo: PlanRepository = new PlanRepository:
    override def append(event: PlanEvent): IO[PersistenceError, Unit]       = ZIO.unit
    override def get(id: PlanId): IO[PersistenceError, Plan]                =
      ZIO.fromOption(Option.when(id == stubPlan.id)(stubPlan)).orElseFail(PersistenceError.NotFound("plan", id.value))
    override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]] = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Plan]]                     = ZIO.succeed(List(stubPlan))

  private val stubDaemonScheduler: DaemonAgentScheduler = new DaemonAgentScheduler:
    override def list: IO[PersistenceError, List[DaemonAgentStatus]]                                    = ZIO.succeed(List(stubDaemonStatus))
    override def start(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                               = ZIO.unit
    override def stop(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                                = ZIO.unit
    override def restart(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ZIO.unit
    override def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): IO[PersistenceError, Unit]        = ZIO.unit
    override def trigger(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ZIO.unit
    override def triggerGovernance(projectId: ProjectId, triggerId: String): IO[PersistenceError, Unit] = ZIO.unit

  private val stubDashboardService: SdlcDashboardService = new SdlcDashboardService:
    override def snapshot: IO[PersistenceError, SdlcSnapshot] = ZIO.succeed(stubDashboardSnapshot)

  private def makeSpecificationRepository(
    initial: List[SpecificationEvent]
  ): UIO[SpecificationRepository] =
    Ref.make(initial).map { ref =>
      new SpecificationRepository:
        override def append(event: SpecificationEvent): IO[PersistenceError, Unit] =
          ref.update(_ :+ event).unit

        override def get(id: SpecificationId): IO[PersistenceError, Specification] =
          ref.get.flatMap { events =>
            val stream = events.filter(_.specificationId == id)
            ZIO
              .fromEither(Specification.fromEvents(stream))
              .mapError(error => PersistenceError.QueryFailed("specification", error))
          }

        override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]] =
          ref.get.map(_.filter(_.specificationId == id))

        override def list: IO[PersistenceError, List[Specification]] =
          ref.get.flatMap { events =>
            ZIO.foreach(events.groupBy(_.specificationId).values.toList)(stream =>
              ZIO
                .fromEither(Specification.fromEvents(stream))
                .mapError(error => PersistenceError.QueryFailed("specification", error))
            )
          }

        override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int)
          : IO[PersistenceError, SpecificationDiff] =
          get(id).flatMap(spec =>
            ZIO
              .fromEither(Specification.diff(spec, fromVersion, toVersion))
              .mapError(error => PersistenceError.QueryFailed("specification_diff", error))
          )
    }

  private def makePlanRepository(initial: List[PlanEvent]): UIO[PlanRepository] =
    Ref.make(initial).map { ref =>
      new PlanRepository:
        override def append(event: PlanEvent): IO[PersistenceError, Unit] =
          ref.update(_ :+ event).unit

        override def get(id: PlanId): IO[PersistenceError, Plan] =
          ref.get.flatMap { events =>
            val stream = events.filter(_.planId == id)
            ZIO
              .fromEither(Plan.fromEvents(stream))
              .mapError(error => PersistenceError.QueryFailed("plan", error))
          }

        override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]] =
          ref.get.map(_.filter(_.planId == id))

        override def list: IO[PersistenceError, List[Plan]] =
          ref.get.flatMap { events =>
            ZIO.foreach(events.groupBy(_.planId).values.toList)(stream =>
              ZIO
                .fromEither(Plan.fromEvents(stream))
                .mapError(error => PersistenceError.QueryFailed("plan", error))
            )
          }
    }

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
      stubGovernanceService,
      stubSpecificationRepo,
      stubPlanRepo,
      stubDaemonScheduler,
      stubDashboardService,
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
          names.contains("get_decision"),
          names.contains("escalate_decision"),
          names.contains("get_governance_policy"),
          names.contains("evaluate_governance_transition"),
          names.contains("list_specifications"),
          names.contains("get_specification"),
          names.contains("create_specification"),
          names.contains("revise_specification"),
          names.contains("approve_specification"),
          names.contains("get_specification_diff"),
          names.contains("list_plans"),
          names.contains("get_plan"),
          names.contains("create_plan"),
          names.contains("revise_plan"),
          names.contains("validate_plan"),
          names.contains("list_daemons"),
          names.contains("start_daemon"),
          names.contains("stop_daemon"),
          names.contains("restart_daemon"),
          names.contains("set_daemon_enabled"),
          names.contains("trigger_daemon"),
          names.contains("get_sdlc_dashboard"),
          names.contains("get_churn_alerts"),
          names.contains("get_stoppages"),
          names.contains("get_escalations"),
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
                            stubGovernanceService,
                            stubSpecificationRepo,
                            stubPlanRepo,
                            stubDaemonScheduler,
                            stubDashboardService,
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
    suite("governance tools")(
      test("get_governance_policy returns the effective workspace policy") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj("workspaceId" -> Json.Str("ws1"))
          result   <- registry.execute(
                        llm4zio.core.ToolCall(id = "6a", name = "get_governance_policy", arguments = args.toJson)
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Workspace Governance"),
          json.contains("daemon-trigger-1"),
        )
      },
      test("evaluate_governance_transition reports satisfied gates") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          args      = Json.Obj(
                        "workspaceId"    -> Json.Str("ws1"),
                        "issueType"      -> Json.Str("plan"),
                        "fromStage"      -> Json.Str("backlog"),
                        "toStage"        -> Json.Str("todo"),
                        "action"         -> Json.Str("dispatch"),
                        "satisfiedGates" -> Json.Arr(
                          Chunk(Json.Str("specReview"), Json.Str("planningReview"))
                        ),
                      )
          result   <- registry.execute(
                        llm4zio.core.ToolCall(
                          id = "6b",
                          name = "evaluate_governance_transition",
                          arguments = args.toJson,
                        )
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("\"allowed\":true"),
          json.contains("daemon-trigger-1"),
        )
      },
    ),
    suite("specification tools")(
      test("create_specification persists and returns the new draft") {
        for
          specRepo <- makeSpecificationRepository(Nil)
          registry <- ToolRegistry.make
          testTools = GatewayMcpTools(
                        stubIssueRepo,
                        stubAgentRepo,
                        stubWorkspaceRepo,
                        stubRunService,
                        stubDecisionInbox,
                        stubEvolutionEngine,
                        stubMemoryRepo,
                        stubAnalysisRepo,
                        stubKnowledgeGraph,
                        stubGovernanceService,
                        specRepo,
                        stubPlanRepo,
                        stubDaemonScheduler,
                        stubDashboardService,
                      )
          _        <- registry.registerAll(testTools.all)
          args      = Json.Obj(
                        "title"             -> Json.Str("Gateway MCP Expansion"),
                        "content"           -> Json.Str("Add full ADE MCP coverage."),
                        "authorId"          -> Json.Str("architect"),
                        "authorDisplayName" -> Json.Str("Architect"),
                        "authorKind"        -> Json.Str("agent"),
                      )
          result   <- registry.execute(
                        llm4zio.core.ToolCall(id = "6c", name = "create_specification", arguments = args.toJson)
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Gateway MCP Expansion"),
          json.contains("Add full ADE MCP coverage."),
        )
      }
    ),
    suite("plan tools")(
      test("validate_plan writes a passed validation result when governance gates are satisfied") {
        val createdEvent = PlanEvent.Created(
          planId = stubPlan.id,
          conversationId = stubPlan.conversationId,
          workspaceId = stubPlan.workspaceId,
          specificationId = stubPlan.specificationId,
          summary = stubPlan.summary,
          rationale = stubPlan.rationale,
          drafts = stubPlan.drafts,
          occurredAt = stubPlan.createdAt,
        )
        for
          specRepo <- makeSpecificationRepository(
                        List(
                          SpecificationEvent.Created(
                            specificationId = stubSpecification.id,
                            title = stubSpecification.title,
                            content = stubSpecification.content,
                            author = stubSpecification.author,
                            status = SpecificationStatus.Draft,
                            linkedPlanRef = stubSpecification.linkedPlanRef,
                            occurredAt = stubSpecification.createdAt,
                          ),
                          SpecificationEvent.Approved(
                            specificationId = stubSpecification.id,
                            approvedBy = stubSpecification.author,
                            occurredAt = stubSpecification.updatedAt,
                          ),
                        )
                      )
          planRepo <- makePlanRepository(List(createdEvent))
          registry <- ToolRegistry.make
          testTools = GatewayMcpTools(
                        stubIssueRepo,
                        stubAgentRepo,
                        stubWorkspaceRepo,
                        stubRunService,
                        stubDecisionInbox,
                        stubEvolutionEngine,
                        stubMemoryRepo,
                        stubAnalysisRepo,
                        stubKnowledgeGraph,
                        stubGovernanceService,
                        specRepo,
                        planRepo,
                        stubDaemonScheduler,
                        stubDashboardService,
                      )
          _        <- registry.registerAll(testTools.all)
          args      = Json.Obj("planId" -> Json.Str(stubPlan.id.value))
          result   <- registry.execute(llm4zio.core.ToolCall(id = "6d", name = "validate_plan", arguments = args.toJson))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("\"status\":\"Validated\""),
          json.contains("SpecReview"),
        )
      }
    ),
    suite("daemon tools")(
      test("list_daemons returns daemon runtime status") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "6e", name = "list_daemons", arguments = "{}"))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Planner Daemon"),
          json.contains("Healthy"),
        )
      }
    ),
    suite("dashboard tools")(
      test("get_sdlc_dashboard returns counts and lifecycle summary") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(
                        llm4zio.core.ToolCall(id = "6f", name = "get_sdlc_dashboard", arguments = "{}")
                      )
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("\"plans\":1"),
          json.contains("Plans waiting for execution"),
        )
      },
      test("get_stoppages returns focused stoppage alerts") {
        for
          registry <- ToolRegistry.make
          _        <- registry.registerAll(tools.all)
          result   <- registry.execute(llm4zio.core.ToolCall(id = "6g", name = "get_stoppages", arguments = "{}"))
          json      = result.result.toOption.get.toJson
        yield assertTrue(
          json.contains("Wire MCP dashboard tools"),
          json.contains("manual approval"),
        )
      },
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
