package daemon.control

import java.nio.file.Files
import java.time.Instant

import zio.*
import zio.test.*

import activity.entity.ActivityEvent
import daemon.entity.*
import governance.entity.*
import issues.entity.*
import orchestration.entity.{ AgentPoolManager, PoolError, SlotHandle }
import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ DaemonAgentSpecId, IssueId, ProjectId }
import shared.testfixtures.*
import workspace.entity.*

object ProactiveAgentWorkflowSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-04-14T10:00:00Z")

  final private class StubProjectRepository(projects: List[Project]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit]   = ZIO.unit
    override def list: IO[PersistenceError, List[Project]]                 = ZIO.succeed(projects)
    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ZIO.succeed(projects.find(_.id == id))
    override def delete(id: ProjectId): IO[PersistenceError, Unit]         = ZIO.unit

  final private class StubAgentPoolManager extends AgentPoolManager:
    override def acquireSlot(agentName: String): IO[PoolError, SlotHandle] =
      ZIO.succeed(SlotHandle("slot-1", agentName, now))
    override def releaseSlot(handle: SlotHandle): UIO[Unit]                = ZIO.unit
    override def availableSlots(agentName: String): UIO[Int]               = ZIO.succeed(1)
    override def resize(agentName: String, newMax: Int): UIO[Unit]         = ZIO.unit

  final private class StubGovernancePolicyRepository extends GovernancePolicyRepository:
    override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]                   = ZIO.unit
    override def get(id: shared.ids.Ids.GovernancePolicyId): IO[PersistenceError, GovernancePolicy] =
      ZIO.fail(PersistenceError.NotFound("governance_policy", id.value))
    override def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy]   =
      ZIO.fail(PersistenceError.NotFound("governance_policy", projectId.value))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]]  =
      ZIO.succeed(Nil)
    override def list: IO[PersistenceError, List[GovernancePolicy]]                                 =
      ZIO.succeed(Nil)

  final private class StubDaemonAgentSpecRepository(specs: List[DaemonAgentSpec]) extends DaemonAgentSpecRepository:
    override def get(id: DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec]                =
      ZIO.fromOption(specs.find(_.id == id)).orElseFail(PersistenceError.NotFound("daemon_spec", id.value))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]] =
      ZIO.succeed(specs.filter(_.projectId == projectId))
    override def listAll: IO[PersistenceError, List[DaemonAgentSpec]]                             =
      ZIO.succeed(specs)
    override def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit]                          = ZIO.unit
    override def delete(id: DaemonAgentSpecId): IO[PersistenceError, Unit]                        = ZIO.unit

  private def makeScheduler(
    workspacePath: String,
    customSpecs: List[DaemonAgentSpec] = Nil,
  ): ZIO[Any, Nothing, (DaemonAgentSchedulerLive, Ref[Map[IssueId, List[IssueEvent]]], Ref[List[ActivityEvent]])] =
    for
      issueRef    <- Ref.make(Map.empty[IssueId, List[IssueEvent]])
      activityRef <- Ref.make(List.empty[ActivityEvent])
      configRef   <- Ref.make(Map.empty[String, String])
      queue       <- Queue.unbounded[DaemonJob]
      runtimeRef  <- Ref.Synchronized.make(Map.empty[DaemonAgentSpecId, DaemonAgentRuntime])
      projectId    = ProjectId("project-1")
      project      = Project(
                       id = projectId,
                       name = "Alpha",
                       description = Some("Demo"),
                       settings = ProjectSettings(defaultAgent = Some("code-agent")),
                       createdAt = now,
                       updatedAt = now,
                     )
      workspace    = Workspace(
                       id = "ws-1",
                       projectId = projectId,
                       name = "Workspace One",
                       localPath = workspacePath,
                       defaultAgent = Some("code-agent"),
                       description = Some("Main workspace"),
                       enabled = true,
                       runMode = RunMode.Host,
                       cliTool = "codex",
                       createdAt = now,
                       updatedAt = now,
                     )
      scheduler    = DaemonAgentSchedulerLive(
                       projectRepository = new StubProjectRepository(List(project)),
                       workspaceRepository = new StubWorkspaceRepository(List(workspace)),
                       issueRepository = new MutableIssueRepository(issueRef),
                       activityHub = new StubActivityHub(activityRef),
                       agentPoolManager = new StubAgentPoolManager,
                       configRepository = new MutableConfigRepository(configRef),
                       governanceRepository = new StubGovernancePolicyRepository,
                       daemonRepository = new StubDaemonAgentSpecRepository(customSpecs),
                       queue = queue,
                       runtimeState = runtimeRef,
                     )
    yield (scheduler, issueRef, activityRef)

  private def makePlanningSpec(projectId: ProjectId): DaemonAgentSpec =
    DaemonAgentSpec(
      id = DaemonAgentSpec.idFor(projectId, DaemonAgentSpec.PlanningAgentKey),
      daemonKey = DaemonAgentSpec.PlanningAgentKey,
      projectId = projectId,
      name = "Planning Agent",
      purpose = "Analyze workspace and produce iteration planning recommendations.",
      trigger = DaemonTriggerCondition.Scheduled(1.hour),
      workspaceIds = List("ws-1"),
      agentName = "code-agent",
      prompt = "Review open issues and propose a prioritized iteration plan.",
      limits = DaemonExecutionLimits(maxIssuesPerRun = 1, cooldown = 1.hour, timeout = 10.minutes),
      builtIn = false,
      governed = false,
    )

  private def makeTriageSpec(projectId: ProjectId): DaemonAgentSpec =
    DaemonAgentSpec(
      id = DaemonAgentSpec.idFor(projectId, DaemonAgentSpec.TriageAgentKey),
      daemonKey = DaemonAgentSpec.TriageAgentKey,
      projectId = projectId,
      name = "Triage Agent",
      purpose = "Categorize and prioritize newly created issues.",
      trigger = DaemonTriggerCondition.Scheduled(30.minutes),
      workspaceIds = List("ws-1"),
      agentName = "code-agent",
      prompt = "Triage incoming issues and assign labels, priority, and category.",
      limits = DaemonExecutionLimits(maxIssuesPerRun = 1, cooldown = 30.minutes, timeout = 10.minutes),
      builtIn = false,
      governed = false,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ProactiveAgentWorkflowSpec")(
      test("planning agent creates recommendation maintenance issue") {
        val projectId    = ProjectId("project-1")
        val planningSpec = makePlanningSpec(projectId)
        for
          temp                               <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-planning").toString).orDie
          (scheduler, issueRef, activityRef) <- makeScheduler(temp, customSpecs = List(planningSpec))
          _                                  <- scheduler.trigger(planningSpec.id)
          _                                  <- scheduler.worker
          issues                             <- issueRef.get
          rebuilt                            <- ZIO.foreach(issues.keys.toList)(id =>
                                                  new MutableIssueRepository(issueRef).get(id)
                                                )
          maintenance                         = rebuilt.filter(_.issueType == "maintenance")
          activities                         <- activityRef.get
        yield assertTrue(
          maintenance.size == 1,
          maintenance.head.tags.exists(_.startsWith(s"daemon:${DaemonAgentSpec.PlanningAgentKey}")),
          activities.exists(_.eventType == activity.entity.ActivityEventType.DaemonCompleted),
        )
      },
      test("triage agent creates categorization maintenance issue") {
        val projectId  = ProjectId("project-1")
        val triageSpec = makeTriageSpec(projectId)
        for
          temp                               <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-triage").toString).orDie
          (scheduler, issueRef, activityRef) <- makeScheduler(temp, customSpecs = List(triageSpec))
          _                                  <- scheduler.trigger(triageSpec.id)
          _                                  <- scheduler.worker
          issues                             <- issueRef.get
          rebuilt                            <- ZIO.foreach(issues.keys.toList)(id =>
                                                  new MutableIssueRepository(issueRef).get(id)
                                                )
          maintenance                         = rebuilt.filter(_.issueType == "maintenance")
          activities                         <- activityRef.get
        yield assertTrue(
          maintenance.size == 1,
          maintenance.head.tags.exists(_.startsWith(s"daemon:${DaemonAgentSpec.TriageAgentKey}")),
          activities.exists(_.eventType == activity.entity.ActivityEventType.DaemonCompleted),
        )
      },
    )
