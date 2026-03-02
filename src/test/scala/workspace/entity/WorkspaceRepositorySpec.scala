package workspace.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.store.{ DataStoreModule, StoreConfig }

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

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError, DataStoreModule.DataStoreService] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> DataStoreModule.live

  private val now = Instant.parse("2026-02-24T10:00:00Z")

  private val createdWs = WorkspaceEvent.Created(
    workspaceId = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("gemini"),
    description = None,
    cliTool = "gemini",
    runMode = RunMode.Host,
    occurredAt = now,
  )

  private val createdDockerWs = WorkspaceEvent.Created(
    workspaceId = "ws-docker",
    name = "sandboxed-api",
    localPath = "/tmp/sandboxed-api",
    defaultAgent = Some("opencode"),
    description = None,
    cliTool = "opencode",
    runMode = RunMode.Docker("my-image:latest", Nil, mountWorktree = true, network = Some("none")),
    occurredAt = now,
  )

  private val assignedRun = WorkspaceRunEvent.Assigned(
    runId = "run-1",
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = "#42",
    agentName = "gemini",
    prompt = "fix it",
    conversationId = "conv-1",
    worktreePath = "/tmp/wt",
    branchName = "agent/42-run1abc",
    occurredAt = now,
  )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("WorkspaceRepositorySpec")(
      test("append Created event and get snapshot") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = WorkspaceRepositoryES(svc)
            _   <- repo.append(createdWs)
            got <- repo.get("ws-1")
          yield assertTrue(
            got.isDefined &&
            got.get.name == "my-api" &&
            got.get.cliTool == "gemini" &&
            got.get.enabled == true
          )).provideLayer(layerFor(dir))
        }
      },
      test("list returns all workspaces sorted by name") {
        withTempDir { dir =>
          (for
            svc  <- ZIO.service[DataStoreModule.DataStoreService]
            repo  = WorkspaceRepositoryES(svc)
            _    <- repo.append(createdWs)
            _    <- repo.append(createdDockerWs)
            list <- repo.list
          yield assertTrue(list.map(_.id).contains("ws-1") && list.map(_.id).contains("ws-docker")))
            .provideLayer(layerFor(dir))
        }
      },
      test("append Updated event changes fields") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = WorkspaceRepositoryES(svc)
            _   <- repo.append(createdWs)
            _   <- repo.append(
                     WorkspaceEvent.Updated(
                       workspaceId = "ws-1",
                       name = "my-api-v2",
                       localPath = "/tmp/my-api-v2",
                       defaultAgent = None,
                       description = Some("updated"),
                       cliTool = "claude",
                       runMode = RunMode.Host,
                       occurredAt = now.plusSeconds(1),
                     )
                   )
            got <- repo.get("ws-1")
          yield assertTrue(
            got.exists(_.name == "my-api-v2") &&
            got.exists(_.cliTool == "claude") &&
            got.exists(_.description.contains("updated"))
          )).provideLayer(layerFor(dir))
        }
      },
      test("delete removes workspace from list and get") {
        withTempDir { dir =>
          (for
            svc  <- ZIO.service[DataStoreModule.DataStoreService]
            repo  = WorkspaceRepositoryES(svc)
            _    <- repo.append(createdWs)
            _    <- repo.delete("ws-1")
            got  <- repo.get("ws-1")
            list <- repo.list
          yield assertTrue(got.isEmpty && list.isEmpty)).provideLayer(layerFor(dir))
        }
      },
      test("get returns None for missing id") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = WorkspaceRepositoryES(svc)
            got <- repo.get("missing")
          yield assertTrue(got.isEmpty)).provideLayer(layerFor(dir))
        }
      },
      test("appendRun Assigned event and getRun snapshot") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[DataStoreModule.DataStoreService]
            repo    = WorkspaceRepositoryES(svc)
            _      <- repo.appendRun(assignedRun)
            loaded <- repo.getRun("run-1")
          yield assertTrue(
            loaded.isDefined &&
            loaded.get.issueRef == "#42" &&
            loaded.get.status == RunStatus.Pending &&
            loaded.get.attachedUsers.isEmpty &&
            loaded.get.controllerUserId.isEmpty
          )).provideLayer(layerFor(dir))
        }
      },
      test("appendRun StatusChanged updates run status") {
        withTempDir { dir =>
          (for
            svc    <- ZIO.service[DataStoreModule.DataStoreService]
            repo    = WorkspaceRepositoryES(svc)
            _      <- repo.appendRun(assignedRun)
            _      <- repo.appendRun(WorkspaceRunEvent.StatusChanged("run-1", RunStatus.Completed, now.plusSeconds(5)))
            loaded <- repo.getRun("run-1")
          yield assertTrue(loaded.exists(_.status == RunStatus.Completed))).provideLayer(layerFor(dir))
        }
      },
      test("listRuns returns only runs for the given workspace") {
        withTempDir { dir =>
          val run2 = WorkspaceRunEvent.Assigned(
            runId = "run-2",
            workspaceId = "ws-2",
            parentRunId = Some("run-1"),
            issueRef = "#2",
            agentName = "opencode",
            prompt = "p",
            conversationId = "c2",
            worktreePath = "/wt2",
            branchName = "b2",
            occurredAt = now,
          )
          (for
            svc  <- ZIO.service[DataStoreModule.DataStoreService]
            repo  = WorkspaceRepositoryES(svc)
            _    <- repo.appendRun(assignedRun)
            _    <- repo.appendRun(run2)
            runs <- repo.listRuns("ws-1")
          yield assertTrue(runs.length == 1 && runs.head.id == "run-1")).provideLayer(layerFor(dir))
        }
      },
      test("Workspace with RunMode.Docker round-trips through events") {
        withTempDir { dir =>
          (for
            svc <- ZIO.service[DataStoreModule.DataStoreService]
            repo = WorkspaceRepositoryES(svc)
            _   <- repo.append(createdDockerWs)
            got <- repo.get("ws-docker")
          yield assertTrue(
            got.exists(_.runMode == RunMode.Docker(
              "my-image:latest",
              Nil,
              mountWorktree = true,
              network = Some("none"),
            ))
          )).provideLayer(layerFor(dir))
        }
      },
    )
