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

  /** Helper to create a test TaskState */
  private def createTestState(
    runId: String = "test-run-001",
    currentStep: TaskStep = TaskStep.Discovery,
    completedSteps: Set[TaskStep] = Set.empty,
  ): TaskState =
    TaskState(
      runId = runId,
      startedAt = Instant.parse("2024-01-15T10:00:00Z"),
      currentStep = currentStep,
      completedSteps = completedSteps,
      artifacts = Map.empty,
      errors = List.empty,
      config = MigrationConfig(
        sourceDir = Paths.get("cobol-source"),
        outputDir = Paths.get("java-output"),
      ),
      workspace = None,
      status = TaskStatus.Running,
      lastCheckpoint = Instant.parse("2024-01-15T10:00:00Z"),
      taskRunId = None,
      currentStepName = None,
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
          val state1 = createTestState(currentStep = TaskStep.Discovery)
          val state2 = createTestState(currentStep = TaskStep.Analysis)
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
            reloaded.get.currentStep == TaskStep.Analysis,
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
            loaded.get.currentStep == TaskStep.Discovery,
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
            currentStep = TaskStep.Analysis,
            completedSteps = Set(TaskStep.Discovery),
          ).copy(
            artifacts = Map("discovery" -> "/path/to/inventory.json"),
            errors = List(
              TaskError(
                TaskStep.Discovery,
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
            loaded.get.currentStep == TaskStep.Analysis,
            loaded.get.completedSteps == Set(TaskStep.Discovery),
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
            _      <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
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
            _         <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
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
          val state = createTestState(currentStep = TaskStep.Discovery)
          for
            _       <- StateService.saveState(state).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            _       <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            content <- ZIO.succeed(
                         Files.readString(stateDir.resolve("runs/test-run-001/checkpoints/discovery.json"))
                       )
          yield assertTrue(
            content.contains("\"test-run-001\""),
            content.contains("\"Discovery\""),
            content.contains("\"checksum\""),
          )
        }
      },
      test("fails when state does not exist") {
        withTempStateDir { stateDir =>
          for result <- StateService
                          .createCheckpoint("non-existent", TaskStep.Discovery)
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
            _       <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                         StateService.live(stateDir),
                         FileService.live,
                       )
            _       <- StateService.createCheckpoint("test-run-001", TaskStep.Analysis).provide(
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
    // listCheckpoints tests
    // ========================================================================
    suite("listCheckpoints")(
      test("returns checkpoints sorted by creation time") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _           <- StateService.saveState(state).provide(
                             StateService.live(stateDir),
                             FileService.live,
                           )
            _           <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                             StateService.live(stateDir),
                             FileService.live,
                           )
            _           <- TestClock.adjust(1.second)
            _           <- StateService.createCheckpoint("test-run-001", TaskStep.Analysis).provide(
                             StateService.live(stateDir),
                             FileService.live,
                           )
            checkpoints <- StateService.listCheckpoints("test-run-001").provide(
                             StateService.live(stateDir),
                             FileService.live,
                           )
          yield assertTrue(
            checkpoints.map(_.step) == List(TaskStep.Discovery, TaskStep.Analysis),
            checkpoints.forall(_.checksum.nonEmpty),
          )
        }
      },
      test("returns empty list for unknown run") {
        withTempStateDir { stateDir =>
          for checkpoints <- StateService.listCheckpoints("unknown-run").provide(
                               StateService.live(stateDir),
                               FileService.live,
                             )
          yield assertTrue(checkpoints.isEmpty)
        }
      },
    ),
    // ========================================================================
    // validateCheckpointIntegrity tests
    // ========================================================================
    suite("validateCheckpointIntegrity")(
      test("succeeds for valid checkpoints") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _         <- StateService.saveState(state).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            _         <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            validated <- StateService.validateCheckpointIntegrity("test-run-001").provide(
                           StateService.live(stateDir),
                           FileService.live,
                         ).either
          yield assertTrue(validated.isRight)
        }
      },
      test("fails when checkpoint file is corrupted") {
        withTempStateDir { stateDir =>
          val state = createTestState()
          for
            _         <- StateService.saveState(state).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            _         <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                           StateService.live(stateDir),
                           FileService.live,
                         )
            _         <- ZIO.succeed(
                           Files.writeString(
                             stateDir.resolve("runs/test-run-001/checkpoints/discovery.json"),
                             """{"not":"a-valid-checkpoint"}""",
                           )
                         )
            validated <- StateService.validateCheckpointIntegrity("test-run-001").provide(
                           StateService.live(stateDir),
                           FileService.live,
                         ).either
          yield assertTrue(validated.isLeft)
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
            _          <- StateService.createCheckpoint("test-run-001", TaskStep.Discovery).provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
            checkpoint <- StateService.getLastCheckpoint("test-run-001").provide(
                            StateService.live(stateDir),
                            FileService.live,
                          )
          yield assertTrue(
            checkpoint.isDefined,
            checkpoint.get == TaskStep.Discovery,
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
                          currentStep = TaskStep.Analysis,
                          completedSteps = Set(TaskStep.Discovery),
                        ).copy(
                          errors = List(
                            TaskError(TaskStep.Discovery, "Error 1", now),
                            TaskError(TaskStep.Discovery, "Error 2", now),
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
            summary.get.currentStep == TaskStep.Analysis,
            summary.get.completedSteps == Set(TaskStep.Discovery),
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
            _          <- StateService.createCheckpoint("integration-run", TaskStep.Discovery).provide(
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
            checkpoint.get == TaskStep.Discovery,
            runs.length == 1,
            runs.head.runId == "integration-run",
          )
        }
      },
      test("multiple runs with different progress") {
        withTempStateDir { stateDir =>
          val state1 = createTestState(runId = "run-1", currentStep = TaskStep.Discovery)
          val state2 = createTestState(
            runId = "run-2",
            currentStep = TaskStep.Analysis,
            completedSteps = Set(TaskStep.Discovery),
          )
          val state3 = createTestState(
            runId = "run-3",
            currentStep = TaskStep.Transformation,
            completedSteps = Set(TaskStep.Discovery, TaskStep.Analysis),
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
