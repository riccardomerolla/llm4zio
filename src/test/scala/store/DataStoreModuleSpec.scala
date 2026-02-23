package store

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.store.*

object DataStoreModuleSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("data-store-module-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { path =>
              val _ = Files.deleteIfExists(path)
            }
      }.ignore
    )(use)

  private def layerFor(
    dataDir: Path
  ): ZLayer[
    Any,
    EclipseStoreError | GigaMapError,
    DataStoreModule.DataStoreService,
  ] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DataStoreModuleSpec")(
      test("taskRuns map supports put/get round-trip") {
        withTempDir { dir =>
          val row = TaskRunRow(
            id = "run-1",
            sourceDir = "./in",
            outputDir = "./out",
            status = "running",
            workflowId = Some("wf-1"),
            currentPhase = Some("analysis"),
            errorMessage = None,
            startedAt = Instant.parse("2026-02-19T10:00:00Z"),
            completedAt = None,
            totalFiles = 10,
            processedFiles = 4,
            successfulConversions = 3,
            failedConversions = 1,
          )
          (for
            data   <- ZIO.service[DataStoreModule.DataStoreService]
            _      <- data.store.store("run:run-1", row)
            loaded <- data.store.fetch[String, TaskRunRow]("run:run-1")
          yield assertTrue(loaded.contains(row))).provideLayer(layerFor(dir))
        }
      },
      test("conversations map supports put/get round-trip") {
        withTempDir { dir =>
          val row = ConversationRow(
            id = "1",
            title = "Conversation",
            description = Some("desc"),
            channelName = Some("telegram"),
            status = "active",
            createdAt = Instant.parse("2026-02-19T10:00:00Z"),
            updatedAt = Instant.parse("2026-02-19T10:01:00Z"),
            runId = Some("1"),
            createdBy = Some("system"),
          )
          (for
            data   <- ZIO.service[DataStoreModule.DataStoreService]
            _      <- data.store.store("conv:1", row)
            loaded <- data.store.fetch[String, ConversationRow]("conv:1")
          yield assertTrue(loaded.contains(row))).provideLayer(layerFor(dir))
        }
      },
    ) @@ TestAspect.sequential
