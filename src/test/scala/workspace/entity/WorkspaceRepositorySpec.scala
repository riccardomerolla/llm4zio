package workspace.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.store.*

object WorkspaceRepositorySpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("workspace-repo-spec")).orDie
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

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError, ConfigStoreModule.ConfigStoreService] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> ConfigStoreModule.live

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("gemini-cli"),
    description = None,
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private val sampleRun = WorkspaceRun(
    id = "run-1",
    workspaceId = "ws-1",
    issueRef = "#42",
    agentName = "gemini-cli",
    prompt = "fix it",
    conversationId = "conv-1",
    worktreePath = "/tmp/wt",
    branchName = "agent/42-run1abc",
    status = RunStatus.Pending,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("WorkspaceRepositorySpec")(
      // --- Task 2: codec round-trip via TypedStore ---
      test("Workspace round-trips through TypedStore") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            _      <- svc.store.store("workspace:ws-1", sampleWs)
            loaded <- svc.store.fetch[String, Workspace]("workspace:ws-1")
          yield assertTrue(loaded.contains(sampleWs))).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRun round-trips through TypedStore") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            _      <- svc.store.store("workspace-run:run-1", sampleRun)
            loaded <- svc.store.fetch[String, WorkspaceRun]("workspace-run:run-1")
          yield assertTrue(loaded.contains(sampleRun))).provideLayer(layerFor(dir))
        }
      },
      // --- Task 3: WorkspaceRepository CRUD ---
      test("WorkspaceRepository saves and lists workspaces") {
        withTempDir { dir =>
          (for
            svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo  = WorkspaceRepositoryES(svc)
            _    <- repo.save(sampleWs)
            list <- repo.list
          yield assertTrue(list.exists(_.id == "ws-1"))).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRepository get returns None for missing id") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo = WorkspaceRepositoryES(svc)
            got <- repo.get("missing")
          yield assertTrue(got.isEmpty)).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRepository delete removes entry") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo = WorkspaceRepositoryES(svc)
            _   <- repo.save(sampleWs)
            _   <- repo.delete("ws-1")
            got <- repo.get("ws-1")
          yield assertTrue(got.isEmpty)).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRepository saves and retrieves a WorkspaceRun") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo    = WorkspaceRepositoryES(svc)
            _      <- repo.saveRun(sampleRun)
            loaded <- repo.getRun("run-1")
          yield assertTrue(loaded.contains(sampleRun))).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRepository listRuns returns only runs for the given workspace") {
        withTempDir { dir =>
          val run2 = WorkspaceRun(
            "r2",
            "ws-2",
            "#2",
            "opencode",
            "p",
            "c2",
            "/wt2",
            "b2",
            RunStatus.Failed,
            Instant.parse("2026-02-24T10:00:00Z"),
            Instant.parse("2026-02-24T10:00:00Z"),
          )
          (for
            svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo  = WorkspaceRepositoryES(svc)
            _    <- repo.saveRun(sampleRun)
            _    <- repo.saveRun(run2)
            runs <- repo.listRuns("ws-1")
          yield assertTrue(runs.length == 1 && runs.head.id == "run-1")).provideLayer(layerFor(dir))
        }
      },
      test("WorkspaceRepository updateRunStatus changes status") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
            repo    = WorkspaceRepositoryES(svc)
            _      <- repo.saveRun(sampleRun)
            _      <- repo.updateRunStatus("run-1", RunStatus.Completed)
            loaded <- repo.getRun("run-1")
          yield assertTrue(loaded.exists(_.status == RunStatus.Completed))).provideLayer(layerFor(dir))
        }
      },
    )
