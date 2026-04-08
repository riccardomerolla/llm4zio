package shared.testfixtures

import java.time.Instant

import zio.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowDefinition, WorkflowRow }
import activity.control.ActivityHub
import activity.entity.ActivityEvent
import issues.entity.*
import orchestration.control.*
import shared.errors.{ ControlPlaneError, PersistenceError }
import shared.ids.Ids.{ IssueId, ProjectId }
import taskrun.entity.TaskStep
import workspace.entity.*

// ── ActivityHub stubs ──────────────────────────────────────────────────────

/** No-op ActivityHub: drops all published events. Use when activity is not under test. */
object NoOpActivityHub extends ActivityHub:
  override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
  override def subscribe: UIO[Dequeue[ActivityEvent]]   =
    Queue.bounded[ActivityEvent](1).map(q => q: Dequeue[ActivityEvent])

/** Capturing ActivityHub: records all published events into a Ref for assertion. */
final class StubActivityHub(val events: Ref[List[ActivityEvent]]) extends ActivityHub:
  override def publish(event: ActivityEvent): UIO[Unit] = events.update(_ :+ event)
  override def subscribe: UIO[Dequeue[ActivityEvent]]   =
    Queue.unbounded[ActivityEvent].map(q => q: Dequeue[ActivityEvent])

object StubActivityHub:
  def make: UIO[StubActivityHub] = Ref.make(List.empty[ActivityEvent]).map(new StubActivityHub(_))

// ── IssueRepository stubs ─────────────────────────────────────────────────

/** Stateless IssueRepository backed by a fixed list of issues.
  *
  * Use for controller and boundary tests that need to return pre-seeded issues. Event appends are silently ignored;
  * `history` always returns Nil.
  */
final class StubIssueRepository(issues: List[AgentIssue]) extends IssueRepository:
  override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
  override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
    ZIO.fromOption(issues.find(_.id == id)).orElseFail(PersistenceError.NotFound("issue", id.value))
  override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    ZIO.succeed(
      issues
        .filter(i => filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(i.state)))
        .filter(i => filter.agentId.isEmpty)
        .filter(i => filter.runId.isEmpty || i.runId == filter.runId)
        .slice(filter.offset, filter.offset + filter.limit)
    )
  override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

object StubIssueRepository:
  def empty: StubIssueRepository                   = new StubIssueRepository(Nil)
  def of(issues: AgentIssue*): StubIssueRepository = new StubIssueRepository(issues.toList)

/** Event-sourced IssueRepository backed by a mutable Ref.
  *
  * Use for control-layer and orchestration tests that append events and need to read back reconstructed aggregates.
  * Issues are rebuilt via `AgentIssue.fromEvents`.
  */
final class MutableIssueRepository(private val ref: Ref[Map[IssueId, List[IssueEvent]]])
  extends IssueRepository:

  def capturedEvents(id: IssueId): UIO[List[IssueEvent]] = ref.get.map(_.getOrElse(id, Nil))
  def allCapturedEvents: UIO[List[IssueEvent]]           = ref.get.map(_.values.flatten.toList)

  override def append(event: IssueEvent): IO[PersistenceError, Unit] =
    ref.update(m => m.updated(event.issueId, m.getOrElse(event.issueId, Nil) :+ event))

  override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
    history(id).flatMap(events =>
      ZIO
        .fromEither(AgentIssue.fromEvents(events))
        .mapError(cause => PersistenceError.SerializationFailed(s"issue:${id.value}", cause))
    )

  override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    ref.get.flatMap(m => ZIO.fromOption(m.get(id)).orElseFail(PersistenceError.NotFound("issue", id.value)))

  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    ref.get.flatMap(m => ZIO.foreach(m.keys.toList)(get))

  override def delete(id: IssueId): IO[PersistenceError, Unit] = ref.update(_ - id)

object MutableIssueRepository:
  def empty: UIO[MutableIssueRepository] =
    Ref.make(Map.empty[IssueId, List[IssueEvent]]).map(new MutableIssueRepository(_))

  def seeded(initial: Map[IssueId, List[IssueEvent]]): UIO[MutableIssueRepository] =
    Ref.make(initial).map(new MutableIssueRepository(_))

// ── WorkspaceRepository stub ──────────────────────────────────────────────

/** Stateless WorkspaceRepository backed by a fixed list of workspaces and optional runs. */
final class StubWorkspaceRepository(
  workspaces: List[Workspace],
  runs: List[WorkspaceRun] = Nil,
) extends WorkspaceRepository:
  override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
  override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(workspaces)
  override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]     =
    ZIO.succeed(workspaces.filter(_.projectId == projectId))
  override def get(id: String): IO[PersistenceError, Option[Workspace]]                       =
    ZIO.succeed(workspaces.find(_.id == id))
  override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
  override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
    ZIO.succeed(runs.filter(_.workspaceId == workspaceId))
  override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
    ZIO.succeed(runs.filter(_.issueRef == issueRef))
  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
    ZIO.succeed(runs.find(_.id == id))

object StubWorkspaceRepository:
  def empty: StubWorkspaceRepository                        = new StubWorkspaceRepository(Nil)
  def of(workspaces: Workspace*): StubWorkspaceRepository   = new StubWorkspaceRepository(workspaces.toList)
  def single(workspace: Workspace): StubWorkspaceRepository = new StubWorkspaceRepository(List(workspace))

// ── ConfigRepository stubs ────────────────────────────────────────────────

/** Immutable ConfigRepository stub backed by a fixed Map.
  *
  * Use for tests where settings are read-only. Workflow/custom-agent methods all die with "unused".
  */
final class StubConfigRepository(settings: Map[String, String]) extends ConfigRepository:

  private val ts: Instant = Instant.parse("2026-01-01T00:00:00Z")

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    ZIO.succeed(settings.toList.map { case (k, v) => SettingRow(k, v, ts) })

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    ZIO.succeed(settings.get(key).map(v => SettingRow(key, v, ts)))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] = ZIO.unit
  override def deleteSetting(key: String): IO[PersistenceError, Unit]                = ZIO.unit
  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]    = ZIO.unit

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.dieMessage("unused")
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

object StubConfigRepository:
  def empty: StubConfigRepository = new StubConfigRepository(Map.empty)

/** Mutable ConfigRepository stub backed by a Ref[Map[String, String]].
  *
  * Use for tests that write settings (upsert/delete) and need to observe changes.
  */
final class MutableConfigRepository(private val ref: Ref[Map[String, String]]) extends ConfigRepository:

  private val ts: Instant = Instant.parse("2026-01-01T00:00:00Z")

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    ref.get.map(_.toList.sortBy(_._1).map { case (k, v) => SettingRow(k, v, ts) })

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    ref.get.map(_.get(key).map(v => SettingRow(key, v, ts)))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    ref.update(_.updated(key, value))

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    ref.update(_ - key)

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    ref.update(_.filterNot { case (k, _) => k.startsWith(prefix) })

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.dieMessage("unused")
  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.dieMessage("unused")
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

object MutableConfigRepository:
  def empty: UIO[MutableConfigRepository] =
    Ref.make(Map.empty[String, String]).map(new MutableConfigRepository(_))

  def from(initial: Map[String, String]): UIO[MutableConfigRepository] =
    Ref.make(initial).map(new MutableConfigRepository(_))

// ── OrchestratorControlPlane stub ─────────────────────────────────────────

/** No-op OrchestratorControlPlane: silently drops all notifications.
  *
  * Use when the control plane is a transitive dependency but not under test. Only `notifyWorkspaceAgent` is safe to
  * call; all other methods throw.
  */
object NoOpOrchestratorControlPlane extends OrchestratorControlPlane:
  override def startWorkflow(runId: String, workflowId: Long, definition: WorkflowDefinition)
    : ZIO[Any, ControlPlaneError, String] = ???
  override def routeStep(runId: String, step: TaskStep, capabilities: List[AgentCapability])
    : ZIO[Any, ControlPlaneError, String] = ???
  override def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]                            = ???
  override def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]                 = ???
  override def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]                    = ???
  override def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]            = ???
  override def subscribeAllEvents: ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]                          = ???
  override def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]                                  = ???
  override def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]                   = ???
  override def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit] = ???
  override def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]                   = ???
  override def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]                       = ???
  override def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]                   = ???
  override def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]] = ???
  override def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    = ???
  override def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                   = ???
  override def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    = ???
  override def notifyWorkspaceAgent(
    agentName: String,
    state: AgentExecutionState,
    runId: Option[String],
    conversationId: Option[String],
    message: Option[String],
    tokenDelta: Long,
  ): UIO[Unit] = ZIO.unit
