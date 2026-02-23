package memory

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import memory.control.{ EmbeddingService, MemoryRepositoryES }
import memory.entity.*
import shared.store.{ MemoryStoreModule, StoreConfig }

object MemoryRepositoryESSpec extends ZIOSpecDefault:

  private val userId: UserId       = UserId("web:42")
  private val sessionId: SessionId = SessionId("conv-1")

  private val mockEmbedding: ULayer[EmbeddingService] =
    ZLayer.succeed(
      new EmbeddingService:
        override def embed(text: String): IO[Throwable, Vector[Float]] =
          if text.contains("relevant") then ZIO.succeed(Vector(1.0f, 0.0f, 0.0f))
          else ZIO.succeed(Vector(0.0f, 1.0f, 0.0f))

        override def embedBatch(texts: List[String]): IO[Throwable, List[Vector[Float]]] =
          ZIO.foreach(texts)(embed)
    )

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("memory-repository-es-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path =>
              val _ = Files.deleteIfExists(path)
            )
      }.ignore
    )(use)

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, MemoryRepository] =
    (ZLayer.succeed(
      StoreConfig(
        configStorePath = path.resolve("config-store").toString,
        dataStorePath = path.resolve("data-store").toString,
      )
    ) >>> MemoryStoreModule.live) ++ mockEmbedding >>> ZLayer.fromZIO {
      for
        map <- MemoryStoreModule.memoryEntriesMap
        vsi <- ZIO.service[io.github.riccardomerolla.zio.eclipsestore.gigamap.vector.VectorIndexService]
        emb <- ZIO.service[EmbeddingService]
      yield MemoryRepositoryES(map, vsi, emb, dimension = 3)
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MemoryRepositoryESSpec")(
      test("save and searchRelevant return semantic match for same user and kind") {
        withTempDir { dir =>
          val now = Instant.parse("2026-02-19T17:10:00Z")

          val entry = MemoryEntry(
            id = MemoryId.make,
            userId = userId,
            sessionId = sessionId,
            text = "critical user preference",
            embedding = Vector(1.0f, 0.0f, 0.0f),
            tags = List("prefs", "priority"),
            kind = MemoryKind.Preference,
            createdAt = now,
            lastAccessedAt = now,
          )

          val program =
            for
              repository <- ZIO.service[MemoryRepository]
              _          <- repository.save(entry)
              hits       <- repository.searchRelevant(
                              userId = userId,
                              query = "relevant preference",
                              limit = 5,
                              filter = MemoryFilter(kind = Some(MemoryKind.Preference)),
                            )
            yield assertTrue(
              hits.nonEmpty,
              hits.head.entry.id == entry.id,
              hits.head.entry.userId == userId,
              hits.head.entry.kind == MemoryKind.Preference,
            )

          program.provideLayer(layerFor(dir))
        }
      }
    )
