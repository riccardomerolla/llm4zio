package db

import java.time.Instant
import java.util.UUID

import zio.*
import zio.test.*

object MigrationRepositorySpec extends ZIOSpecDefault:

  private def repoLayer(dbName: String): ZLayer[Any, PersistenceError, MigrationRepository] =
    ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")) >>>
      Database.live >>>
      MigrationRepository.live

  private val fixedNow = Instant.parse("2026-02-08T00:00:00Z")

  private val sampleRun = MigrationRunRow(
    id = 0L,
    sourceDir = "/tmp/source",
    outputDir = "/tmp/output",
    status = RunStatus.Running,
    startedAt = fixedNow,
    completedAt = None,
    totalFiles = 3,
    processedFiles = 1,
    successfulConversions = 1,
    failedConversions = 0,
    currentPhase = Some("Analysis"),
    errorMessage = None,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MigrationRepositorySpec")(
    test("creates, updates, gets, lists and deletes run") {
      val layer = repoLayer(s"repo-run-${UUID.randomUUID()}")

      (for
        id  <- MigrationRepository.createRun(sampleRun)
        _   <- MigrationRepository.updateRun(
                 sampleRun.copy(
                   id = id,
                   status = RunStatus.Completed,
                   completedAt = Some(fixedNow.plusSeconds(60)),
                   processedFiles = 3,
                   successfulConversions = 3,
                   currentPhase = Some("Documentation"),
                 )
               )
        got <- MigrationRepository.getRun(id)
        all <- MigrationRepository.listRuns(offset = 0, limit = 10)
        _   <- MigrationRepository.deleteRun(id)
        del <- MigrationRepository.getRun(id)
      yield assertTrue(
        id > 0,
        got.exists(_.status == RunStatus.Completed),
        got.exists(_.processedFiles == 3),
        all.exists(_.id == id),
        del.isEmpty,
      )).provideLayer(layer)
    },
    test("saves and loads files, analyses, dependencies and progress") {
      val layer = repoLayer(s"repo-artifacts-${UUID.randomUUID()}")

      (for
        runId <- MigrationRepository.createRun(sampleRun)

        _ <- MigrationRepository.saveFiles(
               List(
                 CobolFileRow(
                   id = 0L,
                   runId = runId,
                   path = "/tmp/source/PROG1.cbl",
                   name = "PROG1.cbl",
                   fileType = FileType.Program,
                   size = 123L,
                   lineCount = 10L,
                   encoding = "UTF-8",
                   createdAt = fixedNow,
                 ),
                 CobolFileRow(
                   id = 0L,
                   runId = runId,
                   path = "/tmp/source/COPY1.cpy",
                   name = "COPY1.cpy",
                   fileType = FileType.Copybook,
                   size = 45L,
                   lineCount = 5L,
                   encoding = "UTF-8",
                   createdAt = fixedNow,
                 ),
               )
             )

        files <- MigrationRepository.getFilesByRun(runId)

        analysisId <- MigrationRepository.saveAnalysis(
                        CobolAnalysisRow(
                          id = 0L,
                          runId = runId,
                          fileId = files.head.id,
                          analysisJson = "{\"program\":\"PROG1\"}",
                          createdAt = fixedNow,
                        )
                      )
        analyses   <- MigrationRepository.getAnalysesByRun(runId)

        _    <- MigrationRepository.saveDependencies(
                  List(
                    DependencyRow(
                      id = 0L,
                      runId = runId,
                      sourceNode = "PROG1",
                      targetNode = "COPY1",
                      edgeType = "Includes",
                    )
                  )
                )
        deps <- MigrationRepository.getDependenciesByRun(runId)

        progressId <- MigrationRepository.saveProgress(
                        PhaseProgressRow(
                          id = 0L,
                          runId = runId,
                          phase = "Analysis",
                          status = "Running",
                          itemTotal = 10,
                          itemProcessed = 2,
                          errorCount = 0,
                          updatedAt = fixedNow,
                        )
                      )
        _          <- MigrationRepository.updateProgress(
                        PhaseProgressRow(
                          id = progressId,
                          runId = runId,
                          phase = "Analysis",
                          status = "Completed",
                          itemTotal = 10,
                          itemProcessed = 10,
                          errorCount = 0,
                          updatedAt = fixedNow.plusSeconds(30),
                        )
                      )
        progress   <- MigrationRepository.getProgress(runId, "Analysis")
      yield assertTrue(
        files.length == 2,
        analysisId > 0,
        analyses.length == 1,
        deps.length == 1,
        progress.exists(_.id == progressId),
        progress.exists(_.status == "Completed"),
      )).provideLayer(layer)
    },
    test("returns NotFound for missing updates/deletes") {
      val layer = repoLayer(s"repo-not-found-${UUID.randomUUID()}")

      (for
        missingDelete <- MigrationRepository.deleteRun(999L).exit
        missingUpdate <- MigrationRepository.updateRun(sampleRun.copy(id = 999L)).exit
        missingProg   <- MigrationRepository.updateProgress(
                           PhaseProgressRow(
                             id = 999L,
                             runId = 1L,
                             phase = "Analysis",
                             status = "Running",
                             itemTotal = 0,
                             itemProcessed = 0,
                             errorCount = 0,
                             updatedAt = fixedNow,
                           )
                         ).exit
      yield assertTrue(
        missingDelete match
          case Exit.Failure(cause) => cause.failureOption.contains(PersistenceError.NotFound("migration_runs", 999L))
          case Exit.Success(_)     => false,
        missingUpdate match
          case Exit.Failure(cause) => cause.failureOption.contains(PersistenceError.NotFound("migration_runs", 999L))
          case Exit.Success(_)     => false,
        missingProg match
          case Exit.Failure(cause) => cause.failureOption.contains(PersistenceError.NotFound("phase_progress", 999L))
          case Exit.Success(_)     => false,
      )).provideLayer(layer)
    },
    test("maps connection failures to PersistenceError.ConnectionFailed") {
      val badLayer = ZLayer.succeed(DatabaseConfig("jdbc:sqlite:file:/invalid/path/that/does/not/exist/db.sqlite")) >>>
        Database.live >>>
        MigrationRepository.live

      for result <- badLayer.build.exit
      yield assertTrue(
        result match
          case Exit.Failure(cause) =>
            cause.failureOption.exists {
              case PersistenceError.ConnectionFailed(_) => true
              case _                                    => false
            }
          case Exit.Success(_)     => false
      )
    },
  ) @@ TestAspect.sequential
