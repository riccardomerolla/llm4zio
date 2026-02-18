package orchestration

import zio.*

import db.PersistenceError
import models.{ ActivityEvent, ActivityEventType, ProgressUpdate }
import web.ActivityHub

trait ProgressTracker:
  def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit]
  def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit]
  def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit]
  def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit]
  def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]]

object ProgressTracker:
  def startPhase(runId: Long, phase: String, total: Int): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.startPhase(runId, phase, total))

  def updateProgress(update: ProgressUpdate): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.updateProgress(update))

  def completePhase(runId: Long, phase: String): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.completePhase(runId, phase))

  def failPhase(runId: Long, phase: String, error: String): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.failPhase(runId, phase, error))

  def subscribe(runId: Long): ZIO[ProgressTracker, Nothing, Dequeue[ProgressUpdate]] =
    ZIO.serviceWithZIO[ProgressTracker](_.subscribe(runId))

  final case class StepState(
    status: String,
    total: Int,
    processed: Int,
    errorCount: Int,
  )

  val live: ZLayer[ActivityHub, Nothing, ProgressTracker] =
    ZLayer.scoped {
      for
        activityHub <- ZIO.service[ActivityHub]
        hub         <- Hub.bounded[ProgressUpdate](256)
        subscribers <- Ref.make(Map.empty[Long, Set[Queue[ProgressUpdate]]])
        stepStates  <- Ref.make(Map.empty[(Long, String), StepState])
        hubQueue    <- hub.subscribe
        _           <- hubQueue.take.flatMap(publishToSubscribers(subscribers, _)).forever.forkScoped
      yield ProgressTrackerLive(hub, subscribers, stepStates, activityHub)
    }

  private def publishToSubscribers(
    subscribers: Ref[Map[Long, Set[Queue[ProgressUpdate]]]],
    update: ProgressUpdate,
  ): UIO[Unit] =
    for
      current <- subscribers.get
      targets  = current.getOrElse(update.runId, Set.empty)
      _       <- ZIO.foreachDiscard(targets)(_.offer(update).unit)
    yield ()

final case class ProgressTrackerLive(
  hub: Hub[ProgressUpdate],
  subscribers: Ref[Map[Long, Set[Queue[ProgressUpdate]]]],
  stepStates: Ref[Map[(Long, String), ProgressTracker.StepState]],
  activityHub: ActivityHub,
) extends ProgressTracker:

  override def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- stepStates.update(_.updated((runId, phase), ProgressTracker.StepState("Running", total, 0, 0)))
      _   <- publish(
               ProgressUpdate(
                 runId = runId,
                 phase = phase,
                 itemsProcessed = 0,
                 itemsTotal = total,
                 message = s"Starting step: $phase",
                 timestamp = now,
                 status = "Running",
                 percentComplete = 0.0,
               )
             )
      _   <- publishActivity(runId, s"Run #$runId started step: $phase", ActivityEventType.RunStarted)
    yield ()

  override def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit] =
    for
      _ <- stepStates.update { current =>
             val key      = (update.runId, update.phase)
             val existing = current.getOrElse(key, ProgressTracker.StepState(update.status, update.itemsTotal, 0, 0))
             current.updated(
               key,
               existing.copy(
                 status = update.status,
                 total = update.itemsTotal,
                 processed = update.itemsProcessed,
               ),
             )
           }
      _ <- publish(
             update.copy(
               percentComplete =
                 if update.itemsTotal <= 0 then update.percentComplete
                 else update.itemsProcessed.toDouble / update.itemsTotal.toDouble
             )
           )
    yield ()

  override def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit] =
    for
      now      <- Clock.instant
      previous <- stepStates.get.map(_.get((runId, phase)))
      total     = previous.map(_.total).getOrElse(0)
      _        <- stepStates.update(_.updated((runId, phase), ProgressTracker.StepState("Completed", total, total, 0)))
      _        <- publish(
                    ProgressUpdate(
                      runId = runId,
                      phase = phase,
                      itemsProcessed = total,
                      itemsTotal = total,
                      message = s"Completed step: $phase",
                      timestamp = now,
                      status = "Completed",
                      percentComplete = 1.0,
                    )
                  )
      _        <- publishActivity(runId, s"Run #$runId completed step: $phase", ActivityEventType.RunCompleted)
    yield ()

  override def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit] =
    for
      now      <- Clock.instant
      previous <- stepStates.get.map(_.get((runId, phase)))
      total     = previous.map(_.total).getOrElse(0)
      done      = previous.map(_.processed).getOrElse(0)
      _        <- stepStates.update(
                    _.updated(
                      (runId, phase),
                      ProgressTracker.StepState("Failed", total, done, previous.map(_.errorCount).getOrElse(0) + 1),
                    )
                  )
      _        <- publish(
                    ProgressUpdate(
                      runId = runId,
                      phase = phase,
                      itemsProcessed = done,
                      itemsTotal = total,
                      message = error,
                      timestamp = now,
                      status = "Failed",
                      percentComplete = if total <= 0 then 0.0 else done.toDouble / total.toDouble,
                    )
                  )
      _        <- publishActivity(runId, s"Run #$runId failed step: $phase", ActivityEventType.RunFailed)
    yield ()

  override def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]] =
    for
      queue <- Queue.bounded[ProgressUpdate](256)
      _     <- subscribers.update(current => current.updated(runId, current.getOrElse(runId, Set.empty) + queue))
    yield queue

  private def publish(update: ProgressUpdate): UIO[Unit] =
    hub.publish(update).unit

  private def publishActivity(runId: Long, summary: String, eventType: ActivityEventType): UIO[Unit] =
    Clock.instant.flatMap { now =>
      activityHub.publish(
        ActivityEvent(
          eventType = eventType,
          source = "progress-tracker",
          runId = Some(runId),
          summary = summary,
          createdAt = now,
        )
      )
    }
