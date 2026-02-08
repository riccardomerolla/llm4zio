package integration

import java.time.Instant
import java.util.UUID

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import db.*

object MigrationRepositoryIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private def repoLayer(dbName: String): ZLayer[Any, PersistenceError, MigrationRepository] =
    ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")) >>>
      Database.live >>>
      MigrationRepository.live

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  private val baseRun = MigrationRunRow(
    id = 0L,
    sourceDir = "/tmp/it-source",
    outputDir = "/tmp/it-output",
    status = RunStatus.Running,
    startedAt = now,
    completedAt = None,
    totalFiles = 2,
    processedFiles = 0,
    successfulConversions = 0,
    failedConversions = 0,
    currentPhase = Some("Discovery"),
    errorMessage = None,
  )

  def spec: Spec[Any, Any] = suite("MigrationRepositoryIntegrationSpec")(
    test("initializes schema and persists complete run data") {
      val layer = repoLayer(s"it-db-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          runId <- MigrationRepository.createRun(baseRun)
          _     <- MigrationRepository.saveFiles(
                     List(
                       CobolFileRow(
                         id = 0L,
                         runId = runId,
                         path = "/tmp/it-source/CUST.cbl",
                         name = "CUST.cbl",
                         fileType = db.FileType.Program,
                         size = 100,
                         lineCount = 10,
                         encoding = "UTF-8",
                         createdAt = now,
                       )
                     )
                   )
          files <- MigrationRepository.getFilesByRun(runId)
          _     <- MigrationRepository.saveAnalysis(
                     CobolAnalysisRow(
                       id = 0L,
                       runId = runId,
                       fileId = files.head.id,
                       analysisJson = "{\"name\":\"CUST\"}",
                       createdAt = now,
                     )
                   )
          _     <- MigrationRepository.saveDependencies(
                     List(
                       DependencyRow(
                         id = 0L,
                         runId = runId,
                         sourceNode = "CUST",
                         targetNode = "COPY-CUST",
                         edgeType = "Includes",
                       )
                     )
                   )
          pid   <- MigrationRepository.saveProgress(
                     PhaseProgressRow(
                       id = 0L,
                       runId = runId,
                       phase = "Discovery",
                       status = "Completed",
                       itemTotal = 2,
                       itemProcessed = 2,
                       errorCount = 0,
                       updatedAt = now,
                     )
                   )
          run   <- MigrationRepository.getRun(runId)
          deps  <- MigrationRepository.getDependenciesByRun(runId)
          prog  <- MigrationRepository.getProgress(runId, "Discovery")
        yield assertTrue(
          run.exists(_.currentPhase.contains("Discovery")),
          files.length == 1,
          deps.length == 1,
          prog.exists(_.id == pid),
        )).provideLayer(layer)
      }
    },
    test("returns typed NotFound for missing entities") {
      val layer = repoLayer(s"it-db-not-found-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          del <- MigrationRepository.deleteRun(404L).exit
          upd <- MigrationRepository.updateProgress(
                   PhaseProgressRow(
                     id = 404L,
                     runId = 1L,
                     phase = "Analysis",
                     status = "Running",
                     itemTotal = 1,
                     itemProcessed = 0,
                     errorCount = 0,
                     updatedAt = now,
                   )
                 ).exit
        yield assertTrue(
          del match
            case Exit.Failure(cause) => cause.failureOption.contains(PersistenceError.NotFound("migration_runs", 404L))
            case Exit.Success(_)     => false,
          upd match
            case Exit.Failure(cause) => cause.failureOption.contains(PersistenceError.NotFound("phase_progress", 404L))
            case Exit.Success(_)     => false,
        )).provideLayer(layer)
      }
    },
    test("maps datasource init errors to ConnectionFailed") {
      val bad = ZLayer.succeed(DatabaseConfig("jdbc:sqlite:file:/invalid/path/it-db.sqlite")) >>> Database.live

      ZIO.scoped {
        for buildExit <- bad.build.exit
        yield assertTrue(
          buildExit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists {
                case PersistenceError.ConnectionFailed(_) => true
                case _                                    => false
              }
            case Exit.Success(_)     => false
        )
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
