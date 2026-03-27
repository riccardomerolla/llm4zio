package evolution.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.WorkflowDefinition
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ EvolutionProposalId, ProjectId }
import shared.store.{ DataStoreModule, EventStore, StoreConfig }

object EvolutionProposalRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreModule.DataStoreService & EventStore[
      EvolutionProposalId,
      EvolutionProposalEvent,
    ] & EvolutionProposalRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("evolution-repo-es-spec")).orDie
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
      EvolutionProposalEventStoreES.live,
      EvolutionProposalRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EvolutionProposalRepositoryESSpec")(
      test("append get list and history preserve proposal lifecycle") {
        withTempDir { path =>
          val id     = EvolutionProposalId("proposal-1")
          val now    = Instant.parse("2026-03-26T12:30:00Z")
          val target = EvolutionTarget.WorkflowDefinitionTarget(
            projectId = ProjectId("project-1"),
            workflow = WorkflowDefinition(name = "quality", steps = List("chat"), isBuiltin = false),
          )
          (for
            repo <- ZIO.service[EvolutionProposalRepository]
            _    <- repo.append(
                      EvolutionProposalEvent.Proposed(
                        proposalId = id,
                        projectId = ProjectId("project-1"),
                        title = "Quality workflow",
                        rationale = "Track platform changes",
                        target = target,
                        template = None,
                        proposedBy = "agent",
                        summary = "Create",
                        decisionId = None,
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      EvolutionProposalEvent.Approved(
                        proposalId = id,
                        projectId = ProjectId("project-1"),
                        decisionId = shared.ids.Ids.DecisionId("decision-1"),
                        approvedBy = "human",
                        summary = "ok",
                        occurredAt = now.plusSeconds(30),
                      )
                    )
            got  <- repo.get(id)
            all  <- repo.list(EvolutionProposalFilter(projectId = Some(ProjectId("project-1"))))
            hist <- repo.history(id)
          yield assertTrue(
            got.status == EvolutionProposalStatus.Approved,
            all.map(_.id) == List(id),
            hist.size == 2,
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
