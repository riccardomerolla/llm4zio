package daemon.control

import java.nio.file.Files
import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import daemon.entity.*
import db.{ ConfigRepository, CustomAgentRow, PersistenceError as DbPersistenceError, SettingRow, WorkflowRow }
import governance.entity.*
import issues.entity.*
import orchestration.control.{ AgentPoolManager, PoolError, SlotHandle }
import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, ProjectId }
import workspace.entity.*

object DaemonAgentSchedulerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  final private class StubProjectRepository(projects: List[Project]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit]   = ZIO.unit
    override def list: IO[PersistenceError, List[Project]]                 = ZIO.succeed(projects)
    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ZIO.succeed(projects.find(_.id == id))
    override def delete(id: ProjectId): IO[PersistenceError, Unit]         = ZIO.unit

  final private class StubWorkspaceRepository(workspaces: List[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(workspaces)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       = ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  final private class StubIssueRepository(ref: Ref[Map[IssueId, List[IssueEvent]]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      ref.update(current => current.updated(event.issueId, current.getOrElse(event.issueId, Nil) :+ event))

    override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
      history(id).flatMap(events =>
        ZIO
          .fromEither(AgentIssue.fromEvents(events))
          .mapError(error => PersistenceError.SerializationFailed(s"issue:${id.value}", error))
      )

    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
      ref.get.flatMap(current =>
        ZIO.fromOption(current.get(id)).orElseFail(PersistenceError.NotFound("issue", id.value))
      )

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ref.get.flatMap(current =>
        ZIO.foreach(current.keys.toList)(get).map(
          _.slice(filter.offset, filter.offset + filter.limit)
        )
      )

    override def delete(id: IssueId): IO[PersistenceError, Unit] =
      ref.update(_ - id)

  final private class StubActivityHub(ref: Ref[List[ActivityEvent]]) extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = ref.update(_ :+ event)
    override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent]

  final private class StubAgentPoolManager extends AgentPoolManager:
    override def acquireSlot(agentName: String): IO[PoolError, SlotHandle] =
      ZIO.succeed(SlotHandle("slot-1", agentName, now))
    override def releaseSlot(handle: SlotHandle): UIO[Unit]                = ZIO.unit
    override def availableSlots(agentName: String): UIO[Int]               = ZIO.succeed(1)
    override def resize(agentName: String, newMax: Int): UIO[Unit]         = ZIO.unit

  final private class StubConfigRepository(ref: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           =
      ref.get.map(_.toList.map { case (key, value) => SettingRow(key, value, now) }.sortBy(_.key))
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                =
      ref.get.map(_.get(key).map(value => SettingRow(key, value, now)))
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            =
      ref.update(_.updated(key, value))
    override def deleteSetting(key: String): IO[DbPersistenceError, Unit]                           =
      ref.update(_ - key)
    override def deleteSettingsByPrefix(prefix: String): IO[DbPersistenceError, Unit]               =
      ref.update(_.filterNot(_._1.startsWith(prefix)))
    override def createWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Long]                = ZIO.succeed(1L)
    override def getWorkflow(id: Long): IO[DbPersistenceError, Option[WorkflowRow]]                 = ZIO.succeed(None)
    override def getWorkflowByName(name: String): IO[DbPersistenceError, Option[WorkflowRow]]       = ZIO.succeed(None)
    override def listWorkflows: IO[DbPersistenceError, List[WorkflowRow]]                           = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Unit]                = ZIO.unit
    override def deleteWorkflow(id: Long): IO[DbPersistenceError, Unit]                             = ZIO.unit
    override def createCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Long]             = ZIO.succeed(1L)
    override def getCustomAgent(id: Long): IO[DbPersistenceError, Option[CustomAgentRow]]           = ZIO.succeed(None)
    override def getCustomAgentByName(name: String): IO[DbPersistenceError, Option[CustomAgentRow]] = ZIO.succeed(None)
    override def listCustomAgents: IO[DbPersistenceError, List[CustomAgentRow]]                     = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Unit]             = ZIO.unit
    override def deleteCustomAgent(id: Long): IO[DbPersistenceError, Unit]                          = ZIO.unit

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

  private def makeScheduler(
    workspacePath: String,
    governancePolicy: Option[GovernancePolicy] = None,
    settings: Map[String, String] = Map.empty,
    seededIssues: Map[IssueId, List[IssueEvent]] = Map.empty,
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
                       issueRepository = new StubIssueRepository(issueRef),
                       activityHub = new StubActivityHub(activityRef),
                       agentPoolManager = new StubAgentPoolManager,
                       configRepository = new StubConfigRepository(configRef),
                       governanceRepository = new StubGovernancePolicyRepository(governancePolicy),
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
                                                  new StubIssueRepository(issueRef).get(id)
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
                                        new StubIssueRepository(issueRef).get(id)
                                      )
          maintenance               = rebuilt.filter(_.issueType == "maintenance")
        yield assertTrue(
          maintenance.size == 1,
          maintenance.head.title.contains("Debt Detector"),
          maintenance.head.description.contains("Service.scala"),
        )
      },
    )
