package governance.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ GovernancePolicyId, ProjectId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

object GovernancePolicyRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreService & EventStore[
      GovernancePolicyId,
      GovernancePolicyEvent,
    ] & GovernancePolicyRepository

  private val dispatchTransition = GovernanceTransition(
    from = GovernanceLifecycleStage.Todo,
    to = GovernanceLifecycleStage.InProgress,
    action = GovernanceLifecycleAction.Dispatch,
  )

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("governance-policy-repo-es-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(path =>
            val _ = Files.deleteIfExists(path)
          )
      }.ignore
    )(use)

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, Env] =
    ZLayer.make[Env](
      ZLayer.succeed(StoreConfig(path.resolve("config").toString, path.resolve("data").toString)),
      DataStoreModule.live,
      GovernancePolicyEventStoreES.live,
      GovernancePolicyRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GovernancePolicyRepositoryESSpec")(
      test("append and getActiveByProject returns the highest active version") {
        withTempDir { path =>
          val projectId = ProjectId("project-1")
          val policyId1 = GovernancePolicyId("policy-1")
          val policyId2 = GovernancePolicyId("policy-2")
          val now       = Instant.parse("2026-03-26T10:00:00Z")

          (for
            repo   <- ZIO.service[GovernancePolicyRepository]
            _      <- repo.append(
                        GovernancePolicyEvent.PolicyCreated(
                          policyId = policyId1,
                          projectId = projectId,
                          name = "Policy v1",
                          version = 1,
                          transitionRules = List(
                            GovernanceTransitionRule(
                              transition = dispatchTransition,
                              requiredGates = List(GovernanceGate.SpecReview),
                            )
                          ),
                          daemonTriggers = Nil,
                          escalationRules = Nil,
                          completionCriteria = Nil,
                          isDefault = false,
                          occurredAt = now,
                        )
                      )
            _      <- repo.append(
                        GovernancePolicyEvent.PolicyCreated(
                          policyId = policyId2,
                          projectId = projectId,
                          name = "Policy v2",
                          version = 2,
                          transitionRules = List(
                            GovernanceTransitionRule(
                              transition = dispatchTransition,
                              requiredGates = List(GovernanceGate.SpecReview, GovernanceGate.PlanningReview),
                              requireHumanApproval = true,
                            )
                          ),
                          daemonTriggers = List(
                            GovernanceDaemonTrigger(
                              id = "dispatch-daemon",
                              transition = dispatchTransition,
                              agentName = "codex",
                            )
                          ),
                          escalationRules = Nil,
                          completionCriteria = List(
                            GovernanceCompletionCriteria(
                              issueType = "feature",
                              requiredGates = List(GovernanceGate.CodeReview, GovernanceGate.CiPassed),
                            )
                          ),
                          isDefault = false,
                          occurredAt = now.plusSeconds(30),
                        )
                      )
            active <- repo.getActiveByProject(projectId)
            all    <- repo.listByProject(projectId)
          yield assertTrue(
            active.id == policyId2,
            active.version == 2,
            all.map(_.id) == List(policyId1, policyId2),
          )).provideLayer(layerFor(path))
        }
      },
      test("archived policies are excluded from active lookups") {
        withTempDir { path =>
          val projectId = ProjectId("project-2")
          val policyId  = GovernancePolicyId("policy-archive")
          val now       = Instant.parse("2026-03-26T10:05:00Z")

          (for
            repo   <- ZIO.service[GovernancePolicyRepository]
            _      <- repo.append(
                        GovernancePolicyEvent.PolicyCreated(
                          policyId = policyId,
                          projectId = projectId,
                          name = "Temporary Policy",
                          version = 1,
                          transitionRules = Nil,
                          daemonTriggers = Nil,
                          escalationRules = Nil,
                          completionCriteria = Nil,
                          isDefault = false,
                          occurredAt = now,
                        )
                      )
            _      <- repo.append(
                        GovernancePolicyEvent.PolicyArchived(
                          policyId = policyId,
                          projectId = projectId,
                          archivedAt = now.plusSeconds(20),
                          occurredAt = now.plusSeconds(20),
                        )
                      )
            active <- repo.getActiveByProject(projectId).either
          yield assertTrue(active.isLeft)).provideLayer(layerFor(path))
        }
      },
    ) @@ TestAspect.sequential
