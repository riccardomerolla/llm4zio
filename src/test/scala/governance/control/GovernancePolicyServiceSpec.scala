package governance.control

import java.time.Instant

import zio.*
import zio.test.*

import governance.entity.*
import project.entity.{ Project, ProjectEvent, ProjectRepository, ProjectSettings }
import shared.errors.PersistenceError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }

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

  private def makeProject(wsIds: List[String] = List(workspaceA)): Project =
    Project(
      id = projectId,
      name = "Project A",
      description = None,
      workspaceIds = wsIds,
      settings = ProjectSettings(),
      createdAt = now,
      updatedAt = now,
    )

  // ─── Stubs ────────────────────────────────────────────────────────────────

  final class StubProjectRepository(projects: List[Project]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit]   = ZIO.unit
    override def list: IO[PersistenceError, List[Project]]                 = ZIO.succeed(projects)
    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ZIO.succeed(projects.find(_.id == id))
    override def delete(id: ProjectId): IO[PersistenceError, Unit]         = ZIO.unit

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

  private def makeService(
    projects: List[Project],
    policy: Option[GovernancePolicy],
  ): GovernancePolicyServiceLive =
    GovernancePolicyServiceLive(
      projectRepository = StubProjectRepository(projects),
      policyRepository = StubGovernancePolicyRepository(policy),
      engine = GovernancePolicyEngineLive(),
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] =
    suite("GovernancePolicyServiceSpec")(
      test("resolves active policy when workspace belongs to project") {
        val policy  = makePolicy()
        val service = makeService(List(makeProject()), Some(policy))
        for
          resolved <- service.resolvePolicyForWorkspace(workspaceA)
        yield assertTrue(resolved.id == policyId, resolved.projectId == projectId)
      },
      test("returns noOp when workspace has no associated project") {
        val service = makeService(projects = Nil, policy = None)
        for
          resolved <- service.resolvePolicyForWorkspace("ws-unknown")
        yield assertTrue(resolved.isDefault, resolved.id == GovernancePolicyId("governance-default"))
      },
      test("returns noOp when project exists but has no active policy") {
        val service = makeService(List(makeProject()), policy = None)
        for
          resolved <- service.resolvePolicyForWorkspace(workspaceA)
        yield assertTrue(resolved.isDefault)
      },
      test("returns noOp when project's only policy is archived") {
        val archived = makePolicy().copy(archivedAt = Some(now))
        val service  = makeService(List(makeProject()), policy = Some(archived))
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
        val service = makeService(List(makeProject()), Some(policy))
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
        val service = makeService(List(makeProject()), Some(policy))
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
