package decision.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ DecisionId, IssueId }
import shared.store.{ DataStoreModule, DataStoreService, EventStore, StoreConfig }

object DecisionRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreService & EventStore[DecisionId, DecisionEvent] & DecisionRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("decision-repo-es-spec")).orDie
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
      DecisionEventStoreES.live,
      DecisionRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DecisionRepositoryESSpec")(
      test("append get list and history preserve decision lifecycle") {
        withTempDir { path =>
          val id  = DecisionId("decision-1")
          val now = Instant.parse("2026-03-26T12:10:00Z")
          (for
            repo <- ZIO.service[DecisionRepository]
            _    <- repo.append(
                      DecisionEvent.Created(
                        decisionId = id,
                        title = "Review issue #1",
                        context = "Need approval",
                        action = DecisionAction.ReviewIssue,
                        source = DecisionSource(
                          kind = DecisionSourceKind.IssueReview,
                          referenceId = "issue-1",
                          summary = "Review required",
                          issueId = Some(IssueId("issue-1")),
                        ),
                        urgency = DecisionUrgency.High,
                        deadlineAt = Some(now.plusSeconds(300)),
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      DecisionEvent.Resolved(
                        decisionId = id,
                        resolution = DecisionResolution(
                          kind = DecisionResolutionKind.Approved,
                          actor = "reviewer",
                          summary = "Approved",
                          respondedAt = now.plusSeconds(120),
                        ),
                        occurredAt = now.plusSeconds(120),
                      )
                    )
            got  <- repo.get(id)
            all  <- repo.list(DecisionFilter(limit = Int.MaxValue))
            hist <- repo.history(id)
          yield assertTrue(
            got.status == DecisionStatus.Resolved,
            all.map(_.id) == List(id),
            hist.size == 2,
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
