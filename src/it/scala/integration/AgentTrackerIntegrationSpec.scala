package integration

import java.time.Instant
import java.util.UUID

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import db.*
import orchestration.*

object AgentTrackerIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private def liveLayer(dbName: String): ZLayer[Any, PersistenceError, ProgressTracker & MigrationRepository] =
    ZLayer.make[ProgressTracker & MigrationRepository](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      MigrationRepository.live,
      ProgressTracker.live,
    )

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  private val baseRun = MigrationRunRow(
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
    currentPhase = Some("Analysis"),
    errorMessage = None,
  )

  private enum TestErr:
    case Boom

  def spec: Spec[Any, Any] = suite("AgentTrackerIntegrationSpec")(
    test("trackBatch emits start/progress/complete and persists terminal completed status") {
      val layer = liveLayer(s"it-agent-tracker-success-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          tracker <- ZIO.service[ProgressTracker]
          runId   <- MigrationRepository.createRun(baseRun)
          queue   <- tracker.subscribe(runId)
          out     <- AgentTracker.trackBatch(runId, "Analysis", List(1, 2, 3), tracker) { (item, _) =>
                       ZIO.succeed(item * 2)
                     }
          events  <- ZIO.collectAll(List.fill(5)(queue.take.timeoutFail("missing progress event")(1.second)))
          row     <- MigrationRepository.getProgress(runId, "Analysis")
        yield assertTrue(
          out == List(2, 4, 6),
          events.head.message.contains("Starting phase"),
          events.last.message.contains("Completed phase"),
          events.count(_.message.startsWith("Processing item")) == 3,
          row.exists(_.status == "Completed"),
          row.exists(r => r.itemProcessed == r.itemTotal && r.itemTotal == 3),
        )).provideLayer(layer)
      }
    },
    test("trackPhase failure emits fail event and persists failed status") {
      val layer = liveLayer(s"it-agent-tracker-failure-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          tracker <- ZIO.service[ProgressTracker]
          runId   <- MigrationRepository.createRun(baseRun.copy(currentPhase = Some("Validation")))
          queue   <- tracker.subscribe(runId)
          exit    <- AgentTracker.trackPhase(runId, "Validation", 1, tracker)(ZIO.fail(TestErr.Boom)).exit
          events  <- ZIO.collectAll(List.fill(2)(queue.take.timeoutFail("missing failure flow event")(1.second)))
          row     <- MigrationRepository.getProgress(runId, "Validation")
        yield assertTrue(
          exit == Exit.fail(TestErr.Boom),
          events.exists(_.message.contains("Starting phase")),
          events.exists(_.message.contains("Boom")),
          row.exists(_.status == "Failed"),
          row.exists(_.errorCount == 1),
        )).provideLayer(layer)
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
