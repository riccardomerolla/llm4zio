package governance.control

import java.time.Instant

import zio.*
import zio.test.*

import governance.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }
import workspace.entity.{ Workspace, WorkspaceEvent, WorkspaceRepository, WorkspaceRun, WorkspaceRunEvent }

object GovernancePolicyServiceSpec extends ZIOSpecDefault:

  private val now        = Instant.parse("2026-03-26T11:00:00Z")
  private val projectId  = ProjectId("project-a")
  private val policyId   = GovernancePolicyId("policy-1")
  private val workspaceA = "ws-a"

  private val dispatchTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.Todo,
    to = GovernanceLifecycleStage.InProgress,
    action = GovernanceLifecycleAction.Dispatch,
  )

  private def makePolicy(
    rules: List[GovernanceTransitionRule] = Nil
  ): GovernancePolicy =
    GovernancePolicy(
      id = policyId,
      projectId = projectId,
      name = "Policy A",
      version = 1,
      transitionRules = rules,
      isDefault = false,
      createdAt = now,
      updatedAt = now,
    )

  private def makeWorkspace(): Workspace =
    Workspace(
      id = workspaceA,
      projectId = projectId,
      name = "Workspace A",
      localPath = "/tmp/ws-a",
      defaultAgent = None,
      description = None,
      enabled = true,
      runMode = workspace.entity.RunMode.Host,
      cliTool = "codex",
      createdAt = now,
      updatedAt = now,
    )

  // ─── Stubs ────────────────────────────────────────────────────────────────

  final class StubWorkspaceRepository(workspaces: List[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(workspaces)
    override def listByProject(pid: ProjectId): IO[PersistenceError, List[Workspace]]           =
      ZIO.succeed(workspaces.filter(_.projectId == pid))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       =
      ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  final class StubGovernancePolicyRepository(policy: Option[GovernancePolicy])
    extends GovernancePolicyRepository:
    override def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]            = ZIO.unit
    override def get(id: GovernancePolicyId): IO[PersistenceError, GovernancePolicy]         =
      policy.fold(ZIO.fail(PersistenceError.NotFound("governance_policy", id.value)))(ZIO.succeed)
    override def getActiveByProject(pid: ProjectId): IO[PersistenceError, GovernancePolicy]  =
      policy
        .filter(!_.archivedAt.isDefined)
        .fold(ZIO.fail(PersistenceError.NotFound("governance_policy", pid.value)))(ZIO.succeed)
    override def listByProject(pid: ProjectId): IO[PersistenceError, List[GovernancePolicy]] =
      ZIO.succeed(policy.toList)
    override def list: IO[PersistenceError, List[GovernancePolicy]]                          =
      ZIO.succeed(policy.toList)

  private def makeService(
    workspaces: List[Workspace],
    policy: Option[GovernancePolicy],
  ): GovernancePolicyServiceLive =
    GovernancePolicyServiceLive(
      workspaceRepository = StubWorkspaceRepository(workspaces),
      policyRepository = StubGovernancePolicyRepository(policy),
      engine = GovernancePolicyEngineLive(),
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("GovernancePolicyServiceSpec")(
      test("resolves active policy when workspace belongs to project") {
        val policy  = makePolicy()
        val service = makeService(List(makeWorkspace()), Some(policy))
        for
          resolved <- service.resolvePolicyForWorkspace(workspaceA)
        yield assertTrue(resolved.id == policyId, resolved.projectId == projectId)
      },
      test("returns noOp when workspace has no associated project") {
        val service = makeService(workspaces = Nil, policy = None)
        for
          resolved <- service.resolvePolicyForWorkspace("ws-unknown")
        yield assertTrue(resolved.isDefault, resolved.id == GovernancePolicyId("governance-default"))
      },
      test("returns noOp when project exists but has no active policy") {
        val service = makeService(List(makeWorkspace()), policy = None)
        for
          resolved <- service.resolvePolicyForWorkspace(workspaceA)
        yield assertTrue(resolved.isDefault)
      },
      test("returns noOp when project's only policy is archived") {
        val archived = makePolicy().copy(archivedAt = Some(now))
        val service  = makeService(List(makeWorkspace()), policy = Some(archived))
        for
          resolved <- service.resolvePolicyForWorkspace(workspaceA)
        yield assertTrue(resolved.isDefault)
      },
      test("evaluateForWorkspace blocks when required gate is unsatisfied") {
        val policy  = makePolicy(rules =
          List(
            GovernanceTransitionRule(
              transition = dispatchTransition,
              requiredGates = List(GovernanceGate.SpecReview),
            )
          )
        )
        val service = makeService(List(makeWorkspace()), Some(policy))
        for
          decision <- service.evaluateForWorkspace(
                        workspaceA,
                        GovernanceEvaluationContext(
                          issueType = "task",
                          transition = dispatchTransition,
                        ),
                      )
        yield assertTrue(
          !decision.allowed,
          decision.missingGates == Set(GovernanceGate.SpecReview),
        )
      },
      test("evaluateForWorkspace allows when required gate is satisfied") {
        val policy  = makePolicy(rules =
          List(
            GovernanceTransitionRule(
              transition = dispatchTransition,
              requiredGates = List(GovernanceGate.SpecReview),
            )
          )
        )
        val service = makeService(List(makeWorkspace()), Some(policy))
        for
          decision <- service.evaluateForWorkspace(
                        workspaceA,
                        GovernanceEvaluationContext(
                          issueType = "task",
                          transition = dispatchTransition,
                          satisfiedGates = Set(GovernanceGate.SpecReview),
                        ),
                      )
        yield assertTrue(decision.allowed, decision.missingGates.isEmpty)
      },
    )
