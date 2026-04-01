package project.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.ids.Ids.ProjectId
import shared.store.{ DataStoreModule, StoreConfig }

object ProjectRepositorySpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("project-repo-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { p =>
              val _ = Files.deleteIfExists(p)
            }
      }.ignore
    )(use)

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError, DataStoreModule.DataStoreService] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live

  private val now = Instant.parse("2026-03-13T09:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ProjectRepositorySpec")(
      test("append create event and load project") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = ProjectRepositoryES(svc)
            _   <- repo.append(
                     ProjectEvent.ProjectCreated(
                       projectId = ProjectId("proj-1"),
                       name = "Platform",
                       description = Some("Shared runtime work"),
                       occurredAt = now,
                     )
                   )
            got <- repo.get(ProjectId("proj-1"))
          yield assertTrue(
            got.exists(_.name == "Platform")
          )).provideLayer(layerFor(dir))
        }
      },
      test("update events rebuild project state") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = ProjectRepositoryES(svc)
            _   <- repo.append(
                     ProjectEvent.ProjectCreated(
                       projectId = ProjectId("proj-1"),
                       name = "Platform",
                       description = None,
                       occurredAt = now,
                     )
                   )
            _   <- repo.append(
                     ProjectEvent.ProjectUpdated(
                       projectId = ProjectId("proj-1"),
                       name = "Platform Core",
                       description = Some("Updated"),
                       settings = ProjectSettings(
                         defaultAgent = Some("codex"),
                         mergePolicy = MergePolicy(requireCi = true, ciCommand = Some("sbt test")),
                       ),
                       occurredAt = now.plusSeconds(10),
                     )
                   )
            got <- repo.get(ProjectId("proj-1"))
          yield assertTrue(
            got.exists(_.name == "Platform Core"),
            got.exists(_.settings.mergePolicy.requireCi),
          )).provideLayer(layerFor(dir))
        }
      },
      test("list returns projects sorted by name") {
        withTempDir { dir =>
          (for
            svc  <- ZIO.service[DataStoreModule.DataStoreService]
            repo  = ProjectRepositoryES(svc)
            _    <- repo.append(ProjectEvent.ProjectCreated(ProjectId("proj-2"), "Zulu", None, now))
            _    <- repo.append(ProjectEvent.ProjectCreated(ProjectId("proj-1"), "Alpha", None, now.plusSeconds(1)))
            list <- repo.list
          yield assertTrue(list.map(_.name) == List("Alpha", "Zulu"))).provideLayer(layerFor(dir))
        }
      },
      test("delete removes project from list and get") {
        withTempDir { dir =>
          (for
            svc  <- ZIO.service[DataStoreModule.DataStoreService]
            repo  = ProjectRepositoryES(svc)
            _    <- repo.append(ProjectEvent.ProjectCreated(ProjectId("proj-1"), "Platform", None, now))
            _    <- repo.delete(ProjectId("proj-1"))
            got  <- repo.get(ProjectId("proj-1"))
            list <- repo.list
          yield assertTrue(got.isEmpty, list.isEmpty)).provideLayer(layerFor(dir))
        }
      },
    )
