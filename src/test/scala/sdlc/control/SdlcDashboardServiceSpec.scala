package sdlc.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowDefinition, WorkflowRow }
import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import daemon.control.DaemonAgentScheduler
import daemon.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import evolution.entity.*
import governance.entity.*
import issues.entity.*
import plan.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import specification.entity.*

object SdlcDashboardServiceSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  private val specDraft = Specification(
    id = SpecificationId("spec-draft"),
    title = "Draft spec",
    content = "draft",
    status = SpecificationStatus.InRefinement,
    version = 1,
    revisions = Nil,
    linkedIssueIds = Nil,
    linkedPlanRef = None,
    author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner"),
    reviewComments = Nil,
    createdAt = now.minusSeconds(7200),
    updatedAt = now.minusSeconds(7200),
  )

  private val specApproved = specDraft.copy(
    id = SpecificationId("spec-approved"),
    title = "Approved spec",
    status = SpecificationStatus.Approved,
  )

  private val planDraft = Plan(
    id = shared.ids.Ids.PlanId("plan-1"),
    conversationId = 1L,
    workspaceId = Some("ws-1"),
    specificationId = Some(specApproved.id),
    summary = "Plan draft",
    rationale = "Rationale",
    status = PlanStatus.Draft,
    version = 1,
    drafts = Nil,
    validation = None,
    linkedIssueIds = Nil,
    versions = Nil,
    createdAt = now.minusSeconds(3600),
    updatedAt = now.minusSeconds(3600),
  )

  private val activeIssue =
    issue("issue-active", "Active issue", IssueState.InProgress(AgentId("agent-a"), now.minusSeconds(7200)))
  private val churnIssue  = issue(
    "issue-churn",
    "Churn issue",
    IssueState.Rework(now.minusSeconds(3600), "Needs rework"),
    blockedBy = List(IssueId("blocker-1")),
    priority = "high",
  )
  private val reviewIssue =
    issue("issue-review", "Review issue", IssueState.HumanReview(now.minusSeconds(36000)), priority = "critical")
  private val doneIssue   = issue("issue-done", "Done issue", IssueState.Done(now.minusSeconds(1800), "done"))

  private val issueHistories: Map[IssueId, List[IssueEvent]] = Map(
    activeIssue.id -> List(
      IssueEvent.Created(
        activeIssue.id,
        activeIssue.title,
        activeIssue.description,
        "task",
        "medium",
        now.minusSeconds(10800),
      ),
      IssueEvent.Assigned(activeIssue.id, AgentId("agent-a"), now.minusSeconds(9000), now.minusSeconds(9000)),
      IssueEvent.Started(activeIssue.id, AgentId("agent-a"), now.minusSeconds(7200), now.minusSeconds(7200)),
    ),
    churnIssue.id  -> List(
      IssueEvent.Created(
        churnIssue.id,
        churnIssue.title,
        churnIssue.description,
        "task",
        "high",
        now.minusSeconds(30000),
      ),
      IssueEvent.MovedToTodo(churnIssue.id, now.minusSeconds(24000), now.minusSeconds(24000)),
      IssueEvent.Assigned(churnIssue.id, AgentId("agent-b"), now.minusSeconds(22000), now.minusSeconds(22000)),
      IssueEvent.Started(churnIssue.id, AgentId("agent-b"), now.minusSeconds(21000), now.minusSeconds(21000)),
      IssueEvent.MovedToRework(churnIssue.id, now.minusSeconds(18000), "fix", now.minusSeconds(18000)),
      IssueEvent.Reopened(churnIssue.id, now.minusSeconds(17000), now.minusSeconds(17000)),
      IssueEvent.MovedToTodo(churnIssue.id, now.minusSeconds(16000), now.minusSeconds(16000)),
      IssueEvent.Assigned(churnIssue.id, AgentId("agent-b"), now.minusSeconds(15000), now.minusSeconds(15000)),
      IssueEvent.Started(churnIssue.id, AgentId("agent-b"), now.minusSeconds(14500), now.minusSeconds(14500)),
      IssueEvent.MovedToRework(churnIssue.id, now.minusSeconds(14000), "again", now.minusSeconds(14000)),
      IssueEvent.MovedToTodo(churnIssue.id, now.minusSeconds(13000), now.minusSeconds(13000)),
      IssueEvent.Assigned(churnIssue.id, AgentId("agent-b"), now.minusSeconds(12000), now.minusSeconds(12000)),
      IssueEvent.Started(churnIssue.id, AgentId("agent-b"), now.minusSeconds(11000), now.minusSeconds(11000)),
      IssueEvent.MovedToRework(churnIssue.id, now.minusSeconds(3600), "latest", now.minusSeconds(3600)),
    ),
    reviewIssue.id -> List(
      IssueEvent.Created(
        reviewIssue.id,
        reviewIssue.title,
        reviewIssue.description,
        "task",
        "critical",
        now.minusSeconds(50000),
      ),
      IssueEvent.Assigned(reviewIssue.id, AgentId("agent-c"), now.minusSeconds(46000), now.minusSeconds(46000)),
      IssueEvent.Started(reviewIssue.id, AgentId("agent-c"), now.minusSeconds(45000), now.minusSeconds(45000)),
      IssueEvent.Completed(
        reviewIssue.id,
        AgentId("agent-c"),
        now.minusSeconds(40000),
        "implemented",
        now.minusSeconds(40000),
      ),
      IssueEvent.MovedToHumanReview(reviewIssue.id, now.minusSeconds(36000), now.minusSeconds(36000)),
    ),
    doneIssue.id   -> List(
      IssueEvent.Created(
        doneIssue.id,
        doneIssue.title,
        doneIssue.description,
        "task",
        "medium",
        now.minusSeconds(20000),
      ),
      IssueEvent.Assigned(doneIssue.id, AgentId("agent-a"), now.minusSeconds(18000), now.minusSeconds(18000)),
      IssueEvent.Started(doneIssue.id, AgentId("agent-a"), now.minusSeconds(17000), now.minusSeconds(17000)),
      IssueEvent.Completed(doneIssue.id, AgentId("agent-a"), now.minusSeconds(2000), "done", now.minusSeconds(2000)),
      IssueEvent.MarkedDone(doneIssue.id, now.minusSeconds(1800), "done", now.minusSeconds(1800)),
    ),
  )

  private val pendingDecision = Decision(
    id = DecisionId("decision-1"),
    title = "Review issue #issue-review",
    context = "context",
    action = DecisionAction.ReviewIssue,
    source = DecisionSource(
      kind = DecisionSourceKind.IssueReview,
      referenceId = reviewIssue.id.value,
      summary = "Human decision required",
      workspaceId = Some("ws-1"),
      issueId = Some(reviewIssue.id),
    ),
    urgency = DecisionUrgency.High,
    status = DecisionStatus.Pending,
    deadlineAt = Some(now.plusSeconds(3600)),
    createdAt = now.minusSeconds(20000),
    updatedAt = now.minusSeconds(20000),
  )

  private val validatedPlan = planDraft.copy(
    id = PlanId("plan-2"),
    status = PlanStatus.Validated,
    validation = Some(
      PlanValidationResult(
        status = PlanValidationStatus.Passed,
        requiredGates = List(GovernanceGate.SpecReview),
        missingGates = Nil,
        humanApprovalRequired = false,
        reason = None,
        validatedAt = now.minusSeconds(1800),
      )
    ),
    versions = List(
      PlanVersion(
        version = 1,
        summary = "Plan draft",
        rationale = "Rationale",
        drafts = Nil,
        validation = Some(
          PlanValidationResult(
            status = PlanValidationStatus.Blocked,
            requiredGates = List(GovernanceGate.SpecReview, GovernanceGate.CiPassed),
            missingGates = List(GovernanceGate.CiPassed),
            humanApprovalRequired = false,
            reason = Some("Missing CI"),
            validatedAt = now.minusSeconds(86400),
          )
        ),
        status = PlanStatus.Draft,
        changedAt = now.minusSeconds(86400),
      ),
      PlanVersion(
        version = 2,
        summary = "Validated plan",
        rationale = "Rationale",
        drafts = Nil,
        validation = Some(
          PlanValidationResult(
            status = PlanValidationStatus.Passed,
            requiredGates = List(GovernanceGate.SpecReview),
            missingGates = Nil,
            humanApprovalRequired = false,
            reason = None,
            validatedAt = now.minusSeconds(1800),
          )
        ),
        status = PlanStatus.Validated,
        changedAt = now.minusSeconds(1800),
      ),
    ),
    createdAt = now.minusSeconds(172800),
    updatedAt = now.minusSeconds(1800),
  )

  private val activePolicy = GovernancePolicy(
    id = GovernancePolicyId("policy-1"),
    projectId = ProjectId("project-1"),
    name = "Primary policy",
    version = 2,
    transitionRules = Nil,
    daemonTriggers = Nil,
    escalationRules = Nil,
    completionCriteria = Nil,
    isDefault = false,
    createdAt = now.minusSeconds(172800),
    updatedAt = now.minusSeconds(3600),
    archivedAt = None,
  )

  private val archivedPolicy = activePolicy.copy(
    id = GovernancePolicyId("policy-2"),
    name = "Archived policy",
    archivedAt = Some(now.minusSeconds(7200)),
  )

  private val runningDaemon = DaemonAgentStatus(
    spec = DaemonAgentSpec(
      id = DaemonAgentSpecId("daemon-running"),
      daemonKey = "test-guardian",
      projectId = ProjectId("project-1"),
      name = "Test Guardian",
      purpose = "Watch CI",
      trigger = DaemonTriggerCondition.Scheduled(30.minutes),
      workspaceIds = List("ws-1"),
      agentName = "guardian",
      prompt = "check",
      limits = DaemonExecutionLimits(),
      builtIn = true,
      governed = true,
    ),
    enabled = true,
    runtime = DaemonAgentRuntime(
      lifecycle = DaemonLifecycle.Running,
      health = DaemonHealth.Healthy,
      completedAt = Some(now.minusSeconds(300)),
    ),
  )

  private val stoppedDaemon = runningDaemon.copy(
    spec = runningDaemon.spec.copy(
      id = DaemonAgentSpecId("daemon-stopped"),
      daemonKey = "debt-detector",
      name = "Debt Detector",
    ),
    runtime = runningDaemon.runtime.copy(lifecycle = DaemonLifecycle.Stopped, health = DaemonHealth.Paused),
  )

  private val erroredDaemon = runningDaemon.copy(
    spec =
      runningDaemon.spec.copy(id = DaemonAgentSpecId("daemon-errored"), daemonKey = "custom", name = "Custom Daemon"),
    runtime = runningDaemon.runtime.copy(health = DaemonHealth.Degraded, lastError = Some("boom")),
  )

  private val pendingProposal = EvolutionProposal(
    id = EvolutionProposalId("proposal-pending"),
    projectId = ProjectId("project-1"),
    title = "Add daemon guardrail",
    rationale = "Need tighter controls",
    target = EvolutionTarget.WorkflowDefinitionTarget(
      projectId = ProjectId("project-1"),
      workflow = WorkflowDefinition(
        id = Some("quality-gate"),
        name = "Quality Gate",
        description = Some("Gate workflow"),
        steps = List("chat"),
        isBuiltin = false,
      ),
    ),
    template = Some(EvolutionTemplateKind.AddDaemonAgent),
    proposer = EvolutionAuditRecord("planner", "proposed", now.minusSeconds(3600)),
    status = EvolutionProposalStatus.Proposed,
    decisionId = None,
    createdAt = now.minusSeconds(3600),
    updatedAt = now.minusSeconds(3600),
  )

  private val appliedProposal = pendingProposal.copy(
    id = EvolutionProposalId("proposal-applied"),
    title = "Roll out governance policy",
    status = EvolutionProposalStatus.Applied,
    application = Some(EvolutionAuditRecord("operator", "applied", now.minusSeconds(900))),
    createdAt = now.minusSeconds(86400),
    updatedAt = now.minusSeconds(900),
  )

  private val workReportProjection: IssueWorkReportProjection = new IssueWorkReportProjection:
    override def get(issueId: IssueId): UIO[Option[IssueWorkReport]]                                                 = getAll.map(_.get(issueId))
    override def getAll: UIO[Map[IssueId, IssueWorkReport]]                                                          =
      ZIO.succeed(
        Map(
          doneIssue.id  -> IssueWorkReport.empty(doneIssue.id, now).copy(
            tokenUsage = Some(TokenUsage(500, 500, 1000)),
            runtimeSeconds = Some(300),
          ),
          churnIssue.id -> IssueWorkReport.empty(churnIssue.id, now).copy(
            tokenUsage = Some(TokenUsage(750, 250, 1000)),
            runtimeSeconds = Some(600),
          ),
        )
      )
    override def updateWalkthrough(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                        = ZIO.unit
    override def updateAgentSummary(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                       = ZIO.unit
    override def updateDiffStats(issueId: IssueId, stats: IssueDiffStats, at: Instant): UIO[Unit]                    = ZIO.unit
    override def updatePrLink(issueId: IssueId, prUrl: String, status: IssuePrStatus, at: Instant): UIO[Unit]        = ZIO.unit
    override def updateCiStatus(issueId: IssueId, status: IssueCiStatus, at: Instant): UIO[Unit]                     = ZIO.unit
    override def updateTokenUsage(issueId: IssueId, usage: TokenUsage, runtimeSeconds: Long, at: Instant): UIO[Unit] =
      ZIO.unit
    override def addReport(issueId: IssueId, report: IssueReport, at: Instant): UIO[Unit]                            = ZIO.unit
    override def addArtifact(issueId: IssueId, artifact: IssueArtifact, at: Instant): UIO[Unit]                      = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SdlcDashboardServiceSpec")(
      test("snapshot derives lifecycle, churn, stoppages, escalations, and agent performance") {
        val service = SdlcDashboardServiceLive(
          specificationRepository = stubSpecificationRepository,
          planRepository = stubPlanRepository,
          issueRepository = stubIssueRepository,
          decisionInbox = stubDecisionInbox,
          activityRepository = stubActivityRepository,
          configRepository = stubConfigRepository,
          workReportProjection = workReportProjection,
          governancePolicyRepository = stubGovernancePolicyRepository,
          daemonAgentScheduler = stubDaemonAgentScheduler,
          evolutionProposalRepository = stubEvolutionProposalRepository,
        )
        for
          _         <- TestClock.adjust(now.toEpochMilli.millis)
          snapshot  <- service.snapshot
          lifecycle  = snapshot.lifecycle.map(stage => stage.key -> stage.count).toMap
          churn      = snapshot.churnAlerts.find(_.issueId == churnIssue.id.value)
          stoppage   = snapshot.stoppages.find(_.issueId == churnIssue.id.value)
          escalation = snapshot.escalations.find(_.referenceId == pendingDecision.id.value)
          reviewEsc  = snapshot.escalations.find(_.referenceId == reviewIssue.id.value)
          agentA     = snapshot.agentPerformance.find(_.agentName == "agent-a")
          agentB     = snapshot.agentPerformance.find(_.agentName == "agent-b")
        yield assertTrue(
          lifecycle.get("idea").contains(1),
          lifecycle.get("spec").contains(1),
          lifecycle.get("plan").contains(2),
          lifecycle.get("in-progress").contains(2),
          lifecycle.get("review").contains(1),
          lifecycle.get("done").contains(1),
          churn.exists(_.transitionCount >= 6),
          churn.exists(_.bounceCount >= 2),
          stoppage.exists(_.kind == "Blocked"),
          escalation.exists(_.kind == "Decision"),
          reviewEsc.exists(_.kind == "Human Review"),
          agentA.exists(_.throughput == 1),
          agentA.exists(_.successRate > 0.9),
          agentB.exists(_.throughput == 0),
          agentB.exists(_.costUsd > 0.0),
          snapshot.governance.activePolicyCount == 1,
          snapshot.governance.passCount == 1,
          snapshot.governance.failCount == 1,
          snapshot.governance.passRate == 0.5,
          snapshot.daemonHealth.runningCount == 1,
          snapshot.daemonHealth.stoppedCount == 1,
          snapshot.daemonHealth.erroredCount == 1,
          snapshot.evolution.pendingProposalCount == 1,
          snapshot.evolution.recentlyApplied.map(_.proposalId) == List("proposal-applied"),
          snapshot.specificationTrend.direction == SdlcDashboardService.TrendDirection.Up,
          snapshot.planTrend.direction == SdlcDashboardService.TrendDirection.Up,
          snapshot.issueTrend.direction == SdlcDashboardService.TrendDirection.Up,
          snapshot.pendingDecisionTrend.direction == SdlcDashboardService.TrendDirection.Up,
          snapshot.recentActivity.nonEmpty,
        )
      }
    )

  private def issue(
    id: String,
    title: String,
    state: IssueState,
    blockedBy: List[IssueId] = Nil,
    priority: String = "medium",
  ): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = Some(TaskRunId(s"run-$id")),
      conversationId = None,
      title = title,
      description = s"Description for $title",
      issueType = "task",
      priority = priority,
      requiredCapabilities = Nil,
      state = state,
      tags = Nil,
      blockedBy = blockedBy,
      contextPath = "",
      sourceFolder = "",
    )

  private val stubSpecificationRepository: SpecificationRepository = new SpecificationRepository:
    override def append(event: SpecificationEvent): IO[PersistenceError, Unit]                                        = ZIO.unit
    override def get(id: SpecificationId): IO[PersistenceError, Specification]                                        = ZIO.fromOption(List(
      specDraft,
      specApproved,
    ).find(_.id == id)).orElseFail(PersistenceError.NotFound("specification", id.value))
    override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]]                         = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Specification]]                                                      = ZIO.succeed(List(specDraft, specApproved))
    override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, SpecificationDiff] =
      ZIO.fail(PersistenceError.QueryFailed("diff", "unused"))

  private val stubPlanRepository: PlanRepository = new PlanRepository:
    override def append(event: PlanEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def get(id: shared.ids.Ids.PlanId): IO[PersistenceError, Plan]                = ZIO.succeed(planDraft)
    override def history(id: shared.ids.Ids.PlanId): IO[PersistenceError, List[PlanEvent]] = ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[Plan]]                                    = ZIO.succeed(List(planDraft, validatedPlan))

  private val stubIssueRepository: IssueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fromOption(List(
        activeIssue,
        churnIssue,
        reviewIssue,
        doneIssue,
      ).find(_.id == id)).orElseFail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      =
      ZIO.succeed(issueHistories.getOrElse(id, Nil))
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(List(activeIssue, churnIssue, reviewIssue, doneIssue))
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  private val stubDecisionInbox: DecisionInbox = new DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      ZIO.succeed(pendingDecision)
    override def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
      : IO[PersistenceError, Decision] = ZIO.succeed(pendingDecision)
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
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]   = ZIO.succeed(pendingDecision)
    override def get(id: DecisionId): IO[PersistenceError, Decision]                        = ZIO.succeed(pendingDecision)
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]         = ZIO.succeed(List(pendingDecision))
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]         = ZIO.succeed(Nil)

  private val stubActivityRepository: ActivityRepository = new ActivityRepository:
    override def createEvent(event: ActivityEvent): IO[PersistenceError, EventId] = ZIO.succeed(event.id)
    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] =
      ZIO.succeed(
        List(
          ActivityEvent(
            id = EventId("evt-1"),
            eventType = ActivityEventType.DecisionCreated,
            source = "decision-inbox",
            runId = None,
            conversationId = None,
            agentName = Some("agent-c"),
            summary = "Decision opened",
            payload = None,
            createdAt = now.minusSeconds(60),
          )
        ).take(limit)
      )

  private val stubConfigRepository: ConfigRepository = new ConfigRepository:
    private val settings = Map(
      "sdlc.thresholds.churn.transitions" -> "6",
      "sdlc.thresholds.churn.bounces"     -> "2",
      "sdlc.thresholds.stalledHours"      -> "1",
      "sdlc.thresholds.blockedHours"      -> "1",
      "sdlc.thresholds.reviewHours"       -> "4",
      "sdlc.thresholds.decisionHours"     -> "4",
    )

    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                              =
      ZIO.succeed(settings.toList.map { case (key, value) => SettingRow(key, value, now) })
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                   =
      ZIO.succeed(settings.get(key).map(value => SettingRow(key, value, now)))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]               = ZIO.unit
    override def deleteSetting(key: String): IO[PersistenceError, Unit]                              = ZIO.unit
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]                  = ZIO.unit
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       =
      ZIO.dieMessage("unused")
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.dieMessage("unused")
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                                = ZIO.dieMessage("unused")
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.dieMessage("unused")
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.dieMessage("unused")
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                             = ZIO.dieMessage("unused")

  private val stubGovernancePolicyRepository: GovernancePolicyRepository = new GovernancePolicyRepository:
    override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]                  = ZIO.unit
    override def get(id: GovernancePolicyId): IO[PersistenceError, GovernancePolicy]               =
      ZIO
        .fromOption(List(activePolicy, archivedPolicy).find(_.id == id))
        .orElseFail(PersistenceError.NotFound("governance_policy", id.value))
    override def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy]  =
      ZIO.succeed(activePolicy)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]] =
      ZIO.succeed(List(activePolicy, archivedPolicy).filter(_.projectId == projectId))
    override def list: IO[PersistenceError, List[GovernancePolicy]]                                =
      ZIO.succeed(List(activePolicy, archivedPolicy))

  private val stubDaemonAgentScheduler: DaemonAgentScheduler = new DaemonAgentScheduler:
    override def list: IO[PersistenceError, List[DaemonAgentStatus]]                                    = ZIO.succeed(
      List(runningDaemon, stoppedDaemon, erroredDaemon)
    )
    override def start(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                               = ZIO.unit
    override def stop(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                                = ZIO.unit
    override def restart(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ZIO.unit
    override def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): IO[PersistenceError, Unit]        = ZIO.unit
    override def trigger(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                             = ZIO.unit
    override def triggerGovernance(projectId: ProjectId, triggerId: String): IO[PersistenceError, Unit] = ZIO.unit

  private val stubEvolutionProposalRepository: EvolutionProposalRepository = new EvolutionProposalRepository:
    override def append(event: EvolutionProposalEvent): IO[PersistenceError, Unit]                    = ZIO.unit
    override def get(id: EvolutionProposalId): IO[PersistenceError, EvolutionProposal]                =
      ZIO
        .fromOption(List[EvolutionProposal](pendingProposal, appliedProposal).find(_.id == id))
        .orElseFail(PersistenceError.NotFound("evolution_proposal", id.value))
    override def history(id: EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]] =
      ZIO.succeed(Nil)
    override def list(filter: EvolutionProposalFilter): IO[PersistenceError, List[EvolutionProposal]] =
      ZIO.succeed(List(pendingProposal, appliedProposal))
