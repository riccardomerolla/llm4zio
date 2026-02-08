package orchestration

import zio.*
import zio.test.*

import db.*
import models.ProgressUpdate

object AgentTrackerSpec extends ZIOSpecDefault:

  private enum TestErr:
    case Boom

  private enum TrackerEvent:
    case Started(runId: Long, phase: String, total: Int)
    case Updated(update: ProgressUpdate)
    case Completed(runId: Long, phase: String)
    case Failed(runId: Long, phase: String, error: String)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentTrackerSpec")(
    test("trackPhase reports start and complete on success") {
      for
        events  <- Ref.make(List.empty[TrackerEvent])
        tracker <- TestProgressTracker.make(events)
        value   <- AgentTracker.trackPhase(1L, "Discovery", 2, tracker)(ZIO.succeed("ok"))
        all     <- events.get
      yield assertTrue(
        value == "ok",
        all.contains(TrackerEvent.Started(1L, "Discovery", 2)),
        all.contains(TrackerEvent.Completed(1L, "Discovery")),
      )
    },
    test("trackPhase reports failPhase and preserves original error") {
      for
        events  <- Ref.make(List.empty[TrackerEvent])
        tracker <- TestProgressTracker.make(events)
        exit    <- AgentTracker.trackPhase(2L, "Analysis", 1, tracker)(ZIO.fail(TestErr.Boom)).exit
        all     <- events.get
      yield assertTrue(
        exit == Exit.fail(TestErr.Boom),
        all.exists {
          case TrackerEvent.Failed(2L, "Analysis", msg) => msg.contains("Boom")
          case _                                        => false
        },
      )
    },
    test("tracker failures are swallowed; wrapped effect result is unchanged") {
      for
        events  <- Ref.make(List.empty[TrackerEvent])
        tracker <- TestProgressTracker.make(
                     events = events,
                     failStart = true,
                     failUpdate = true,
                     failComplete = true,
                     failFail = true,
                   )
        ok      <- AgentTracker.trackPhase(3L, "Mapping", 1, tracker)(ZIO.succeed(99))
        failed  <- AgentTracker.trackPhase(3L, "Mapping", 1, tracker)(ZIO.fail(TestErr.Boom)).exit
      yield assertTrue(
        ok == 99,
        failed == Exit.fail(TestErr.Boom),
      )
    },
    test("trackBatch emits per-item progress and complete") {
      for
        events  <- Ref.make(List.empty[TrackerEvent])
        tracker <- TestProgressTracker.make(events)
        out     <- AgentTracker.trackBatch(9L, "Transformation", List(10, 20, 30), tracker) { (item, _) =>
                     ZIO.succeed(item / 10)
                   }
        all     <- events.get
        updates  = all.collect { case TrackerEvent.Updated(update) => update }
      yield assertTrue(
        out == List(1, 2, 3),
        updates.length == 3,
        updates.map(_.itemsProcessed).sorted == List(1, 2, 3),
        updates.forall(_.itemsTotal == 3),
        all.contains(TrackerEvent.Completed(9L, "Transformation")),
      )
    },
    test("trackBatch calls failPhase when one item fails and propagates the process error") {
      for
        events  <- Ref.make(List.empty[TrackerEvent])
        tracker <- TestProgressTracker.make(events)
        exit    <- AgentTracker.trackBatch(10L, "Validation", List("a", "b", "c"), tracker) { (item, idx) =>
                     if idx == 1 then ZIO.fail(TestErr.Boom) else ZIO.succeed(item)
                   }.exit
        all     <- events.get
      yield assertTrue(
        exit == Exit.fail(TestErr.Boom),
        all.exists {
          case TrackerEvent.Failed(10L, "Validation", msg) => msg.contains("Boom")
          case _                                           => false
        },
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  final private case class TestProgressTracker(
    events: Ref[List[TrackerEvent]],
    failStart: Boolean,
    failUpdate: Boolean,
    failComplete: Boolean,
    failFail: Boolean,
  ) extends ProgressTracker:

    override def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit] =
      for
        _ <- events.update(TrackerEvent.Started(runId, phase, total) :: _)
        _ <- if failStart then ZIO.fail(PersistenceError.QueryFailed("startPhase", "forced")) else ZIO.unit
      yield ()

    override def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit] =
      for
        _ <- events.update(TrackerEvent.Updated(update) :: _)
        _ <- if failUpdate then ZIO.fail(PersistenceError.QueryFailed("updateProgress", "forced")) else ZIO.unit
      yield ()

    override def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit] =
      for
        _ <- events.update(TrackerEvent.Completed(runId, phase) :: _)
        _ <- if failComplete then ZIO.fail(PersistenceError.QueryFailed("completePhase", "forced")) else ZIO.unit
      yield ()

    override def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit] =
      for
        _ <- events.update(TrackerEvent.Failed(runId, phase, error) :: _)
        _ <- if failFail then ZIO.fail(PersistenceError.QueryFailed("failPhase", "forced")) else ZIO.unit
      yield ()

    override def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]] =
      Queue.unbounded[ProgressUpdate]

  private object TestProgressTracker:
    def make(
      events: Ref[List[TrackerEvent]],
      failStart: Boolean = false,
      failUpdate: Boolean = false,
      failComplete: Boolean = false,
      failFail: Boolean = false,
    ): UIO[TestProgressTracker] =
      ZIO.succeed(
        TestProgressTracker(
          events = events,
          failStart = failStart,
          failUpdate = failUpdate,
          failComplete = failComplete,
          failFail = failFail,
        )
      )
