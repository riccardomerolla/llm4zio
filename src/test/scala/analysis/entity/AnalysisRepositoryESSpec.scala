package analysis.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.{ DataStoreModule, EventStore, StoreConfig }

object AnalysisRepositoryESSpec extends ZIOSpecDefault:

  private type Env =
    DataStoreModule.DataStoreService & EventStore[Ids.AnalysisDocId, AnalysisEvent] & AnalysisRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("analysis-repo-es-spec")).orDie
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
      AnalysisEventStoreES.live,
      AnalysisRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisRepositoryESSpec")(
      test("append/replay/list by workspace and type for analysis documents") {
        withTempDir { path =>
          val codeReviewId = Ids.AnalysisDocId("analysis-1")
          val securityId   = Ids.AnalysisDocId("analysis-2")
          val now          = Instant.parse("2026-03-13T12:45:00Z")
          (for
            repo <- ZIO.service[AnalysisRepository]
            _    <- repo.append(
                      AnalysisEvent.AnalysisCreated(
                        docId = codeReviewId,
                        workspaceId = "ws-1",
                        analysisType = AnalysisType.CodeReview,
                        content = "Initial review",
                        filePath = ".llm4zio/analysis/code-review.md",
                        generatedBy = Ids.AgentId("reviewer"),
                        occurredAt = now,
                      )
                    )
            _    <- repo.append(
                      AnalysisEvent.AnalysisUpdated(
                        docId = codeReviewId,
                        content = "Updated review with blocking findings",
                        updatedAt = now.plusSeconds(5),
                      )
                    )
            _    <- repo.append(
                      AnalysisEvent.AnalysisCreated(
                        docId = securityId,
                        workspaceId = "ws-1",
                        analysisType = AnalysisType.Security,
                        content = "Security scan",
                        filePath = ".llm4zio/analysis/security.md",
                        generatedBy = Ids.AgentId("security-bot"),
                        occurredAt = now.plusSeconds(10),
                      )
                    )
            doc  <- repo.get(codeReviewId)
            byWs <- repo.listByWorkspace("ws-1")
            byTy <- repo.listByType(AnalysisType.CodeReview)
          yield assertTrue(
            doc.content == "Updated review with blocking findings",
            doc.updatedAt == now.plusSeconds(5),
            byWs.map(_.id) == List(codeReviewId, securityId),
            byTy.map(_.id) == List(codeReviewId),
          )).provideLayer(layerFor(path))
        }
      },
      test("deleted analysis documents are removed from projection reads") {
        withTempDir { path =>
          val id  = Ids.AnalysisDocId("analysis-3")
          val now = Instant.parse("2026-03-13T12:50:00Z")
          (for
            repo    <- ZIO.service[AnalysisRepository]
            _       <- repo.append(
                         AnalysisEvent.AnalysisCreated(
                           docId = id,
                           workspaceId = "ws-2",
                           analysisType = AnalysisType.Architecture,
                           content = "Architecture notes",
                           filePath = ".llm4zio/analysis/architecture.md",
                           generatedBy = Ids.AgentId("architect"),
                           occurredAt = now,
                         )
                       )
            _       <- repo.append(AnalysisEvent.AnalysisDeleted(id, now.plusSeconds(10)))
            result  <- repo.get(id).either
            visible <- repo.listByWorkspace("ws-2")
          yield assertTrue(
            result == Left(shared.errors.PersistenceError.NotFound("analysis_doc", id.value)),
            visible.isEmpty,
          )).provideLayer(layerFor(path))
        }
      },
    ) @@ TestAspect.sequential
