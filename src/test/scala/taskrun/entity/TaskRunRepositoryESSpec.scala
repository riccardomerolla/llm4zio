package taskrun.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import shared.ids.Ids
import shared.store.{ DataStoreModule, EventStore, StoreConfig, TaskRunRow }

object TaskRunRepositoryESSpec extends ZIOSpecDefault:

  private type Env = DataStoreModule.DataStoreService & EventStore[Ids.TaskRunId, TaskRunEvent] & TaskRunRepository

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("taskrun-repo-es-spec")).orDie
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
  ): ZLayer[Any, EclipseStoreError | GigaMapError, Env] =
    ZLayer.make[Env](
      ZLayer.succeed(
        StoreConfig(
          configStorePath = dataDir.resolve("config-store").toString,
          dataStorePath = dataDir.resolve("data-store").toString,
        )
      ),
      DataStoreModule.live,
      TaskRunEventStoreES.live,
      TaskRunRepositoryES.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TaskRunRepositoryESSpec")(
      test("append/replay persists snapshot and reconstructs state") {
        withTempDir { dir =>
          val runId      = Ids.TaskRunId("run-evt-1")
          val workflowId = Ids.WorkflowId("wf-evt-1")
          val now        = Instant.parse("2026-02-23T11:00:00Z")
          val events     = List[TaskRunEvent](
            TaskRunEvent.Created(runId, workflowId, "planner", "src", now),
            TaskRunEvent.Started(runId, "analysis", now.plusSeconds(5)),
            TaskRunEvent.PhaseChanged(runId, "transform", now.plusSeconds(10)),
            TaskRunEvent.ReportAdded(
              runId,
              TaskReport(Ids.ReportId("report-1"), "analysis", "summary", "ok", now.plusSeconds(20)),
              now.plusSeconds(20),
            ),
            TaskRunEvent.Completed(runId, "done", now.plusSeconds(30)),
          )

          (for
            repo       <- ZIO.service[TaskRunRepository]
            eventStore <- ZIO.service[EventStore[Ids.TaskRunId, TaskRunEvent]]
            _          <- ZIO.foreachDiscard(events)(repo.append)
            run        <- repo.get(runId)
            stored     <- eventStore.events(runId)
          yield assertTrue(
            stored.size == events.size,
            run.id == runId,
            run.workflowId == workflowId,
            run.reports.size == 1,
            run.state == TaskRunState.Completed(now.plusSeconds(5), now.plusSeconds(30), "done"),
          )).provideLayer(layerFor(dir))
        }
      },
      test("eventsSince returns only new events") {
        withTempDir { dir =>
          val runId      = Ids.TaskRunId("run-evt-2")
          val workflowId = Ids.WorkflowId("wf-evt-2")
          val now        = Instant.parse("2026-02-23T11:05:00Z")

          (for
            eventStore <- ZIO.service[EventStore[Ids.TaskRunId, TaskRunEvent]]
            _          <- eventStore.append(runId, TaskRunEvent.Created(runId, workflowId, "planner", "src", now))
            _          <- eventStore.append(runId, TaskRunEvent.Started(runId, "analysis", now.plusSeconds(3)))
            _          <- eventStore.append(runId, TaskRunEvent.Failed(runId, "boom", now.plusSeconds(8)))
            since1     <- eventStore.eventsSince(runId, 1L)
          yield assertTrue(since1.size == 2)).provideLayer(layerFor(dir))
        }
      },
      test("legacy TaskRunRow migration emits events and snapshot") {
        withTempDir { dir =>
          val startedAt = Instant.parse("2026-02-23T12:00:00Z")
          val legacyRow = TaskRunRow(
            id = "legacy-run-1",
            sourceDir = "legacy/src",
            outputDir = "legacy/out",
            status = "completed",
            workflowId = Some("legacy-wf-1"),
            currentPhase = Some("analysis"),
            errorMessage = None,
            startedAt = startedAt,
            completedAt = Some(startedAt.plusSeconds(30)),
            totalFiles = 10,
            processedFiles = 10,
            successfulConversions = 10,
            failedConversions = 0,
          )

          (for
            dataStore <- ZIO.service[DataStoreModule.DataStoreService]
            _         <- dataStore.store.store("run:legacy-run-1", legacyRow)
            migrated  <- TaskRunMigration.migrateLegacyRows
            repo      <- ZIO.service[TaskRunRepository]
            run       <- repo.get(Ids.TaskRunId("legacy-run-1"))
          yield assertTrue(
            migrated == 1,
            run.id == Ids.TaskRunId("legacy-run-1"),
            run.workflowId == Ids.WorkflowId("legacy-wf-1"),
            run.state == TaskRunState.Completed(
              startedAt,
              startedAt.plusSeconds(30),
              s"legacy completed at ${startedAt.plusSeconds(30)}",
            ),
          )).provideLayer(layerFor(dir))
        }
      },
    ) @@ TestAspect.sequential
