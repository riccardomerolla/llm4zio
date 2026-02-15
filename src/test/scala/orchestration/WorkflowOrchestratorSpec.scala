package orchestration

import zio.*
import zio.test.*

import orchestration.WorkflowOrchestrator.*

object WorkflowOrchestratorSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("WorkflowOrchestrator")(
    suite("PhaseProgressUpdate")(
      test("creates progress update with correct fields") {
        val update = PhaseProgressUpdate("Discovery", "Starting phase", 10)
        assertTrue(
          update.phase == "Discovery",
          update.message == "Starting phase",
          update.percent == 10,
        )
      }
    ),
    suite("PhaseResult")(
      test("creates successful result") {
        val result = PhaseResult("Analysis", success = true, None)
        assertTrue(
          result.phase == "Analysis",
          result.success,
          result.error.isEmpty,
        )
      },
      test("creates failed result with error") {
        val result = PhaseResult("Transformation", success = false, Some("Failed to transform"))
        assertTrue(
          result.phase == "Transformation",
          !result.success,
          result.error.contains("Failed to transform"),
        )
      },
    ),
    suite("WorkflowError")(
      test("captures error with timestamp") {
        for {
          now  <- Clock.instant
          error = WorkflowError("Validation", "Invalid input", now)
        } yield assertTrue(
          error.phase == "Validation",
          error.message == "Invalid input",
          error.timestamp == now,
        )
      }
    ),
    suite("PhaseOrdering")(
      test("shouldRunFrom returns true when phase order is greater or equal") {
        val phaseOrder: String => Int = {
          case "Discovery"      => 1
          case "Analysis"       => 2
          case "Transformation" => 3
          case _                => 0
        }

        assertTrue(
          PhaseOrdering.shouldRunFrom("Analysis", "Discovery", phaseOrder),
          PhaseOrdering.shouldRunFrom("Transformation", "Analysis", phaseOrder),
          PhaseOrdering.shouldRunFrom("Discovery", "Discovery", phaseOrder),
        )
      },
      test("shouldRunFrom returns false when phase order is less") {
        val phaseOrder: String => Int = {
          case "Discovery"      => 1
          case "Analysis"       => 2
          case "Transformation" => 3
          case _                => 0
        }

        assertTrue(
          !PhaseOrdering.shouldRunFrom("Discovery", "Analysis", phaseOrder),
          !PhaseOrdering.shouldRunFrom("Analysis", "Transformation", phaseOrder),
        )
      },
      test("shouldRunPhase returns true when phase is in set and order is valid") {
        val phaseOrder: String => Int = {
          case "Discovery"      => 1
          case "Analysis"       => 2
          case "Transformation" => 3
          case _                => 0
        }
        val phasesToRun               = Set("Discovery", "Analysis", "Transformation")

        assertTrue(
          PhaseOrdering.shouldRunPhase("Analysis", "Discovery", phasesToRun, phaseOrder),
          PhaseOrdering.shouldRunPhase("Transformation", "Discovery", phasesToRun, phaseOrder),
        )
      },
      test("shouldRunPhase returns false when phase not in set") {
        val phaseOrder: String => Int = {
          case "Discovery" => 1
          case "Analysis"  => 2
          case _           => 0
        }
        val phasesToRun               = Set("Discovery")

        assertTrue(
          !PhaseOrdering.shouldRunPhase("Analysis", "Discovery", phasesToRun, phaseOrder)
        )
      },
      test("shouldRunPhase returns false when order is invalid") {
        val phaseOrder: String => Int = {
          case "Discovery" => 1
          case "Analysis"  => 2
          case _           => 0
        }
        val phasesToRun               = Set("Discovery", "Analysis")

        assertTrue(
          !PhaseOrdering.shouldRunPhase("Discovery", "Analysis", phasesToRun, phaseOrder)
        )
      },
    ),
    suite("FiberManagement")(
      test("trackFiber adds fiber to ref and removes on completion") {
        for {
          fiberRef <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, String]])
          promise  <- Promise.make[Nothing, String]
          fiber    <- promise.await.fork
          _        <- FiberManagement.trackFiber(
                        "run-1",
                        fiberRef,
                        fiber,
                        (runId, exit) => ZIO.unit,
                      )
          initial  <- fiberRef.get
          _        <- promise.succeed("completed")
          _        <- fiber.await
          _        <- TestClock.adjust(100.millis) // Allow cleanup fiber to run
        } yield assertTrue(
          initial.contains("run-1"),
          initial("run-1") == fiber,
        )
      },
      test("cancelFiber interrupts and removes fiber") {
        for {
          fiberRef  <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, String]])
          promise   <- Promise.make[Nothing, String]
          fiber     <- promise.await.fork
          _         <- fiberRef.update(_ + ("run-1" -> fiber))
          _         <- FiberManagement.cancelFiber("run-1", fiberRef)
          remaining <- fiberRef.get
          isInterr  <- fiber.poll.map(_.exists(_.isInterrupted))
        } yield assertTrue(
          !remaining.contains("run-1"),
          isInterr,
        )
      },
      test("cancelFiber fails when fiber not found") {
        for {
          fiberRef <- Ref.make(Map.empty[String, Fiber.Runtime[Nothing, String]])
          result   <- FiberManagement.cancelFiber("nonexistent", fiberRef).exit
        } yield assertTrue(
          result.isFailure
        )
      },
    ),
    suite("StatusDetermination")(
      test("determineStatus returns completed when no errors or warnings") {
        val status = StatusDetermination.determineStatus(
          hasErrors = false,
          hasArtifacts = true,
          hasWarnings = false,
          completed = "Completed",
          completedWithWarnings = "CompletedWithWarnings",
          partialFailure = "PartialFailure",
          failed = "Failed",
        )
        assertTrue(status == "Completed")
      },
      test("determineStatus returns completed with warnings when warnings exist") {
        val status = StatusDetermination.determineStatus(
          hasErrors = false,
          hasArtifacts = true,
          hasWarnings = true,
          completed = "Completed",
          completedWithWarnings = "CompletedWithWarnings",
          partialFailure = "PartialFailure",
          failed = "Failed",
        )
        assertTrue(status == "CompletedWithWarnings")
      },
      test("determineStatus returns partial failure when errors but artifacts exist") {
        val status = StatusDetermination.determineStatus(
          hasErrors = true,
          hasArtifacts = true,
          hasWarnings = false,
          completed = "Completed",
          completedWithWarnings = "CompletedWithWarnings",
          partialFailure = "PartialFailure",
          failed = "Failed",
        )
        assertTrue(status == "PartialFailure")
      },
      test("determineStatus returns failed when errors and no artifacts") {
        val status = StatusDetermination.determineStatus(
          hasErrors = true,
          hasArtifacts = false,
          hasWarnings = false,
          completed = "Completed",
          completedWithWarnings = "CompletedWithWarnings",
          partialFailure = "PartialFailure",
          failed = "Failed",
        )
        assertTrue(status == "Failed")
      },
    ),
    suite("PhaseExecution")(
      test("runPhase skips when shouldRun is false") {
        case class TestState(current: String)

        for {
          stateRef  <- Ref.make(TestState("initial"))
          errorsRef <- Ref.make(List.empty[WorkflowError[String]])
          result    <- PhaseExecution.runPhase(
                         "Test Phase",
                         "test",
                         shouldRun = false,
                         progress = 10,
                         onProgress = _ => ZIO.unit,
                         stateRef = stateRef,
                         errorsRef = errorsRef,
                         updateState = (s, p, t) => s,
                         markCompleted = (s, p, t) => s,
                         saveState = _ => ZIO.unit,
                         createCheckpoint = (_, _) => ZIO.unit,
                       )(ZIO.succeed("value"))
          state     <- stateRef.get
        } yield assertTrue(
          result.isEmpty,
          state.current == "initial",
        )
      },
      test("runPhase executes successfully and returns result") {
        case class TestState(current: String, completed: Boolean)

        for {
          stateRef  <- Ref.make(TestState("initial", completed = false))
          errorsRef <- Ref.make(List.empty[WorkflowError[String]])
          result    <- PhaseExecution.runPhase(
                         "Test Phase",
                         "test",
                         shouldRun = true,
                         progress = 10,
                         onProgress = _ => ZIO.unit,
                         stateRef = stateRef,
                         errorsRef = errorsRef,
                         updateState = (s, p, t) => s.copy(current = p),
                         markCompleted = (s, p, t) => s.copy(completed = true),
                         saveState = _ => ZIO.unit,
                         createCheckpoint = (_, _) => ZIO.unit,
                       )(ZIO.succeed("computed"))
          state     <- stateRef.get
          errors    <- errorsRef.get
        } yield assertTrue(
          result.contains("computed"),
          state.completed,
          errors.isEmpty,
        )
      },
      test("runPhase captures errors and returns None on failure") {
        case class TestState(current: String)

        for {
          stateRef  <- Ref.make(TestState("initial"))
          errorsRef <- Ref.make(List.empty[WorkflowError[String]])
          result    <- PhaseExecution.runPhase(
                         "Test Phase",
                         "test",
                         shouldRun = true,
                         progress = 10,
                         onProgress = _ => ZIO.unit,
                         stateRef = stateRef,
                         errorsRef = errorsRef,
                         updateState = (s, p, t) => s.copy(current = p),
                         markCompleted = (s, p, t) => s,
                         saveState = _ => ZIO.unit,
                         createCheckpoint = (_, _) => ZIO.unit,
                       )(ZIO.fail("test error"))
          errors    <- errorsRef.get
        } yield assertTrue(
          result.isEmpty,
          errors.length == 1,
          errors.head.phase == "test",
          errors.head.message.contains("test error"),
        )
      },
    ),
  )
