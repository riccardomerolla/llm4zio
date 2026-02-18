package integration

import java.time.Instant
import java.util.UUID

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import db.*
import orchestration.*

object ProgressTrackerIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private def liveLayer(dbName: String): ZLayer[Any, PersistenceError, ProgressTracker & TaskRepository] =
    ZLayer.make[ProgressTracker & TaskRepository](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      TaskRepository.live,
      ProgressTracker.live,
    )

  def spec: Spec[Any, Any] = suite("ProgressTrackerIntegrationSpec")(
    test("start/update/complete persist to DB and publish in order") {
      val layer = liveLayer(s"it-progress-${UUID.randomUUID()}")
      val now   = Instant.parse("2026-02-08T00:00:00Z")

      ZIO.scoped {
        (for
          runId <- TaskRepository.createRun(
                     TaskRunRow(
                       id = 0L,
                       sourceDir = "/tmp/it-source",
                       outputDir = "/tmp/it-output",
                       status = RunStatus.Running,
                       startedAt = now,
                       completedAt = None,
                       totalFiles = 3,
                       processedFiles = 0,
                       successfulConversions = 0,
                       failedConversions = 0,
                       currentPhase = Some("Discovery"),
                       errorMessage = None,
                     )
                   )
          queue <- ProgressTracker.subscribe(runId)
          _     <- ProgressTracker.startPhase(runId, "Discovery", 3)
          _     <- ProgressTracker.updateProgress(
                     models.ProgressUpdate(
                       runId = runId,
                       phase = "Discovery",
                       itemsProcessed = 2,
                       itemsTotal = 3,
                       message = "processing",
                       timestamp = now,
                     )
                   )
          _     <- ProgressTracker.completePhase(runId, "Discovery")

          first  <- queue.take.timeoutFail("missing start event")(1.second)
          second <- queue.take.timeoutFail("missing update event")(1.second)
          third  <- queue.take.timeoutFail("missing completion event")(1.second)
          row    <- TaskRepository.getProgress(runId, "Discovery")
        yield assertTrue(
          first.message.contains("Starting phase"),
          second.itemsProcessed == 2,
          third.message.contains("Completed phase"),
          row.exists(_.status == "Completed"),
          row.exists(row => row.itemProcessed == row.itemTotal),
          row.exists(_.itemTotal == 3),
        )).provideLayer(layer)
      }
    },
    test("subscribe filters by runId in live setup") {
      val layer = liveLayer(s"it-progress-filter-${UUID.randomUUID()}")
      val now   = Instant.parse("2026-02-08T00:00:00Z")

      ZIO.scoped {
        (for
          runIdA <- TaskRepository.createRun(
                      TaskRunRow(
                        id = 0L,
                        sourceDir = "/tmp/it-source-a",
                        outputDir = "/tmp/it-output-a",
                        status = RunStatus.Running,
                        startedAt = now,
                        completedAt = None,
                        totalFiles = 1,
                        processedFiles = 0,
                        successfulConversions = 0,
                        failedConversions = 0,
                        currentPhase = Some("Analysis"),
                        errorMessage = None,
                      )
                    )
          runIdB <- TaskRepository.createRun(
                      TaskRunRow(
                        id = 0L,
                        sourceDir = "/tmp/it-source-b",
                        outputDir = "/tmp/it-output-b",
                        status = RunStatus.Running,
                        startedAt = now,
                        completedAt = None,
                        totalFiles = 1,
                        processedFiles = 0,
                        successfulConversions = 0,
                        failedConversions = 0,
                        currentPhase = Some("Analysis"),
                        errorMessage = None,
                      )
                    )
          queueA <- ProgressTracker.subscribe(runIdA)
          queueB <- ProgressTracker.subscribe(runIdB)
          _      <- ProgressTracker.startPhase(runIdA, "Analysis", 1)
          seenA  <- queueA.take.timeoutFail("expected event for runId=10")(1.second)
          seenB  <- queueB.take.timeout(300.millis)
        yield assertTrue(
          seenA.runId == runIdA,
          seenA.phase == "Analysis",
          seenB.isEmpty,
        )).provideLayer(layer)
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
