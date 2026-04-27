package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import shared.ids.Ids.TaskRunId
import shared.testfixtures.StubActivityHub
import taskrun.entity.ProgressUpdate

object ProgressTrackerSpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-04-25T12:00:00Z")

  private def trackerLayer(stub: ActivityHub): ZLayer[Scope, Nothing, ProgressTracker] =
    ZLayer.succeed[ActivityHub](stub) >>> ProgressTracker.live

  /** Builds a ProgressTrackerLive directly, wiring the hub→subscribers routing fiber
    * via forkScoped. This avoids re-using ZLayer.scoped for the subscriber-delivery test.
    */
  private def makeLive(stub: StubActivityHub): URIO[Scope, ProgressTrackerLive] =
    for
      hub         <- Hub.bounded[ProgressUpdate](256)
      subscribers <- Ref.make(Map.empty[String, Set[Queue[ProgressUpdate]]])
      stepStates  <- Ref.make(Map.empty[(String, String), ProgressTracker.StepState])
      hubQueue    <- hub.subscribe
      _           <- hubQueue
                       .take
                       .flatMap { update =>
                         subscribers.get.flatMap { current =>
                           val targets = current.getOrElse(update.runId, Set.empty)
                           ZIO.foreachDiscard(targets)(_.offer(update).unit)
                         }
                       }
                       .forever
                       .forkScoped
    yield ProgressTrackerLive(hub, subscribers, stepStates, stub)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ProgressTrackerSpec")(

    test("startPhase publishes RunStarted activity event") {
      ZIO.scoped {
        for
          stub    <- StubActivityHub.make
          tracker <- ZIO.service[ProgressTracker].provideSomeLayer[Scope](trackerLayer(stub))
          _       <- tracker.startPhase("run-1", "build", 10)
          events  <- stub.published
        yield
          val relevant = events.filter(_.eventType == ActivityEventType.RunStarted)
          assertTrue(
            relevant.size == 1,
            relevant.head.source == "progress-tracker",
            relevant.head.runId == Some(TaskRunId("run-1")),
          )
      }
    },

    test("subscribers receive updates for their runId") {
      ZIO.scoped {
        for
          stub    <- StubActivityHub.make
          tracker <- makeLive(stub)
          queue   <- tracker.subscribe("run-1")
          update   = ProgressUpdate(
                       runId           = "run-1",
                       phase           = "build",
                       itemsProcessed  = 5,
                       itemsTotal      = 10,
                       message         = "halfway",
                       timestamp       = now,
                       status          = "Running",
                       percentComplete = 0.0,
                     )
          _       <- tracker.updateProgress(update)
          result  <- queue.take.timeout(5.seconds)
        yield assertTrue(
          result.isDefined,
          result.exists(_.percentComplete == 0.5),
        )
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.seconds),

    test("completePhase publishes RunCompleted activity event") {
      ZIO.scoped {
        for
          stub    <- StubActivityHub.make
          tracker <- ZIO.service[ProgressTracker].provideSomeLayer[Scope](trackerLayer(stub))
          _       <- tracker.startPhase("run-2", "test", 5)
          _       <- tracker.completePhase("run-2", "test")
          events  <- stub.published
        yield
          val last = events.last
          assertTrue(
            last.eventType == ActivityEventType.RunCompleted,
            last.summary.contains("completed step: test"),
          )
      }
    },

    test("failPhase publishes RunFailed activity event with error message") {
      ZIO.scoped {
        for
          stub    <- StubActivityHub.make
          tracker <- ZIO.service[ProgressTracker].provideSomeLayer[Scope](trackerLayer(stub))
          _       <- tracker.startPhase("run-3", "deploy", 3)
          _       <- tracker.failPhase("run-3", "deploy", "boom")
          events  <- stub.published
        yield
          val last = events.last
          assertTrue(
            last.eventType == ActivityEventType.RunFailed,
            last.summary.contains("failed step: deploy"),
          )
      }
    },

  )
