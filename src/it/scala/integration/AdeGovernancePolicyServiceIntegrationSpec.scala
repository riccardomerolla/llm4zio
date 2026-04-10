package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import governance.control.*
import governance.entity.*
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.errors.PersistenceError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }
import shared.testfixtures.StubWorkspaceRepository
import workspace.entity.*

/** Integration test: `GovernancePolicyService` with real EclipseStore repositories.
  *
  * Validates the full lookup chain (workspace → project → active policy → engine evaluation) against real ES
  * persistence, catching serialization and event-replay issues not visible in stub-based unit tests.
  */
object AdeGovernancePolicyServiceIntegrationSpec extends ZIOSpecDefault:

  private val now       = Instant.parse("2026-03-26T11:00:00Z")
  private val projectId = ProjectId("project-a")
  private val wsId      = "ws-a"

  private val dispatchTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.Todo,
    to = GovernanceLifecycleStage.InProgress,
    action = GovernanceLifecycleAction.Dispatch,
  )

  // ─── Test fixtures ────────────────────────────────────────────────────────

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("ade-governance-it-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { p =>
            val _ = Files.deleteIfExists(p)
          }
      }.ignore
    )(use)

  private type EsEnv =
    DataStoreService &
      EventStore[GovernancePolicyId, GovernancePolicyEvent] &
      GovernancePolicyRepository &
      GovernancePolicyEngine

  private def esLayer(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, EsEnv] =
    ZLayer.make[EsEnv](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      GovernancePolicyEventStoreES.live,
      GovernancePolicyRepositoryES.live,
      GovernancePolicyEngine.live,
    )

  /** Stub workspace repository that maps the given workspace IDs to workspaces linked to `projectId`. */
  private def stubWorkspaceRepo(workspaceIds: List[String]): WorkspaceRepository =
    val workspaces = workspaceIds.map(id =>
      Workspace(
        id = id,
        projectId = projectId,
        name = id,
        localPath = s"/tmp/$id",
        defaultAgent = None,
        description = None,
        enabled = true,
        runMode = RunMode.Host,
        cliTool = "codex",
        createdAt = now,
        updatedAt = now,
      )
    )
    StubWorkspaceRepository.of(workspaces*)

  private def makePolicyCreated(
    policyId: GovernancePolicyId,
    version: Int,
    rules: List[GovernanceTransitionRule] = Nil,
  ): GovernancePolicyEvent.PolicyCreated =
    GovernancePolicyEvent.PolicyCreated(
      policyId = policyId,
      projectId = projectId,
      name = s"Policy v$version",
      version = version,
      transitionRules = rules,
      daemonTriggers = Nil,
      escalationRules = Nil,
      completionCriteria = Nil,
      isDefault = false,
      occurredAt = now,
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AdeGovernancePolicyServiceIntegrationSpec")(
      test("resolvePolicyForWorkspace returns active policy when workspace is linked to project") {
        withTempDir { path =>
          val policyId = GovernancePolicyId("policy-1")
          (for
            repo     <- ZIO.service[GovernancePolicyRepository]
            engine   <- ZIO.service[GovernancePolicyEngine]
            _        <- repo.append(makePolicyCreated(policyId, version = 1))
            service   = GovernancePolicyServiceLive(stubWorkspaceRepo(List(wsId)), repo, engine)
            resolved <- service.resolvePolicyForWorkspace(wsId)
          yield assertTrue(resolved.id == policyId, !resolved.isDefault))
            .provideLayer(esLayer(path))
        }
      },
      test("resolvePolicyForWorkspace returns noOp when workspace has no associated project") {
        withTempDir { path =>
          val policyId = GovernancePolicyId("policy-2")
          (for
            repo     <- ZIO.service[GovernancePolicyRepository]
            engine   <- ZIO.service[GovernancePolicyEngine]
            _        <- repo.append(makePolicyCreated(policyId, version = 1))
            service   = GovernancePolicyServiceLive(stubWorkspaceRepo(Nil), repo, engine)
            resolved <- service.resolvePolicyForWorkspace("ws-other")
          yield assertTrue(resolved.isDefault))
            .provideLayer(esLayer(path))
        }
      },
      test("resolvePolicyForWorkspace returns noOp when all policies are archived") {
        withTempDir { path =>
          val policyId = GovernancePolicyId("policy-3")
          (for
            repo     <- ZIO.service[GovernancePolicyRepository]
            engine   <- ZIO.service[GovernancePolicyEngine]
            _        <- repo.append(makePolicyCreated(policyId, version = 1))
            _        <- repo.append(
                          GovernancePolicyEvent.PolicyArchived(
                            policyId = policyId,
                            projectId = projectId,
                            archivedAt = now,
                            occurredAt = now,
                          )
                        )
            service   = GovernancePolicyServiceLive(stubWorkspaceRepo(List(wsId)), repo, engine)
            resolved <- service.resolvePolicyForWorkspace(wsId)
          yield assertTrue(resolved.isDefault))
            .provideLayer(esLayer(path))
        }
      },
      test("evaluateForWorkspace returns blocked when required gate is not satisfied") {
        withTempDir { path =>
          val policyId = GovernancePolicyId("policy-4")
          val rule     = GovernanceTransitionRule(
            transition = dispatchTransition,
            requiredGates = List(GovernanceGate.SpecReview),
          )
          (for
            repo     <- ZIO.service[GovernancePolicyRepository]
            engine   <- ZIO.service[GovernancePolicyEngine]
            _        <- repo.append(makePolicyCreated(policyId, version = 1, rules = List(rule)))
            service   = GovernancePolicyServiceLive(stubWorkspaceRepo(List(wsId)), repo, engine)
            decision <- service.evaluateForWorkspace(
                          wsId,
                          GovernanceEvaluationContext(issueType = "task", transition = dispatchTransition),
                        )
          yield assertTrue(
            !decision.allowed,
            decision.missingGates == Set(GovernanceGate.SpecReview),
          )).provideLayer(esLayer(path))
        }
      },
      test("evaluateForWorkspace returns allowed when gate is satisfied") {
        withTempDir { path =>
          val policyId = GovernancePolicyId("policy-5")
          val rule     = GovernanceTransitionRule(
            transition = dispatchTransition,
            requiredGates = List(GovernanceGate.SpecReview),
          )
          (for
            repo     <- ZIO.service[GovernancePolicyRepository]
            engine   <- ZIO.service[GovernancePolicyEngine]
            _        <- repo.append(makePolicyCreated(policyId, version = 1, rules = List(rule)))
            service   = GovernancePolicyServiceLive(stubWorkspaceRepo(List(wsId)), repo, engine)
            decision <- service.evaluateForWorkspace(
                          wsId,
                          GovernanceEvaluationContext(
                            issueType = "task",
                            transition = dispatchTransition,
                            satisfiedGates = Set(GovernanceGate.SpecReview),
                          ),
                        )
          yield assertTrue(decision.allowed, decision.missingGates.isEmpty))
            .provideLayer(esLayer(path))
        }
      },
    ) @@ sequential
