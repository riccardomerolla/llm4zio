package core

import java.nio.file.{ Files, Path, Paths }
import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import models.*

object StateServiceSpec extends ZIOSpecDefault:

  /** Creates a temporary directory for tests */
  private def withTempStateDir[R, E, A](test: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed(Files.createTempDirectory("stateservice-test"))
    )(tempDir =>
      ZIO.succeed {
        if Files.exists(tempDir) then
          Files
            .walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { p =>
              val _ = Files.deleteIfExists(p)
            }
      }
    )(test)

  /** Helper to create a test MigrationState */
  private def createTestState(
    runId: String = "test-run-001",
    currentStep: MigrationStep = MigrationStep.Discovery,
    completedSteps: Set[MigrationStep] = Set.empty,
  ): MigrationState =
    MigrationState(
      runId = runId,
      startedAt = Instant.parse("2024-01-15T10:00:00Z"),
      currentStep = currentStep,
      completedSteps = completedSteps,
      artifacts = Map.empty,
      errors = List.empty,
      config = MigrationConfig(
        sourceDirectory = Paths.get("cobol-source"),
        outputDirectory = Paths.get("java-output"),
        geminiModel = "gemini-2.0-flash",
      ),
      fileInventory = None,
      analyses = List.empty,
      dependencyGraph = None,
      projects = List.empty,
      validationReports = List.empty,
      lastCheckpoint = Instant.parse("2024-01-15T10:00:00Z"),
    )

  def spec: Spec[Any, Any] = suite("StateServiceSpec")(
    // ========================================================================
    // saveState tests
    // ========================================================================
    suite("saveState")(
      test("saves state to JSON file successfully") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _      <- StateService.saveState(state).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            exists <- ZIO.succeed(Files.exists(stateDir.resolve("runs/test-run-001/state.json")))
          yield assertTrue(exists)
        }
      },
      test("creates necessary directories") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _         <- StateService.saveState(state).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            runDir    <- ZIO.succeed(stateDir.resolve("runs/test-run-001"))
            runsExist <- ZIO.succeed(Files.exists(runDir) && Files.isDirectory(runDir))
          yield assertTrue(runsExist)
        }
      },
      test("saves valid JSON content") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _       <- StateService.saveState(state).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            content <- ZIO.succeed(Files.readString(stateDir.resolve("runs/test-run-001/state.json")))
          yield assertTrue(
            content.contains("\"test-run-001\""),
            content.contains("\"Discovery\""),
          )
        }
      },
      test("overwrites existing state with atomic move") {
        withTempStateDir { stateDir =>
          val state1 = createTestState(currentStep = MigrationStep.Discovery)
          val state2 = createTestState(currentStep = MigrationStep.Analysis)
          for
            _        <- StateService.saveState(state1).provide(
                          StateService.live(stateDir),
                          FileService.live,
                        )
            _        <- StateService.saveState(state2).provide(
                          StateService.live(stateDir),
                          FileService.live,
                        )
            reloaded <- StateService.loadState("test-run-001").provide(
                          StateService.live(stateDir),
                          FileService.live,
                        )
          yield assertTrue(
            reloaded.isDefined,
            reloaded.get.currentStep == MigrationStep.Analysis,
          )
        }
      },
      test("updates index.json with run summary") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _           <- StateService.saveState(state).provide(
                             StateService.live(stateDir),
                             FileService.live,
                           )
            indexPath    = stateDir.resolve("index.json")
            indexExists <- ZIO.succeed(Files.exists(indexPath))
            content     <- ZIO.succeed(Files.readString(indexPath))
          yield assertTrue(
            indexExists,
            content.contains("test-run-001"),
          )
        }
      },
    ),
    // ========================================================================
    // loadState tests
    // ========================================================================
    suite("loadState")(
      test("loads existing state successfully") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _      <- StateService.saveState(state).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            loaded <- StateService.loadState("test-run-001").provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
          yield assertTrue(
            loaded.isDefined,
            loaded.get.runId == "test-run-001",
            loaded.get.currentStep == MigrationStep.Discovery,
          )
        }
      },
      test("returns None for non-existent run") {
        withTempStateDir { stateDir =>
          for loaded <- StateService.loadState("non-existent").provide(
                          StateService.live(stateDir),
                          FileService.live,
                        )
          yield assertTrue(loaded.isEmpty)
        }
      },
      test("deserializes state with all fields correctly") {
        withTempStateDir { stateDir =>
          val state = createTestState(
            currentStep = MigrationStep.Analysis,
            completedSteps = Set(MigrationStep.Discovery),
          ).copy(
            artifacts = Map("discovery" -> "/path/to/inventory.json"),
            errors = List(
              MigrationError(
                MigrationStep.Discovery,
                "Test error",
                Instant.parse("2024-01-15T10:30:00Z"),
              )
            ),
          )
          for
            _      <- StateService.saveState(state).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            loaded <- StateService.loadState("test-run-001").provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
          yield assertTrue(
            loaded.isDefined,
            loaded.get.currentStep == MigrationStep.Analysis,
            loaded.get.completedSteps == Set(MigrationStep.Discovery),
            loaded.get.artifacts.contains("discovery"),
            loaded.get.errors.length == 1,
          )
        }
      },
    ),
    // ========================================================================
    // createCheckpoint tests
    // ========================================================================
    suite("createCheckpoint")(
      test("creates checkpoint file successfully") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _      <- StateService.saveState(state).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            _      <- StateService.createCheckpoint("test-run-001", MigrationStep.Discovery).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            exists <- ZIO.succeed(
                        Files.exists(stateDir.resolve("runs/test-run-001/checkpoints/discovery.json"))
                      )
          yield assertTrue(exists)
        }
      },
      test("creates checkpoints directory if not exists") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _         <- StateService.saveState(state).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            _         <- StateService.createCheckpoint("test-run-001", MigrationStep.Discovery).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            checkpDir <- ZIO.succeed(stateDir.resolve("runs/test-run-001/checkpoints"))
            exists    <- ZIO.succeed(Files.exists(checkpDir) && Files.isDirectory(checkpDir))
          yield assertTrue(exists)
        }
      },
      test("checkpoint contains full state snapshot") {
        withTempStateDir { stateDir =>
          val state = createTestState(currentStep = MigrationStep.Discovery)
          for
            _       <- StateService.saveState(state).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            _       <- StateService.createCheckpoint("test-run-001", MigrationStep.Discovery).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            content <- ZIO.succeed(
                         Files.readString(stateDir.resolve("runs/test-run-001/checkpoints/discovery.json"))
                       )
          yield assertTrue(
            content.contains("\"test-run-001\""),
            content.contains("\"Discovery\""),
          )
        }
      },
      test("fails when state does not exist") {
        withTempStateDir { stateDir =>
          for result <- StateService
                          .createCheckpoint("non-existent", MigrationStep.Discovery)
                          .provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
                          .either
          yield assertTrue(result == Left(StateError.StateNotFound("non-existent")))
        }
      },
      test("creates checkpoints for different steps") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _       <- StateService.saveState(state).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            _       <- StateService.createCheckpoint("test-run-001", MigrationStep.Discovery).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            _       <- StateService.createCheckpoint("test-run-001", MigrationStep.Analysis).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            exists1 <- ZIO.succeed(
                         Files.exists(stateDir.resolve("runs/test-run-001/checkpoints/discovery.json"))
                       )
            exists2 <- ZIO.succeed(
                         Files.exists(stateDir.resolve("runs/test-run-001/checkpoints/analysis.json"))
                       )
          yield assertTrue(exists1, exists2)
        }
      },
    ),
    // ========================================================================
    // getLastCheckpoint tests
    // ========================================================================
    suite("getLastCheckpoint")(
      test("returns checkpoint step when exists") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _          <- StateService.saveState(state).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            _          <- StateService.createCheckpoint("test-run-001", MigrationStep.Discovery).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            checkpoint <- StateService.getLastCheckpoint("test-run-001").provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
          yield assertTrue(
            checkpoint.isDefined,
            checkpoint.get == MigrationStep.Discovery,
          )
        }
      },
      test("returns None when no checkpoints exist") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _          <- StateService.saveState(state).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            checkpoint <- StateService.getLastCheckpoint("test-run-001").provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
          yield assertTrue(checkpoint.isEmpty)
        }
      },
      test("returns None for non-existent run") {
        withTempStateDir { stateDir =>
          for checkpoint <- StateService.getLastCheckpoint("non-existent").provide(
                              StateService.live(stateDir),
                              FileService.live,
                            )
          yield assertTrue(checkpoint.isEmpty)
        }
      },
    ),
    // ========================================================================
    // listRuns tests
    // ========================================================================
    suite("listRuns")(
      test("lists all saved runs") {
        withTempStateDir { stateDir =>
          val state1 = createTestState(runId = "run-001")
          val state2 = createTestState(runId = "run-002")
          for
            _    <- StateService.saveState(state1).provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
            _    <- StateService.saveState(state2).provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
            runs <- StateService.listRuns().provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
          yield assertTrue(
            runs.length == 2,
            runs.exists(_.runId == "run-001"),
            runs.exists(_.runId == "run-002"),
          )
        }
      },
      test("returns empty list when no runs exist") {
        withTempStateDir { stateDir =>
          for runs <- StateService.listRuns().provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
          yield assertTrue(runs.isEmpty)
        }
      },
      test("run summaries contain correct metadata") {
        withTempStateDir { stateDir =>
          for
            now    <- Clock.instant
            state  <- ZIO.succeed(
                        createTestState(
                          runId = "run-with-metadata",
                          currentStep = MigrationStep.Analysis,
                          completedSteps = Set(MigrationStep.Discovery),
                        ).copy(
                          errors = List(
                            MigrationError(MigrationStep.Discovery, "Error 1", now),
                            MigrationError(MigrationStep.Discovery, "Error 2", now),
                          )
                        )
                      )
            _      <- StateService.saveState(state).provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            runs   <- StateService.listRuns().provide(
                        StateService.live(stateDir),
                        FileService.live,
                      )
            summary = runs.find(_.runId == "run-with-metadata")
          yield assertTrue(
            summary.isDefined,
            summary.get.currentStep == MigrationStep.Analysis,
            summary.get.completedSteps == Set(MigrationStep.Discovery),
            summary.get.errorCount == 2,
          )
        }
      },
      test("runs are sorted by startedAt timestamp descending") {
        withTempStateDir { stateDir =>
          val state1 = createTestState(runId = "run-old").copy(
            startedAt = Instant.parse("2024-01-15T08:00:00Z")
          )
          val state2 = createTestState(runId = "run-recent").copy(
            startedAt = Instant.parse("2024-01-15T12:00:00Z")
          )
          for
            _    <- StateService.saveState(state1).provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
            _    <- StateService.saveState(state2).provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
            runs <- StateService.listRuns().provide(
                      StateService.live(stateDir),
                      FileService.live,
                    )
          yield assertTrue(
            runs.length == 2,
            runs.head.runId == "run-recent",
            runs.last.runId == "run-old",
          )
        }
      },
    ),
    // ========================================================================
    // StateError ADT tests
    // ========================================================================
    suite("StateError")(
      test("StateNotFound has correct message") {
        val error = StateError.StateNotFound("test-run")
        assertTrue(error.message.contains("test-run"))
      },
      test("InvalidState has correct message") {
        val error = StateError.InvalidState("test-run", "Invalid JSON")
        assertTrue(
          error.message.contains("test-run"),
          error.message.contains("Invalid JSON"),
        )
      },
      test("WriteError has correct message") {
        val error = StateError.WriteError("test-run", "Permission denied")
        assertTrue(
          error.message.contains("test-run"),
          error.message.contains("Permission denied"),
        )
      },
    ),
    // ========================================================================
    // Integration tests
    // ========================================================================
    suite("Integration")(
      test("complete workflow: save -> load -> checkpoint -> list") {
        withTempStateDir { stateDir =>
          val state = createTestState(runId = "integration-run")
          for
            // Save initial state
            _          <- StateService.saveState(state).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            // Load and verify
            loaded     <- StateService.loadState("integration-run").provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            // Create checkpoint
            _          <- StateService.createCheckpoint("integration-run", MigrationStep.Discovery).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            // Get last checkpoint
            checkpoint <- StateService.getLastCheckpoint("integration-run").provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            // List all runs
            runs       <- StateService.listRuns().provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
          yield assertTrue(
            loaded.isDefined,
            loaded.get.runId == "integration-run",
            checkpoint.isDefined,
            checkpoint.get == MigrationStep.Discovery,
            runs.length == 1,
            runs.head.runId == "integration-run",
          )
        }
      },
      test("multiple runs with different progress") {
        withTempStateDir { stateDir =>
          val state1 = createTestState(runId = "run-1", currentStep = MigrationStep.Discovery)
          val state2 = createTestState(
            runId = "run-2",
            currentStep = MigrationStep.Analysis,
            completedSteps = Set(MigrationStep.Discovery),
          )
          val state3 = createTestState(
            runId = "run-3",
            currentStep = MigrationStep.Transformation,
            completedSteps = Set(MigrationStep.Discovery, MigrationStep.Analysis),
          )
          for
            _     <- StateService.saveState(state1).provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            _     <- StateService.saveState(state2).provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            _     <- StateService.saveState(state3).provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            runs  <- StateService.listRuns().provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            load1 <- StateService.loadState("run-1").provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            load2 <- StateService.loadState("run-2").provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
            load3 <- StateService.loadState("run-3").provide(
                       StateService.live(stateDir),
                       FileService.live,
                     )
          yield assertTrue(
            runs.length == 3,
            load1.get.completedSteps.isEmpty,
            load2.get.completedSteps.size == 1,
            load3.get.completedSteps.size == 2,
          )
        }
      },
    ),
  )
