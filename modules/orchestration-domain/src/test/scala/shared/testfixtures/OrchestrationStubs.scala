package shared.testfixtures

import zio.*

import _root_.activity.control.ActivityHub
import _root_.activity.entity.ActivityEvent
import _root_.agent.entity.{ Agent, AgentEvent, AgentRepository }
import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowRow }
import _root_.governance.control.{ GovernancePolicyService, GovernanceTransitionDecision }
import _root_.governance.entity.GovernancePolicy
import _root_.issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import _root_.workspace.entity.{
  AssignRunRequest,
  Workspace,
  WorkspaceError,
  WorkspaceRepository,
  WorkspaceRun,
  WorkspaceRunEvent,
  WorkspaceRunService,
  WorkspaceEvent,
  RunStatus,
}
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, IssueId, ProjectId }

// ── StubIssueRepository ───────────────────────────────────────────────────

/** Mutable IssueRepository backed by Refs.
  *
  * Supports reading, appending events, and mutating the issue snapshot directly.
  * Use for orchestration-layer tests that append events and assert on history.
  */
final class StubIssueRepository private (
  private val issuesRef:     Ref[Map[IssueId, AgentIssue]],
  private val historyRef:    Ref[Map[IssueId, List[IssueEvent]]],
  private val allEventsRef:  Ref[List[IssueEvent]],
) extends IssueRepository:

  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    issuesRef.get.map(_.values.toList)

  override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
    issuesRef.get.flatMap { m =>
      m.get(id) match
        case Some(issue) => ZIO.succeed(issue)
        case None        => ZIO.fail(PersistenceError.NotFound("issue", id.value))
    }

  override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    historyRef.get.map(_.getOrElse(id, Nil))

  override def append(event: IssueEvent): IO[PersistenceError, Unit] =
    historyRef.update(m => m.updated(event.issueId, m.getOrElse(event.issueId, Nil) :+ event)) *>
      allEventsRef.update(_ :+ event)

  override def delete(id: IssueId): IO[PersistenceError, Unit] =
    issuesRef.update(_ - id)

  def appendedEvents: UIO[List[IssueEvent]] = allEventsRef.get

  def setIssues(issues: List[AgentIssue]): UIO[Unit] =
    issuesRef.set(issues.map(i => i.id -> i).toMap)

object StubIssueRepository:
  def make(initial: List[AgentIssue] = Nil): UIO[StubIssueRepository] =
    for
      issuesRef    <- Ref.make(initial.map(i => i.id -> i).toMap)
      historyRef   <- Ref.make(Map.empty[IssueId, List[IssueEvent]])
      allEventsRef <- Ref.make(List.empty[IssueEvent])
    yield new StubIssueRepository(issuesRef, historyRef, allEventsRef)

// ── StubAgentRepository ───────────────────────────────────────────────────

/** Mutable AgentRepository backed by a Ref.
  *
  * Use for orchestration tests that need to seed agents and assert on lookups.
  * `append` is not needed in this sprint and dies if called.
  */
final class StubAgentRepository private (
  private val agentsRef: Ref[List[Agent]],
) extends AgentRepository:

  override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
    agentsRef.get.map { agents =>
      if includeDeleted then agents
      else agents.filter(_.deletedAt.isEmpty)
    }

  override def findByName(name: String): IO[PersistenceError, Option[Agent]] =
    list(includeDeleted = true).map(_.find(_.name.equalsIgnoreCase(name.trim)))

  override def get(id: AgentId): IO[PersistenceError, Agent] =
    list(includeDeleted = true).flatMap { agents =>
      agents.find(_.id == id) match
        case Some(agent) => ZIO.succeed(agent)
        case None        => ZIO.fail(PersistenceError.NotFound("agent", id.value))
    }

  override def append(event: AgentEvent): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubAgentRepository.append not used in tests")

  def setAgents(agents: List[Agent]): UIO[Unit] = agentsRef.set(agents)

object StubAgentRepository:
  def make(initial: List[Agent] = Nil): UIO[StubAgentRepository] =
    Ref.make(initial).map(new StubAgentRepository(_))

// ── StubConfigRepository ─────────────────────────────────────────────────

/** Mutable ConfigRepository backed by a Ref[Map[String, String]].
  *
  * Settings operations (get/upsert/delete) are fully functional.
  * Workflow and custom-agent operations die — not used in this sprint.
  */
final class StubConfigRepository private (
  private val settingsRef: Ref[Map[String, String]],
) extends ConfigRepository:

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      now      <- Clock.instant
      settings <- settingsRef.get
    yield settings.toList.sortBy(_._1).map { case (k, v) => SettingRow(k, v, now) }

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    for
      now      <- Clock.instant
      settings <- settingsRef.get
    yield settings.get(key).map(v => SettingRow(key, v, now))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    settingsRef.update(_.updated(key, value))

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    settingsRef.update(_ - key)

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    settingsRef.update(_.filterNot { case (k, _) => k.startsWith(prefix) })

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    ZIO.dieMessage("StubConfigRepository.createWorkflow not used in tests")

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    ZIO.dieMessage("StubConfigRepository.getWorkflow not used in tests")

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    ZIO.dieMessage("StubConfigRepository.getWorkflowByName not used in tests")

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    ZIO.dieMessage("StubConfigRepository.listWorkflows not used in tests")

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubConfigRepository.updateWorkflow not used in tests")

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubConfigRepository.deleteWorkflow not used in tests")

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    ZIO.dieMessage("StubConfigRepository.createCustomAgent not used in tests")

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.dieMessage("StubConfigRepository.getCustomAgent not used in tests")

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.dieMessage("StubConfigRepository.getCustomAgentByName not used in tests")

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    ZIO.dieMessage("StubConfigRepository.listCustomAgents not used in tests")

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubConfigRepository.updateCustomAgent not used in tests")

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubConfigRepository.deleteCustomAgent not used in tests")

object StubConfigRepository:
  def make(initial: Map[String, String] = Map.empty): UIO[StubConfigRepository] =
    Ref.make(initial).map(new StubConfigRepository(_))

// ── StubActivityHub ───────────────────────────────────────────────────────

/** Capturing ActivityHub: records published events and fan-outs to live subscribers.
  *
  * Use for orchestration tests that need to assert on activity events published during a run.
  */
final class StubActivityHub private (
  private val capturedRef:   Ref[Chunk[ActivityEvent]],
  private val subscriberRef: Ref[Set[Queue[ActivityEvent]]],
) extends ActivityHub:

  override def publish(event: ActivityEvent): UIO[Unit] =
    capturedRef.update(_ :+ event) *>
      subscriberRef.get.flatMap { queues =>
        ZIO.foreachDiscard(queues)(_.offer(event).unit)
      }

  override def subscribe: UIO[Dequeue[ActivityEvent]] =
    for
      queue <- Queue.bounded[ActivityEvent](64)
      _     <- subscriberRef.update(_ + queue)
    yield queue

  def published: UIO[Chunk[ActivityEvent]] = capturedRef.get

object StubActivityHub:
  def make: UIO[StubActivityHub] =
    for
      capturedRef   <- Ref.make(Chunk.empty[ActivityEvent])
      subscriberRef <- Ref.make(Set.empty[Queue[ActivityEvent]])
    yield new StubActivityHub(capturedRef, subscriberRef)

// ── StubWorkspaceRepository ───────────────────────────────────────────────

/** Mutable WorkspaceRepository backed by Refs.
  *
  * Supports list/get/listRuns/listRunsByIssueRef/getRun and mutation via setRuns.
  * append/delete/appendRun die — not used in this sprint.
  */
final class StubWorkspaceRepository private (
  private val workspacesRef: Ref[List[Workspace]],
  private val runsRef:       Ref[List[WorkspaceRun]],
) extends WorkspaceRepository:

  override def list: IO[PersistenceError, List[Workspace]] = workspacesRef.get

  override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] =
    workspacesRef.get.map(_.filter(_.projectId == projectId))

  override def get(id: String): IO[PersistenceError, Option[Workspace]] =
    workspacesRef.get.map(_.find(_.id == id))

  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
    runsRef.get.map(_.filter(_.workspaceId == workspaceId))

  override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
    val normalizedRef = issueRef.trim.stripPrefix("#")
    runsRef.get.map(_.filter(run => run.issueRef.trim.stripPrefix("#") == normalizedRef))

  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
    runsRef.get.map(_.find(_.id == id))

  override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubWorkspaceRepository.append not used in tests")

  override def delete(id: String): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubWorkspaceRepository.delete not used in tests")

  override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
    ZIO.dieMessage("StubWorkspaceRepository.appendRun not used in tests")

  def setRuns(runs: List[WorkspaceRun]): UIO[Unit] = runsRef.set(runs)

object StubWorkspaceRepository:
  def make(
    workspaces: List[Workspace] = Nil,
    runs:       List[WorkspaceRun] = Nil,
  ): UIO[StubWorkspaceRepository] =
    for
      workspacesRef <- Ref.make(workspaces)
      runsRef       <- Ref.make(runs)
    yield new StubWorkspaceRepository(workspacesRef, runsRef)

// ── StubWorkspaceRunService ───────────────────────────────────────────────

/** Stub WorkspaceRunService that returns deterministic WorkspaceRun values.
  *
  * Each call to `assign` increments a counter, creating a run with id `run-N`.
  * Captured calls are accessible via `assignments` for assertion.
  * `continueRun` and `cancelRun` die — not used in this sprint.
  */
final class StubWorkspaceRunService private (
  private val counterRef:     Ref[Int],
  private val assignmentsRef: Ref[Chunk[(String, AssignRunRequest)]],
) extends WorkspaceRunService:

  override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
    for
      now <- Clock.instant
      n   <- counterRef.updateAndGet(_ + 1)
      _   <- assignmentsRef.update(_ :+ (workspaceId -> req))
      run  = WorkspaceRun(
               id               = s"run-$n",
               workspaceId      = workspaceId,
               parentRunId      = None,
               issueRef         = req.issueRef,
               agentName        = req.agentName,
               prompt           = req.prompt,
               conversationId   = s"conv-$n",
               worktreePath     = s"/tmp/stub-worktree-$n",
               branchName       = s"stub-branch-$n",
               status           = RunStatus.Pending,
               attachedUsers    = Set.empty,
               controllerUserId = None,
               createdAt        = now,
               updatedAt        = now,
             )
    yield run

  override def continueRun(
    runId:             String,
    followUpPrompt:    String,
    agentNameOverride: Option[String] = None,
  ): IO[WorkspaceError, WorkspaceRun] =
    ZIO.dieMessage("StubWorkspaceRunService.continueRun not used in tests")

  override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
    ZIO.dieMessage("StubWorkspaceRunService.cancelRun not used in tests")

  def assignments: UIO[Chunk[(String, AssignRunRequest)]] = assignmentsRef.get

object StubWorkspaceRunService:
  def make: UIO[StubWorkspaceRunService] =
    for
      counterRef     <- Ref.make(0)
      assignmentsRef <- Ref.make(Chunk.empty[(String, AssignRunRequest)])
    yield new StubWorkspaceRunService(counterRef, assignmentsRef)

// ── StubGovernancePolicyService ───────────────────────────────────────────

/** Stub GovernancePolicyService with a configurable decision.
  *
  * Defaults to `allowAll`. Use `setDecision` to inject a denial for negative-path tests.
  */
final class StubGovernancePolicyService private (
  private val decisionRef: Ref[GovernanceTransitionDecision],
) extends GovernancePolicyService:

  override def evaluateForWorkspace(
    workspaceId: String,
    context:     _root_.governance.control.GovernanceEvaluationContext,
  ): IO[PersistenceError, GovernanceTransitionDecision] =
    decisionRef.get

  override def resolvePolicyForWorkspace(workspaceId: String): IO[PersistenceError, GovernancePolicy] =
    ZIO.succeed(GovernancePolicy.noOp)

  def setDecision(decision: GovernanceTransitionDecision): UIO[Unit] = decisionRef.set(decision)

object StubGovernancePolicyService:

  val allowAll: GovernanceTransitionDecision =
    GovernanceTransitionDecision(
      allowed                = true,
      requiredGates          = Set.empty,
      missingGates           = Set.empty,
      humanApprovalRequired  = false,
      daemonTriggers         = Nil,
      escalationRules        = Nil,
      completionCriteria     = None,
      reason                 = None,
    )

  def deny(reason: String): GovernanceTransitionDecision =
    allowAll.copy(allowed = false, reason = Some(reason))

  def make(
    decision: GovernanceTransitionDecision = allowAll,
  ): UIO[StubGovernancePolicyService] =
    Ref.make(decision).map(new StubGovernancePolicyService(_))
