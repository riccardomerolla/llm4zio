package plan.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

object PlanRepositoryESSpec extends ZIOSpecDefault:

  private type Env = DataStoreService & EventStore[PlanId, PlanEvent] & PlanRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("plan-repo-es-spec")).orDie
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
      PlanEventStoreES.live,
      PlanRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PlanRepositoryESSpec")(
      test("append get history and list preserve versions") {
        withTempDir { path =>
          val planId = PlanId("plan-1")
          val now    = Instant.parse("2026-03-26T12:10:00Z")
          (for
            repo <- ZIO.service[PlanRepository]
            _    <- repo.append(
                      PlanEvent.Created(
                        planId = planId,
                        conversationId = 42L,
                        workspaceId = Some("ws-1"),
                        specificationId = Some(SpecificationId("spec-1")),
                        summary = "Planner plan",
                        rationale = "Initial rationale",
                        drafts = List(PlanTaskDraft("issue-1", "Model", "Build the model")),
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      PlanEvent.TasksCreated(
                        planId = planId,
                        issueIds = List(IssueId("issue-1")),
                        occurredAt = now.plusSeconds(10),
                      )
                    )
            _    <- repo.append(
                      PlanEvent.Revised(
                        planId = planId,
                        version = 2,
                        workspaceId = Some("ws-1"),
                        specificationId = Some(SpecificationId("spec-1")),
                        summary = "Planner plan revised",
                        rationale = "Added rollout",
                        drafts = List(
                          PlanTaskDraft("issue-1", "Model", "Build the model"),
                          PlanTaskDraft("issue-2", "Rollout", "Ship the change"),
                        ),
                        occurredAt = now.plusSeconds(20),
                      )
                    )
            got  <- repo.get(planId)
            all  <- repo.list
            hist <- repo.history(planId)
          yield assertTrue(
            got.version == 2,
            got.versions.map(_.version) == List(1, 2),
            got.linkedIssueIds == List(IssueId("issue-1")),
            all.map(_.id) == List(planId),
            hist.size == 3,
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
