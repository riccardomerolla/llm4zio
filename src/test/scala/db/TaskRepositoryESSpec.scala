package db

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.store.{ ConfigStoreModule, DataStoreModule, StoreConfig }

object TaskRepositoryESSpec extends ZIOSpecDefault:

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("task-repository-es-spec")).orDie
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

  private def layerFor(path: Path): ZLayer[Any, EclipseStoreError | GigaMapError, TaskRepository] =
    (ZLayer.succeed(
      StoreConfig(
        configStorePath = path.resolve("config-store").toString,
        dataStorePath = path.resolve("data-store").toString,
      )
    ) >>> (DataStoreModule.live ++ ConfigStoreModule.live)) >>> TaskRepositoryLive.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TaskRepositoryESSpec")(
      test("createRun/getRun/updateRun/listRuns round-trip") {
        withTempDir { dir =>
          val base = TaskRunRow(
            id = 0L,
            sourceDir = "./in",
            outputDir = "./out",
            status = RunStatus.Pending,
            startedAt = Instant.parse("2026-02-19T14:00:00Z"),
            completedAt = None,
            totalFiles = 10,
            processedFiles = 0,
            successfulConversions = 0,
            failedConversions = 0,
            currentPhase = Some("Discovery"),
            errorMessage = None,
            workflowId = None,
          )

          val program =
            for
              repo         <- ZIO.service[TaskRepository]
              runId        <- repo.createRun(base)
              loadedBefore <- repo.getRun(runId)
              _            <- repo.updateRun(base.copy(id = runId, status = RunStatus.Running, processedFiles = 5))
              loadedAfter  <- repo.getRun(runId)
              runs         <- repo.listRuns(0, 20)
            yield assertTrue(
              loadedBefore.exists(_.status == RunStatus.Pending),
              loadedAfter.exists(_.status == RunStatus.Running),
              loadedAfter.exists(_.processedFiles == 5),
              runs.exists(_.id == runId),
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("saveReport and getArtifactsByTask return persisted rows") {
        withTempDir { dir =>
          val program =
            for
              repo      <- ZIO.service[TaskRepository]
              runId     <- repo.createRun(
                             TaskRunRow(
                               id = 0L,
                               sourceDir = "./in",
                               outputDir = "./out",
                               status = RunStatus.Running,
                               startedAt = Instant.parse("2026-02-19T14:10:00Z"),
                               completedAt = None,
                               totalFiles = 3,
                               processedFiles = 1,
                               successfulConversions = 1,
                               failedConversions = 0,
                               currentPhase = Some("Analysis"),
                               errorMessage = None,
                               workflowId = None,
                             )
                           )
              _         <- repo.saveReport(
                             TaskReportRow(
                               id = 0L,
                               taskRunId = runId,
                               stepName = "Analysis",
                               reportType = "summary",
                               content = "ok",
                               createdAt = Instant.parse("2026-02-19T14:10:30Z"),
                             )
                           )
              _         <- repo.saveArtifact(
                             TaskArtifactRow(
                               id = 0L,
                               taskRunId = runId,
                               stepName = "Analysis",
                               key = "memory.summary",
                               value = "details",
                               createdAt = Instant.parse("2026-02-19T14:11:00Z"),
                             )
                           )
              reports   <- repo.getReportsByTask(runId)
              artifacts <- repo.getArtifactsByTask(runId)
            yield assertTrue(
              reports.length == 1,
              reports.head.stepName == "Analysis",
              artifacts.length == 1,
              artifacts.head.key == "memory.summary",
            )

          program.provideLayer(layerFor(dir))
        }
      },
      test("settings/workflow/custom-agent methods operate through config store") {
        withTempDir { dir =>
          val now     = Instant.parse("2026-02-19T14:20:00Z")
          val program =
            for
              repo       <- ZIO.service[TaskRepository]
              _          <- repo.upsertSetting("gateway.timeout", "30")
              setting    <- repo.getSetting("gateway.timeout")
              workflowId <- repo.createWorkflow(
                              WorkflowRow(
                                id = None,
                                name = "Default",
                                description = Some("desc"),
                                steps = "[\"Discovery\"]",
                                isBuiltin = true,
                                createdAt = now,
                                updatedAt = now,
                              )
                            )
              workflow   <- repo.getWorkflow(workflowId)
              agentId    <- repo.createCustomAgent(
                              CustomAgentRow(
                                id = None,
                                name = "custom-reviewer",
                                displayName = "Custom Reviewer",
                                description = Some("desc"),
                                systemPrompt = "review",
                                tags = Some("[\"review\"]"),
                                enabled = true,
                                createdAt = now,
                                updatedAt = now,
                              )
                            )
              agent      <- repo.getCustomAgent(agentId)
            yield assertTrue(
              setting.exists(_.value == "30"),
              workflow.exists(_.name == "Default"),
              agent.exists(_.name == "custom-reviewer"),
            )

          program.provideLayer(layerFor(dir))
        }
      },
    )
