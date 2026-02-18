package orchestration

import java.time.Instant

import zio.*
import zio.test.*

import db.*
import models.ProgressUpdate

object ProgressTrackerSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProgressTrackerSpec")(
    test("startPhase publishes running update") {
      val layer = trackerLayer
      (for
        queue  <- ProgressTracker.subscribe(10L)
        _      <- ProgressTracker.startPhase(10L, "Discovery", 12)
        update <- queue.take.timeoutFail("missing published update")(5.seconds)
      yield assertTrue(
        update.runId == 10L,
        update.phase == "Discovery",
        update.itemsProcessed == 0,
        update.itemsTotal == 12,
      )).provideLayer(layer)
    },
    test("subscribe only receives updates for matching runId") {
      val update = ProgressUpdate(
        runId = 100L,
        phase = "Analysis",
        itemsProcessed = 1,
        itemsTotal = 5,
        message = "one done",
        timestamp = Instant.parse("2026-02-08T00:00:00Z"),
      )

      val layer = trackerLayer
      (for
        queueA <- ProgressTracker.subscribe(100L)
        queueB <- ProgressTracker.subscribe(200L)
        _      <- ProgressTracker.updateProgress(update)
        seenA  <- queueA.take.timeoutFail("matching runId should receive event")(5.seconds)
        seenB  <- queueB.take.timeout(1.second)
      yield assertTrue(
        seenA.runId == 100L,
        seenA.phase == "Analysis",
        seenB.isEmpty,
      )).provideLayer(layer)
    },
    test("completePhase and failPhase publish terminal updates") {
      val layer = trackerLayer
      (for
        queue <- ProgressTracker.subscribe(30L)
        _     <- ProgressTracker.startPhase(30L, "Mapping", 4)
        _     <- queue.take
        _     <- ProgressTracker.completePhase(30L, "Mapping")
        _     <- ProgressTracker.startPhase(30L, "Validation", 3)
        _     <- ProgressTracker.failPhase(30L, "Validation", "Validation failed")

        events   <- ZIO.collectAll(
                      List.fill(3)(queue.take.timeoutFail("missing terminal-sequence event")(5.seconds))
                    )
        completed = events.find(event =>
                      event.phase == "Mapping" && event.message.contains("Completed step")
                    )
        failed    = events.find(event =>
                      event.phase == "Validation" && event.message == "Validation failed"
                    )
      yield assertTrue(
        completed.exists(update => update.itemsProcessed == update.itemsTotal),
        failed.isDefined,
      )).provideLayer(layer)
    },
    test("startPhase always succeeds and publishes") {
      val layer = trackerLayer
      (for
        queue  <- ProgressTracker.subscribe(50L)
        exit   <- ProgressTracker.startPhase(50L, "Transformation", 2).exit
        update <- queue.take.timeoutFail("update should still be published")(5.seconds)
      yield assertTrue(
        exit.isSuccess,
        update.runId == 50L,
        update.phase == "Transformation",
      )).provideLayer(layer)
    },
    test("bounded hub applies backpressure for non-draining subscribers") {
      val layer = trackerLayer
      (for
        _   <- ProgressTracker.subscribe(70L)
        now  = Instant.parse("2026-02-08T00:00:00Z")
        out <- ZIO
                 .foreachDiscard(1 to 600) { i =>
                   ProgressTracker.updateProgress(
                     ProgressUpdate(
                       runId = 70L,
                       phase = "Analysis",
                       itemsProcessed = i,
                       itemsTotal = 1000,
                       message = s"item-$i",
                       timestamp = now,
                     )
                   )
                 }
                 .timeout(5.seconds)
      yield assertTrue(out.isEmpty)).provideLayer(layer)
    },
    test("property: publish N updates, subscriber receives all N") {
      check(Gen.int(1, 80)) { n =>
        val layer = trackerLayer
        (for
          queue <- ProgressTracker.subscribe(99L)
          _     <- ZIO.foreachDiscard(1 to n) { i =>
                     ProgressTracker.updateProgress(
                       ProgressUpdate(
                         runId = 99L,
                         phase = "Discovery",
                         itemsProcessed = i,
                         itemsTotal = n,
                         message = s"progress-$i",
                         timestamp = Instant.parse("2026-02-08T00:00:00Z"),
                       )
                     )
                   }
          seen  <- ZIO.collectAll(
                     List.fill(n)(queue.take.timeoutFail("subscriber missed a published update")(5.seconds))
                   )
        yield assertTrue(
          seen.length == n,
          seen.forall(_.runId == 99L),
          seen.map(_.itemsProcessed) == (1 to n).toList,
        )).provideLayer(layer)
      }
    } @@ TestAspect.samples(20),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def trackerLayer: ZLayer[Any, Nothing, ProgressTracker] =
    stubActivityHubLayer >>> ProgressTracker.live

  private val stubActivityHubLayer: ULayer[web.ActivityHub] =
    ZLayer.fromZIO {
      Ref.make(Set.empty[Queue[_root_.models.ActivityEvent]]).map { subs =>
        web.ActivityHubLive(stubActivityRepo, subs)
      }
    }

  private val stubActivityRepo: db.ActivityRepository = new db.ActivityRepository:
    override def createEvent(event: _root_.models.ActivityEvent): IO[PersistenceError, Long] = ZIO.succeed(1L)
    override def listEvents(
      eventType: Option[_root_.models.ActivityEventType],
      since: Option[java.time.Instant],
      limit: Int,
    ): IO[PersistenceError, List[_root_.models.ActivityEvent]] = ZIO.succeed(Nil)
