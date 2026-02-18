package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*

import core.{ FileService, StateService }
import models.*

object StateServiceIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  def spec: Spec[Any, Any] = suite("StateServiceIntegrationSpec")(
    test("records run history after saving state") {
      ZIO.scoped {
        for
          stateDir <- ZIO.attemptBlocking(Files.createTempDirectory("it-state-history"))
          state     = baseState("run-history", TaskStep.Analysis)
          runs     <- (for
                        _    <- StateService.saveState(state)
                        runs <- StateService.listRuns()
                      yield runs).provide(
                        FileService.live,
                        StateService.live(stateDir),
                      )
        yield assertTrue(
          runs.exists(r => r.runId == "run-history" && r.currentStep == TaskStep.Analysis)
        )
      }
    },
    test("creates checkpoints and validates integrity") {
      ZIO.scoped {
        for
          stateDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-state-checkpoint"))
          state        = baseState("run-checkpoint", TaskStep.Discovery)
          checkpoints <- (for
                           _           <- StateService.saveState(state)
                           _           <- StateService.createCheckpoint("run-checkpoint", TaskStep.Discovery)
                           _           <- StateService.validateCheckpointIntegrity("run-checkpoint")
                           checkpoints <- StateService.listCheckpoints("run-checkpoint")
                         yield checkpoints).provide(
                           FileService.live,
                           StateService.live(stateDir),
                         )
          steps        = checkpoints.map(_.step).toSet
        yield assertTrue(
          steps.contains(TaskStep.Discovery)
        )
      }
    },
    test("fails integrity validation for corrupted checkpoint") {
      ZIO.scoped {
        for
          stateDir <- ZIO.attemptBlocking(Files.createTempDirectory("it-state-corrupt"))
          runId     = "run-corrupt"
          state     = baseState(runId, TaskStep.Discovery)
          result   <- (for
                        _   <- StateService.saveState(state)
                        _   <- StateService.createCheckpoint(runId, TaskStep.Discovery)
                        _   <- FileService.writeFile(
                                 stateDir
                                   .resolve("runs")
                                   .resolve(runId)
                                   .resolve("checkpoints")
                                   .resolve("discovery.json"),
                                 "{invalid-json}",
                               )
                        out <- StateService.validateCheckpointIntegrity(runId).either
                      yield out).provide(
                        FileService.live,
                        StateService.live(stateDir),
                      )
        yield assertTrue(
          result match
            case Left(StateError.InvalidState(_, reason)) => reason.contains("Invalid checkpoint discovery.json")
            case _                                        => false
        )
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def baseState(runId: String, step: TaskStep): TaskState =
    TaskState(
      runId = runId,
      startedAt = Instant.EPOCH,
      currentStep = step,
      completedSteps = Set.empty,
      artifacts = Map.empty,
      errors = List.empty,
      config = MigrationConfig(
        sourceDir = Path.of("cobol-source"),
        outputDir = Path.of("java-output"),
      ),
      fileInventory = None,
      analyses = List.empty,
      dependencyGraph = None,
      projects = List.empty,
      validationReports = List.empty,
      lastCheckpoint = Instant.EPOCH,
    )
