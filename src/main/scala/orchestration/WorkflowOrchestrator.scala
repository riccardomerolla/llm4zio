package orchestration

import java.time.Instant

import zio.*
import zio.json.*

/** Base workflow primitives and patterns for orchestrators.
  *
  * This module provides reusable ADTs and execution patterns that can be shared across different orchestrator
  * implementations, promoting consistency and reducing duplication.
  */
object WorkflowOrchestrator:

  /** Generic phase/step progress update for callback-based progress tracking.
    *
    * @param phase
    *   The current workflow phase/step being executed
    * @param message
    *   Human-readable progress message
    * @param percent
    *   Progress percentage (0-100)
    */
  case class StepProgressUpdate[Phase](
    phase: Phase,
    message: String,
    percent: Int,
  ) derives JsonCodec

  /** Generic phase execution result.
    *
    * @param phase
    *   The workflow phase that was executed
    * @param success
    *   Whether the phase completed successfully
    * @param error
    *   Optional error message if phase failed
    */
  case class PhaseResult[Phase](
    phase: Phase,
    success: Boolean,
    error: Option[String],
  )

  /** Generic workflow error captured during execution.
    *
    * @param phase
    *   The phase where the error occurred
    * @param message
    *   Human-readable error message
    * @param timestamp
    *   When the error occurred
    */
  case class WorkflowError[Phase](
    phase: Phase,
    message: String,
    timestamp: Instant,
  ) derives JsonCodec

  /** Phase execution helpers - common patterns for running workflow phases.
    */
  object PhaseExecution:

    /** Execute a phase with state tracking, checkpointing, and progress callback.
      *
      * @param name
      *   Human-readable phase name
      * @param phase
      *   Phase identifier
      * @param shouldRun
      *   Whether this phase should execute
      * @param progress
      *   Current progress percentage
      * @param onProgress
      *   Progress callback
      * @param stateRef
      *   Mutable state reference
      * @param errorsRef
      *   Accumulated errors reference
      * @param updateState
      *   Function to update state for this phase
      * @param saveState
      *   Function to persist state
      * @param createCheckpoint
      *   Function to create a checkpoint
      * @param effect
      *   The actual phase logic to execute
      */
    def runPhase[R, E, A, State, Phase](
      name: String,
      phase: Phase,
      shouldRun: Boolean,
      progress: Int,
      onProgress: StepProgressUpdate[Phase] => UIO[Unit],
      stateRef: Ref[State],
      errorsRef: Ref[List[WorkflowError[Phase]]],
      updateState: (State, Phase, Instant) => State,
      markCompleted: (State, Phase, Instant) => State,
      saveState: State => IO[E, Unit],
      createCheckpoint: (State, Phase) => IO[E, Unit],
    )(
      effect: ZIO[R, E, A]
    ): ZIO[R, E, Option[A]] =
      if !shouldRun then ZIO.succeed(None)
      else
        for
          _      <- onProgress(StepProgressUpdate(phase, s"Starting phase: $name", progress))
          _      <- ZIO.logInfo(s"Starting phase: $name")
          before <- stateRef.get
          now    <- Clock.instant
          _      <- stateRef.set(updateState(before, phase, now))
          _      <- stateRef.get.flatMap(saveState)
          out    <- effect.either
          result <- out match
                      case Right(value) =>
                        for
                          checkpointAt <- Clock.instant
                          current      <- stateRef.get
                          _            <- stateRef.set(markCompleted(current, phase, checkpointAt))
                          _            <- stateRef.get.flatMap(saveState)
                          _            <- stateRef.get.flatMap(st => createCheckpoint(st, phase))
                          _            <- onProgress(StepProgressUpdate(phase, s"Completed phase: $name", progress + 10))
                          _            <- ZIO.logInfo(s"Completed phase: $name")
                        yield Some(value)

                      case Left(err) =>
                        for
                          ts <- Clock.instant
                          _  <- errorsRef.update(_ :+ WorkflowError(phase, err.toString, ts))
                          es <- errorsRef.get
                          st <- stateRef.get
                          _  <- stateRef.set(updateState(st, phase, ts))
                          _  <- stateRef.get.flatMap(saveState)
                          _  <- onProgress(StepProgressUpdate(phase, s"Phase failed: $name ($err)", progress + 5))
                          _  <- ZIO.logError(s"Phase failed: $name ($err)")
                        yield None
        yield result

  /** Phase ordering helpers - utilities for determining phase execution order.
    */
  object PhaseOrdering:

    /** Determine if a phase should run based on resume point.
      *
      * @param phase
      *   The phase being considered
      * @param startPhase
      *   The phase to start/resume from
      * @param phaseOrder
      *   Function that maps phase to integer order
      */
    def shouldRunFrom[Phase](
      phase: Phase,
      startPhase: Phase,
      phaseOrder: Phase => Int,
    ): Boolean =
      phaseOrder(phase) >= phaseOrder(startPhase)

    /** Determine if a phase should run based on both resume point and inclusion set.
      *
      * @param phase
      *   The phase being considered
      * @param startPhase
      *   The phase to start/resume from
      * @param phasesToRun
      *   Set of phases that should be executed
      * @param phaseOrder
      *   Function that maps phase to integer order
      */
    def shouldRunPhase[Phase](
      phase: Phase,
      startPhase: Phase,
      phasesToRun: Set[Phase],
      phaseOrder: Phase => Int,
    ): Boolean =
      phasesToRun.contains(phase) && shouldRunFrom(phase, startPhase, phaseOrder)

  /** Fiber management helpers for background orchestration.
    */
  object FiberManagement:

    /** Track a fiber for a run and handle its exit.
      *
      * @param runId
      *   Unique run identifier
      * @param fiberRef
      *   Ref containing active fibers
      * @param fiber
      *   The fiber to track
      * @param onExit
      *   Handler for fiber exit
      */
    def trackFiber[RunId, E, A](
      runId: RunId,
      fiberRef: Ref[Map[RunId, Fiber.Runtime[E, A]]],
      fiber: Fiber.Runtime[E, A],
      onExit: (RunId, Exit[E, A]) => UIO[Unit],
    ): UIO[Unit] =
      for
        _ <- fiberRef.update(_ + (runId -> fiber))
        _ <- fiber.await.flatMap(exit => onExit(runId, exit)).forkDaemon
      yield ()

    /** Cancel a tracked fiber by run ID.
      *
      * @param runId
      *   Run identifier to cancel
      * @param fiberRef
      *   Ref containing active fibers
      */
    def cancelFiber[RunId, E, A](
      runId: RunId,
      fiberRef: Ref[Map[RunId, Fiber.Runtime[E, A]]],
    ): IO[Option[Nothing], Unit] =
      for
        maybeFiber <- fiberRef.modify(current => (current.get(runId), current - runId))
        fiber      <- ZIO.fromOption(maybeFiber)
        _          <- fiber.interrupt.unit
      yield ()

  /** Status determination helpers - utilities for determining final workflow status.
    */
  object StatusDetermination:

    /** Determine workflow status based on errors and artifacts.
      *
      * @param hasErrors
      *   Whether any errors occurred
      * @param hasArtifacts
      *   Whether any successful artifacts were produced
      * @param hasWarnings
      *   Whether any warnings were generated
      * @param completed
      *   Status constructor for successful completion
      * @param completedWithWarnings
      *   Status constructor for completion with warnings
      * @param partialFailure
      *   Status constructor for partial failure
      * @param failed
      *   Status constructor for complete failure
      * @return
      *   The determined workflow status
      */
    def determineStatus[Status](
      hasErrors: Boolean,
      hasArtifacts: Boolean,
      hasWarnings: Boolean,
      completed: Status,
      completedWithWarnings: Status,
      partialFailure: Status,
      failed: Status,
    ): Status =
      if !hasErrors then
        if hasWarnings then completedWithWarnings
        else completed
      else if hasArtifacts then partialFailure
      else failed
