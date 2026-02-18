package orchestration

import zio.*

import core.Logger
import db.*
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

  val live: ZLayer[TaskRepository & ActivityHub, Nothing, ProgressTracker] =
    ZLayer.scoped {
      for
        repository  <- ZIO.service[TaskRepository]
        activityHub <- ZIO.service[ActivityHub]
        hub         <- Hub.bounded[ProgressUpdate](256)
        subscribers <- Ref.make(Map.empty[Long, Set[Queue[ProgressUpdate]]])
        hubQueue    <- hub.subscribe
        _           <- hubQueue.take.flatMap(publishToSubscribers(subscribers, _)).forever.forkScoped
      yield ProgressTrackerLive(repository, hub, subscribers, activityHub)
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
  repository: TaskRepository,
  hub: Hub[ProgressUpdate],
  subscribers: Ref[Map[Long, Set[Queue[ProgressUpdate]]]],
  activityHub: ActivityHub,
) extends ProgressTracker:

  override def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- persistIgnoringFailure("startPhase")(
               repository.saveProgress(
                 PhaseProgressRow(
                   id = 0L,
                   runId = runId,
                   phase = phase,
                   status = "Running",
                   itemTotal = total,
                   itemProcessed = 0,
                   errorCount = 0,
                   updatedAt = now,
                 )
               ).unit
             )
      _   <- publish(
               ProgressUpdate(
                 runId = runId,
                 phase = phase,
                 itemsProcessed = 0,
                 itemsTotal = total,
                 message = s"Starting phase: $phase",
                 timestamp = now,
               )
             )
      _   <- activityHub.publish(
               ActivityEvent(
                 eventType = ActivityEventType.RunStarted,
                 source = "progress-tracker",
                 runId = Some(runId),
                 summary = s"Run #$runId started phase: $phase",
                 createdAt = now,
               )
             )
    yield ()

  override def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit] =
    for
      _ <- persistIgnoringFailure("updateProgress") {
             for
               current <- repository.getProgress(update.runId, update.phase)
               _       <- current match
                            case Some(existing) =>
                              repository
                                .updateProgress(
                                  existing.copy(
                                    status = "Running",
                                    itemTotal = update.itemsTotal,
                                    itemProcessed = update.itemsProcessed,
                                    updatedAt = update.timestamp,
                                  )
                                )
                            case None           =>
                              repository
                                .saveProgress(
                                  PhaseProgressRow(
                                    id = 0L,
                                    runId = update.runId,
                                    phase = update.phase,
                                    status = "Running",
                                    itemTotal = update.itemsTotal,
                                    itemProcessed = update.itemsProcessed,
                                    errorCount = 0,
                                    updatedAt = update.timestamp,
                                  )
                                )
                                .unit
             yield ()
           }
      _ <- publish(update)
    yield ()

  override def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit] =
    for
      now     <- Clock.instant
      current <- repository.getProgress(runId, phase).orElseSucceed(None)
      _       <- persistIgnoringFailure("completePhase") {
                   current match
                     case Some(existing) =>
                       repository
                         .updateProgress(
                           existing.copy(
                             status = "Completed",
                             itemProcessed = existing.itemTotal,
                             updatedAt = now,
                           )
                         )
                     case None           =>
                       repository
                         .saveProgress(
                           PhaseProgressRow(
                             id = 0L,
                             runId = runId,
                             phase = phase,
                             status = "Completed",
                             itemTotal = 0,
                             itemProcessed = 0,
                             errorCount = 0,
                             updatedAt = now,
                           )
                         )
                         .unit
                 }
      total    = current.map(_.itemTotal).getOrElse(0)
      _       <- publish(
                   ProgressUpdate(
                     runId = runId,
                     phase = phase,
                     itemsProcessed = total,
                     itemsTotal = total,
                     message = s"Completed phase: $phase",
                     timestamp = now,
                   )
                 )
      _       <- activityHub.publish(
                   ActivityEvent(
                     eventType = ActivityEventType.RunCompleted,
                     source = "progress-tracker",
                     runId = Some(runId),
                     summary = s"Run #$runId completed phase: $phase",
                     createdAt = now,
                   )
                 )
    yield ()

  override def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit] =
    for
      now     <- Clock.instant
      current <- repository.getProgress(runId, phase).orElseSucceed(None)
      _       <- persistIgnoringFailure("failPhase") {
                   current match
                     case Some(existing) =>
                       repository
                         .updateProgress(
                           existing.copy(
                             status = "Failed",
                             errorCount = existing.errorCount + 1,
                             updatedAt = now,
                           )
                         )
                     case None           =>
                       repository
                         .saveProgress(
                           PhaseProgressRow(
                             id = 0L,
                             runId = runId,
                             phase = phase,
                             status = "Failed",
                             itemTotal = 0,
                             itemProcessed = 0,
                             errorCount = 1,
                             updatedAt = now,
                           )
                         )
                         .unit
                 }
      total    = current.map(_.itemTotal).getOrElse(0)
      done     = current.map(_.itemProcessed).getOrElse(0)
      _       <- publish(
                   ProgressUpdate(
                     runId = runId,
                     phase = phase,
                     itemsProcessed = done,
                     itemsTotal = total,
                     message = error,
                     timestamp = now,
                   )
                 )
      _       <- activityHub.publish(
                   ActivityEvent(
                     eventType = ActivityEventType.RunFailed,
                     source = "progress-tracker",
                     runId = Some(runId),
                     summary = s"Run #$runId failed phase: $phase",
                     createdAt = now,
                   )
                 )
    yield ()

  override def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]] =
    for
      queue <- Queue.bounded[ProgressUpdate](256)
      _     <- subscribers.update(current =>
                 current.updated(runId, current.getOrElse(runId, Set.empty) + queue)
               )
    yield queue

  private def publish(update: ProgressUpdate): UIO[Unit] =
    hub.publish(update).unit

  private def persistIgnoringFailure(action: String)(effect: IO[PersistenceError, Unit]): UIO[Unit] =
    effect.catchAll(err => Logger.warn(s"Progress persistence failed in $action: $err"))
