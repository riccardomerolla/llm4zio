package shared.store

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.service.LifecycleCommand
import issues.entity.AgentIssueRow
import issues.entity.api.{ IssuePriority, IssueStatus }

object StoreIsolationSpec extends ZIOSpecDefault:

  private type Env = DataStoreService & ConfigStoreModule.ConfigStoreService

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("store-isolation-spec")).orDie
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

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, Env] =
    ZLayer.make[Env](
      ZLayer.succeed(
        StoreConfig(
          configStorePath = path.resolve("config-store").toString,
          dataStorePath = path.resolve("data-store").toString,
        )
      ),
      ConfigStoreModule.live,
      DataStoreModule.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StoreIsolationSpec")(
      test("config and data stores remain isolated in one runtime and after checkpoint") {
        withTempDir { dir =>
          val now = Instant.parse("2026-02-22T08:20:00Z")

          (for
            config <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            data   <- ZIO.service[DataStoreService]
            _      <- config.store("setting:isolation", "ok")
            _      <- data.store(
                        "issue:1",
                        AgentIssueRow(
                          id = "1",
                          runId = None,
                          conversationId = None,
                          title = "isolation",
                          description = "check",
                          issueType = "task",
                          tags = None,
                          preferredAgent = None,
                          contextPath = None,
                          sourceFolder = None,
                          priority = IssuePriority.Medium.toString,
                          status = IssueStatus.Backlog.toString,
                          assignedAgent = None,
                          assignedAt = None,
                          completedAt = None,
                          errorMessage = None,
                          resultData = None,
                          createdAt = now,
                          updatedAt = now,
                        ),
                      )
            _      <- config.rawStore.maintenance(LifecycleCommand.Checkpoint)
            _      <- data.rawStore.maintenance(LifecycleCommand.Checkpoint)
            cKeys  <- config.rawStore.streamKeys[String].runCollect.map(_.toSet)
            dKeys  <- data.rawStore.streamKeys[String].runCollect.map(_.toSet)
          yield assertTrue(
            cKeys.contains("setting:isolation"),
            !cKeys.contains("issue:1"),
            dKeys.contains("issue:1"),
            !dKeys.contains("setting:isolation"),
          )).provideLayer(layerFor(dir))
        }
      }
    )
