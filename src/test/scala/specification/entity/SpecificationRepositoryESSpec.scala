package specification.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids.{ IssueId, SpecificationId }
import shared.store.{ DataStoreModule, EventStore, StoreConfig }

object SpecificationRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreModule.DataStoreService & EventStore[SpecificationId, SpecificationEvent] & SpecificationRepository

  private val author = SpecificationAuthor(SpecificationAuthorKind.Agent, "planner", "Planner")

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("specification-repo-es-spec")).orDie
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
      SpecificationEventStoreES.live,
      SpecificationRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SpecificationRepositoryESSpec")(
      test("append get list and diff preserve revision history") {
        withTempDir { path =>
          val specId = SpecificationId("spec-1")
          val now    = Instant.parse("2026-03-26T12:10:00Z")
          (for
            repo <- ZIO.service[SpecificationRepository]
            _    <- repo.append(
                      SpecificationEvent.Created(
                        specificationId = specId,
                        title = "Planner Spec",
                        content = "v1",
                        author = author,
                        status = SpecificationStatus.Draft,
                        linkedPlanRef = Some("planner:1"),
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      SpecificationEvent.Revised(
                        specificationId = specId,
                        version = 2,
                        title = "Planner Spec",
                        beforeContent = "v1",
                        afterContent = "v2",
                        author = author,
                        status = SpecificationStatus.InRefinement,
                        linkedPlanRef = Some("planner:1"),
                        occurredAt = now.plusSeconds(10),
                      )
                    )
            _    <- repo.append(
                      SpecificationEvent.IssuesLinked(specId, List(IssueId("issue-1")), now.plusSeconds(20))
                    )
            got  <- repo.get(specId)
            all  <- repo.list
            diff <- repo.diff(specId, 1, 2)
          yield assertTrue(
            got.version == 2,
            got.linkedIssueIds == List(IssueId("issue-1")),
            all.map(_.id) == List(specId),
            diff.beforeContent == "v1",
            diff.afterContent == "v2",
          )).provideLayer(layerFor(path))
        }
      }
    ) @@ TestAspect.sequential
