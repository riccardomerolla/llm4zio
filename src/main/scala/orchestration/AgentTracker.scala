package orchestration

import zio.*

import db.PersistenceError
import models.ProgressUpdate

object AgentTracker:

  /** Wraps any effect with start/fail/complete phase tracking.
    *
    * Tracker persistence errors are swallowed and logged so the wrapped effect keeps its original success/failure
    * semantics.
    */
  def trackPhase[R, E, A](
    runId: Long,
    phase: String,
    totalItems: Int,
    tracker: ProgressTracker,
  )(
    effect: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    for
      _      <- trackerCall("startPhase", tracker.startPhase(runId, phase, totalItems))
      result <- effect.tapError(err =>
                  trackerCall("failPhase", tracker.failPhase(runId, phase, err.toString))
                )
      _      <- trackerCall("completePhase", tracker.completePhase(runId, phase))
    yield result

  /** Tracks batch processing with per-item progress updates.
    *
    * Tracker persistence errors are swallowed and logged so the processing effect keeps its original success/failure
    * semantics.
    */
  def trackBatch[R, E, A, B](
    runId: Long,
    phase: String,
    items: List[A],
    tracker: ProgressTracker,
  )(
    process: (A, Int) => ZIO[R, E, B]
  ): ZIO[R, E, List[B]] =
    for
      _       <- trackerCall("startPhase", tracker.startPhase(runId, phase, items.size))
      results <- ZIO.foreach(items.zipWithIndex) {
                   case (item, idx) =>
                     process(item, idx)
                       .tapError(err => trackerCall("failPhase", tracker.failPhase(runId, phase, err.toString)))
                       .tap { _ =>
                         for
                           now <- Clock.instant
                           _   <- trackerCall(
                                    "updateProgress",
                                    tracker.updateProgress(
                                      ProgressUpdate(
                                        runId = runId,
                                        phase = phase,
                                        itemsProcessed = idx + 1,
                                        itemsTotal = items.size,
                                        message = s"Processing item ${idx + 1}/${items.size}",
                                        timestamp = now,
                                      )
                                    ),
                                  )
                         yield ()
                       }
                 }
      _       <- trackerCall("completePhase", tracker.completePhase(runId, phase))
    yield results

  private def trackerCall(action: String, effect: IO[PersistenceError, Unit]): UIO[Unit] =
    effect.catchAll(err => ZIO.logWarning(s"AgentTracker: tracker $action failed: $err"))
