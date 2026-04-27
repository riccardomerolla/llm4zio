package orchestration.control

import java.time.{ Duration, Instant }

import zio.*
import zio.test.*

import _root_.activity.entity.ActivityEventType
import _root_.agent.entity.{ Agent, AgentPermissions, TrustLevel }
import issues.entity.{ AgentIssue, IssueEvent, IssueState }
import orchestration.entity.{ AgentPoolManager, SlotHandle }
import shared.ids.Ids.{ AgentId, IssueId, ProjectId }
import shared.testfixtures.{
  StubActivityHub,
  StubAgentRepository,
  StubConfigRepository,
  StubGovernancePolicyService,
  StubIssueRepository,
  StubWorkspaceRepository,
  StubWorkspaceRunService,
}
import workspace.entity.{ RunMode, Workspace }

object AutoDispatcherSpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-04-25T12:00:00Z")

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def mkTodoIssue(idStr: String, capabilities: List[String], wsId: String): AgentIssue =
    AgentIssue(
      id                   = IssueId(idStr),
      runId                = None,
      conversationId       = None,
      title                = s"Issue $idStr",
      description          = "Test issue description",
      issueType            = "feature",
      priority             = "medium",
      requiredCapabilities = capabilities,
      state                = IssueState.Todo(now),
      tags                 = Nil,
      blockedBy            = Nil,
      blocking             = Nil,
      contextPath          = "",
      sourceFolder         = "",
      workspaceId          = Some(wsId),
    )

  private def mkAgent(name: String, caps: List[String]): Agent =
    Agent(
      id                 = AgentId(s"agent-$name"),
      name               = name,
      description        = s"stub agent $name",
      cliTool            = "gemini",
      capabilities       = caps,
      defaultModel       = None,
      systemPrompt       = None,
      maxConcurrentRuns  = 2,
      envVars            = Map.empty,
      timeout            = Duration.ofMinutes(30),
      enabled            = true,
      createdAt          = now,
      updatedAt          = now,
      permissions        = AgentPermissions.defaults(
        trustLevel = TrustLevel.Standard,
        cliTool    = "gemini",
        timeout    = Duration.ofMinutes(30),
        maxEstimatedTokens = None,
      ),
    )

  private def mkWorkspace(id: String): Workspace =
    Workspace(
      id            = id,
      projectId     = ProjectId("project-1"),
      name          = s"Workspace $id",
      localPath     = s"/tmp/ws-$id",
      defaultAgent  = None,
      description   = None,
      enabled       = true,
      runMode       = RunMode.Host,
      cliTool       = "gemini",
      createdAt     = now,
      updatedAt     = now,
    )

  /** An inline AgentPoolManager stub that always reports `slotsAvailable` slots. */
  private def stubPoolManager(slotsAvailable: Int): AgentPoolManager = new AgentPoolManager:
    def acquireSlot(agentName: String): IO[orchestration.entity.PoolError, SlotHandle] =
      ZIO.dieMessage("acquireSlot not used in AutoDispatcherSpec")
    def releaseSlot(handle: SlotHandle): UIO[Unit] = ZIO.unit
    def availableSlots(agentName: String): UIO[Int] = ZIO.succeed(slotsAvailable)
    def resize(agentName: String, newMax: Int): UIO[Unit] = ZIO.unit

  /** Build an AutoDispatcherLive from all 9 stub components. */
  private def makeDispatcher(
    cfg:       StubConfigRepository,
    issueRepo: StubIssueRepository,
    agentRepo: StubAgentRepository,
    wsRepo:    StubWorkspaceRepository,
    runSvc:    StubWorkspaceRunService,
    actHub:    StubActivityHub,
    poolMgr:   AgentPoolManager,
    govSvc:    StubGovernancePolicyService,
  ): UIO[AutoDispatcherLive] =
    ZIO.succeed(
      AutoDispatcherLive(
        configRepository        = cfg,
        issueRepository         = issueRepo,
        dependencyResolver      = DependencyResolverLive(issueRepo),
        agentRepository         = agentRepo,
        workspaceRepository     = wsRepo,
        workspaceRunService     = runSvc,
        activityHub             = actHub,
        agentPoolManager        = poolMgr,
        governancePolicyService = govSvc,
      )
    )

  // ── Spec ───────────────────────────────────────────────────────────────────

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("AutoDispatcherSpec")(

      test("dispatchOnce dispatches a ready Todo issue to a matching agent and returns 1") {
        val issue = mkTodoIssue("i1", List("scala"), "ws-1")
        val alice = mkAgent("alice", List("scala"))
        val ws    = mkWorkspace("ws-1")
        for
          cfg     <- StubConfigRepository.make(Map(AutoDispatcher.enabledSettingKey -> "true"))
          iRepo   <- StubIssueRepository.make(List(issue))
          aRepo   <- StubAgentRepository.make(List(alice))
          wsRepo  <- StubWorkspaceRepository.make(workspaces = List(ws), runs = Nil)
          runSvc  <- StubWorkspaceRunService.make
          actHub  <- StubActivityHub.make
          govSvc  <- StubGovernancePolicyService.make()
          d       <- makeDispatcher(cfg, iRepo, aRepo, wsRepo, runSvc, actHub, stubPoolManager(5), govSvc)
          count   <- d.dispatchOnce
          assigns <- runSvc.assignments
          events  <- iRepo.appendedEvents
        yield
          assertTrue(
            count == 1,
            assigns.size == 1,
            assigns.exists { case (wsId, req) => wsId == "ws-1" && req.agentName == "alice" },
            events.exists(_.isInstanceOf[IssueEvent.Assigned]),
            events.exists(_.isInstanceOf[IssueEvent.Started]),
          )
      },

      test("respects pool capacity — returns 0 when no slots are available for the matching agent") {
        val issue = mkTodoIssue("i1", List("scala"), "ws-1")
        val alice = mkAgent("alice", List("scala"))
        val ws    = mkWorkspace("ws-1")
        for
          cfg     <- StubConfigRepository.make(Map(AutoDispatcher.enabledSettingKey -> "true"))
          iRepo   <- StubIssueRepository.make(List(issue))
          aRepo   <- StubAgentRepository.make(List(alice))
          wsRepo  <- StubWorkspaceRepository.make(workspaces = List(ws), runs = Nil)
          runSvc  <- StubWorkspaceRunService.make
          actHub  <- StubActivityHub.make
          govSvc  <- StubGovernancePolicyService.make()
          d       <- makeDispatcher(cfg, iRepo, aRepo, wsRepo, runSvc, actHub, stubPoolManager(0), govSvc)
          count   <- d.dispatchOnce
          assigns <- runSvc.assignments
        yield
          assertTrue(count == 0) &&
          assertTrue(assigns.isEmpty)
      },

      test("publishes AgentAssigned activity event on successful dispatch") {
        val issue = mkTodoIssue("i1", List("scala"), "ws-1")
        val alice = mkAgent("alice", List("scala"))
        val ws    = mkWorkspace("ws-1")
        for
          cfg       <- StubConfigRepository.make(Map(AutoDispatcher.enabledSettingKey -> "true"))
          iRepo     <- StubIssueRepository.make(List(issue))
          aRepo     <- StubAgentRepository.make(List(alice))
          wsRepo    <- StubWorkspaceRepository.make(workspaces = List(ws), runs = Nil)
          runSvc    <- StubWorkspaceRunService.make
          actHub    <- StubActivityHub.make
          govSvc    <- StubGovernancePolicyService.make()
          d         <- makeDispatcher(cfg, iRepo, aRepo, wsRepo, runSvc, actHub, stubPoolManager(5), govSvc)
          _         <- d.dispatchOnce
          published <- actHub.published
        yield assertTrue(
          published.size == 1,
          published.exists(e =>
            e.eventType == ActivityEventType.AgentAssigned &&
              e.source == "auto-dispatch" &&
              e.agentName == Some("alice")
          ),
        )
      },

      test("skips dispatch when governance policy denies") {
        val issue = mkTodoIssue("i1", List("scala"), "ws-1")
        val alice = mkAgent("alice", List("scala"))
        val ws    = mkWorkspace("ws-1")
        for
          cfg     <- StubConfigRepository.make(Map(AutoDispatcher.enabledSettingKey -> "true"))
          iRepo   <- StubIssueRepository.make(List(issue))
          aRepo   <- StubAgentRepository.make(List(alice))
          wsRepo  <- StubWorkspaceRepository.make(workspaces = List(ws), runs = Nil)
          runSvc  <- StubWorkspaceRunService.make
          actHub  <- StubActivityHub.make
          govSvc  <- StubGovernancePolicyService.make(StubGovernancePolicyService.deny("test denial"))
          d       <- makeDispatcher(cfg, iRepo, aRepo, wsRepo, runSvc, actHub, stubPoolManager(5), govSvc)
          count   <- d.dispatchOnce
          assigns <- runSvc.assignments
        yield
          assertTrue(count == 0) &&
          assertTrue(assigns.isEmpty)
      },

      test("returns 0 immediately when issues.autoDispatch.enabled is false") {
        val issue = mkTodoIssue("i1", List("scala"), "ws-1")
        val alice = mkAgent("alice", List("scala"))
        val ws    = mkWorkspace("ws-1")
        for
          cfg     <- StubConfigRepository.make(Map(AutoDispatcher.enabledSettingKey -> "false"))
          iRepo   <- StubIssueRepository.make(List(issue))
          aRepo   <- StubAgentRepository.make(List(alice))
          wsRepo  <- StubWorkspaceRepository.make(workspaces = List(ws), runs = Nil)
          runSvc  <- StubWorkspaceRunService.make
          actHub  <- StubActivityHub.make
          govSvc  <- StubGovernancePolicyService.make()
          d       <- makeDispatcher(cfg, iRepo, aRepo, wsRepo, runSvc, actHub, stubPoolManager(5), govSvc)
          count   <- d.dispatchOnce
          assigns <- runSvc.assignments
        yield
          assertTrue(count == 0) &&
          assertTrue(assigns.isEmpty)
      },
    )
