package daemon.control

import java.nio.file.Files
import java.time.Instant

import zio.*
import zio.test.*

import activity.entity.ActivityEvent
import daemon.entity.*
import governance.entity.*
import issues.entity.*
import orchestration.control.{ AgentPoolManager, PoolError, SlotHandle }
import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, ProjectId }
import shared.testfixtures.*
import workspace.entity.*

object DaemonAgentSchedulerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

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

  final private class StubGovernancePolicyRepository(policy: Option[GovernancePolicy])
    extends GovernancePolicyRepository:
    override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]                   = ZIO.unit
    override def get(id: shared.ids.Ids.GovernancePolicyId): IO[PersistenceError, GovernancePolicy] =
      ZIO.fromOption(policy).orElseFail(PersistenceError.NotFound("governance_policy", id.value))
    override def getActiveByProject(projectId: ProjectId): IO[PersistenceError, GovernancePolicy]   =
      ZIO.fromOption(policy.filter(_.projectId == projectId))
        .orElseFail(PersistenceError.NotFound("governance_policy", projectId.value))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[GovernancePolicy]]  =
      ZIO.succeed(policy.filter(_.projectId == projectId).toList)
    override def list: IO[PersistenceError, List[GovernancePolicy]]                                 =
      ZIO.succeed(policy.toList)

  final private class StubDaemonAgentSpecRepository(specs: List[DaemonAgentSpec]) extends DaemonAgentSpecRepository:
    override def get(id: shared.ids.Ids.DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec] =
      ZIO.fromOption(specs.find(_.id == id)).orElseFail(PersistenceError.NotFound("daemon_spec", id.value))
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]] =
      ZIO.succeed(specs.filter(_.projectId == projectId))
    override def listAll: IO[PersistenceError, List[DaemonAgentSpec]]                             =
      ZIO.succeed(specs)
    override def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit]                          = ZIO.unit
    override def delete(id: shared.ids.Ids.DaemonAgentSpecId): IO[PersistenceError, Unit]         = ZIO.unit

  private def makeScheduler(
    workspacePath: String,
    governancePolicy: Option[GovernancePolicy] = None,
    settings: Map[String, String] = Map.empty,
    seededIssues: Map[IssueId, List[IssueEvent]] = Map.empty,
    customSpecs: List[DaemonAgentSpec] = Nil,
  ): ZIO[Any, Nothing, (DaemonAgentSchedulerLive, Ref[Map[IssueId, List[IssueEvent]]], Ref[List[ActivityEvent]])] =
    for
      issueRef    <- Ref.make(seededIssues)
      activityRef <- Ref.make(List.empty[ActivityEvent])
      configRef   <- Ref.make(settings)
      queue       <- Queue.unbounded[DaemonJob]
      runtimeRef  <- Ref.Synchronized.make(Map.empty[shared.ids.Ids.DaemonAgentSpecId, DaemonAgentRuntime])
      projectId    = ProjectId("project-1")
      project      = Project(
                       id = projectId,
                       name = "Alpha",
                       description = Some("Demo"),
                       workspaceIds = List("ws-1"),
                       settings = ProjectSettings(defaultAgent = Some("code-agent")),
                       createdAt = now,
                       updatedAt = now,
                     )
      workspace    = Workspace(
                       id = "ws-1",
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
                       governanceRepository = new StubGovernancePolicyRepository(governancePolicy),
                       daemonRepository = new StubDaemonAgentSpecRepository(customSpecs),
                       queue = queue,
                       runtimeState = runtimeRef,
                     )
    yield (scheduler, issueRef, activityRef)

  private def sourceIssueEvents(issueId: IssueId): List[IssueEvent] =
    List(
      IssueEvent.Created(
        issueId = issueId,
        title = "Broken pipeline",
        description = "Investigate failures",
        issueType = "task",
        priority = "high",
        occurredAt = now.minusSeconds(60),
      ),
      IssueEvent.WorkspaceLinked(issueId, "ws-1", now.minusSeconds(50)),
      IssueEvent.MovedToTodo(issueId, movedAt = now.minusSeconds(40), occurredAt = now.minusSeconds(40)),
      IssueEvent.CiVerificationResult(
        issueId = issueId,
        passed = false,
        details = "Tests failed in workflow run",
        checkedAt = now.minusSeconds(10),
        occurredAt = now.minusSeconds(10),
      ),
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DaemonAgentSchedulerSpec")(
      test("list derives built-ins and governance-managed enablement") {
        val projectId = ProjectId("project-1")
        val policy    = GovernancePolicy.noOp.copy(
          projectId = projectId,
          daemonTriggers = List(
            GovernanceDaemonTrigger(
              id = DaemonAgentSpec.TestGuardianKey,
              transition = GovernanceTransition(
                GovernanceLifecycleStage.Todo,
                GovernanceLifecycleStage.InProgress,
                GovernanceLifecycleAction.Dispatch,
              ),
              agentName = "code-agent",
              enabled = false,
              schedule = Some("15 minutes"),
            )
          ),
        )
        for
          temp              <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-spec").toString).orDie
          (scheduler, _, _) <- makeScheduler(temp, governancePolicy = Some(policy))
          statuses          <- scheduler.list
          testGuardian       = statuses.find(_.spec.daemonKey == DaemonAgentSpec.TestGuardianKey)
          debtDetector       = statuses.find(_.spec.daemonKey == DaemonAgentSpec.DebtDetectorKey)
        yield assertTrue(
          statuses.size == 2,
          testGuardian.exists(status => !status.enabled && status.spec.governed),
          debtDetector.exists(status => status.enabled && !status.spec.governed),
        )
      },
      test("test guardian creates one maintenance issue and avoids duplicates") {
        val sourceIssueId = IssueId("issue-1")
        for
          temp                               <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-guardian").toString).orDie
          (scheduler, issueRef, activityRef) <- makeScheduler(
                                                  temp,
                                                  seededIssues = Map(sourceIssueId -> sourceIssueEvents(sourceIssueId)),
                                                )
          statuses                           <- scheduler.list
          testGuardianId                      = statuses.find(_.spec.daemonKey == DaemonAgentSpec.TestGuardianKey).map(_.spec.id).get
          _                                  <- scheduler.trigger(testGuardianId)
          _                                  <- scheduler.worker
          _                                  <- scheduler.trigger(testGuardianId)
          _                                  <- scheduler.worker
          issues                             <- issueRef.get
          rebuilt                            <- ZIO.foreach(issues.keys.toList)(id =>
                                                  new MutableIssueRepository(issueRef).get(id)
                                                )
          maintenance                         = rebuilt.filter(_.issueType == "maintenance")
          activities                         <- activityRef.get
        yield assertTrue(
          maintenance.size == 1,
          maintenance.head.tags.exists(_.startsWith("daemon-key:test-guardian:issue-1")),
          activities.exists(_.eventType == activity.entity.ActivityEventType.DaemonCompleted),
        )
      },
      test("debt detector raises a maintenance issue from TODO markers") {
        for
          tempDir                  <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-debt")).orDie
          _                        <- ZIO.attemptBlocking(
                                        Files.writeString(
                                          tempDir.resolve("Service.scala"),
                                          "object Service { // TODO tighten this implementation }\n",
                                        )
                                      ).orDie
          (scheduler, issueRef, _) <- makeScheduler(tempDir.toString)
          statuses                 <- scheduler.list
          debtDetectorId            = statuses.find(_.spec.daemonKey == DaemonAgentSpec.DebtDetectorKey).map(_.spec.id).get
          _                        <- scheduler.trigger(debtDetectorId)
          _                        <- scheduler.worker
          issues                   <- issueRef.get
          rebuilt                  <- ZIO.foreach(issues.keys.toList)(id =>
                                        new MutableIssueRepository(issueRef).get(id)
                                      )
          maintenance               = rebuilt.filter(_.issueType == "maintenance")
        yield assertTrue(
          maintenance.size == 1,
          maintenance.head.title.contains("Debt Detector"),
          maintenance.head.description.contains("Service.scala"),
        )
      },
    )
