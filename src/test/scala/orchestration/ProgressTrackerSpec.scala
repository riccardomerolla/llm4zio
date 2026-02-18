package orchestration

import java.time.Instant

import zio.*
import zio.test.*

import db.*
import models.ProgressUpdate

object ProgressTrackerSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProgressTrackerSpec")(
    test("startPhase persists progress row and publishes update") {
      for
        state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
        nextId <- Ref.make(0L)
        layer   = trackerLayer(TestMigrationRepository(state, nextId))
        result <- (for
                    queue  <- ProgressTracker.subscribe(10L)
                    _      <- ProgressTracker.startPhase(10L, "Discovery", 12)
                    rowOpt <- state.get.map(_.get((10L, "Discovery")))
                    update <- queue.take.timeoutFail("missing published update")(5.seconds)
                  yield assertTrue(
                    rowOpt.exists(_.status == "Running"),
                    rowOpt.exists(_.itemTotal == 12),
                    rowOpt.exists(_.itemProcessed == 0),
                    update.runId == 10L,
                    update.phase == "Discovery",
                    update.itemsProcessed == 0,
                    update.itemsTotal == 12,
                  )).provideLayer(layer)
      yield result
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

      for
        state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
        nextId <- Ref.make(0L)
        layer   = trackerLayer(TestMigrationRepository(state, nextId))
        result <- (for
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
      yield result
    },
    test("completePhase and failPhase update terminal status and publish events") {
      for
        state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
        nextId <- Ref.make(0L)
        layer   = trackerLayer(TestMigrationRepository(state, nextId))
        result <- (for
                    queue <- ProgressTracker.subscribe(30L)
                    _     <- ProgressTracker.startPhase(30L, "Mapping", 4)
                    _     <- queue.take
                    _     <- ProgressTracker.completePhase(30L, "Mapping")
                    _     <- ProgressTracker.startPhase(30L, "Validation", 3)
                    _     <- ProgressTracker.failPhase(30L, "Validation", "Validation failed")

                    events   <- ZIO.collectAll(
                                  List.fill(3)(queue.take.timeoutFail("missing terminal-sequence event")(5.seconds))
                                )
                    rows     <- state.get
                    completed = events.find(event =>
                                  event.phase == "Mapping" && event.message.contains("Completed phase")
                                )
                    failed    = events.find(event =>
                                  event.phase == "Validation" && event.message == "Validation failed"
                                )
                  yield assertTrue(
                    rows.get((30L, "Mapping")).exists(_.status == "Completed"),
                    rows.get((30L, "Mapping")).exists(row => row.itemProcessed == row.itemTotal),
                    rows.get((30L, "Validation")).exists(_.status == "Failed"),
                    rows.get((30L, "Validation")).exists(_.errorCount == 1),
                    completed.exists(update => update.itemsProcessed == update.itemsTotal),
                    failed.isDefined,
                  )).provideLayer(layer)
      yield result
    },
    test("progress still publishes when persistence fails") {
      for
        state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
        nextId <- Ref.make(0L)
        layer   = trackerLayer(TestMigrationRepository(state, nextId, failWrites = true, failReads = true))
        result <- (for
                    queue  <- ProgressTracker.subscribe(50L)
                    exit   <- ProgressTracker.startPhase(50L, "Transformation", 2).exit
                    update <- queue.take.timeoutFail("update should still be published")(5.seconds)
                  yield assertTrue(
                    exit.isSuccess,
                    update.runId == 50L,
                    update.phase == "Transformation",
                  )).provideLayer(layer)
      yield result
    },
    test("bounded hub applies backpressure for non-draining subscribers") {
      for
        state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
        nextId <- Ref.make(0L)
        layer   = trackerLayer(TestMigrationRepository(state, nextId))
        result <- (for
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
      yield result
    },
    test("property: publish N updates, subscriber receives all N") {
      check(Gen.int(1, 80)) { n =>
        for
          state  <- Ref.make(Map.empty[(Long, String), PhaseProgressRow])
          nextId <- Ref.make(0L)
          layer   = trackerLayer(TestMigrationRepository(state, nextId))
          result <- (for
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
        yield result
      }
    } @@ TestAspect.samples(20),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def trackerLayer(repo: TaskRepository): ZLayer[Any, Nothing, ProgressTracker] =
    (ZLayer.succeed(repo) ++ stubActivityHubLayer) >>> ProgressTracker.live

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

  final private case class TestMigrationRepository(
    progressRows: Ref[Map[(Long, String), PhaseProgressRow]],
    nextProgressId: Ref[Long],
    failWrites: Boolean = false,
    failReads: Boolean = false,
  ) extends TaskRepository:

    override def saveProgress(progress: PhaseProgressRow): IO[PersistenceError, Long] =
      if failWrites then ZIO.fail(PersistenceError.QueryFailed("saveProgress", "forced failure"))
      else
        for
          id <- nextProgressId.updateAndGet(_ + 1)
          _  <- progressRows.update(_.updated((progress.runId, progress.phase), progress.copy(id = id)))
        yield id

    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      if failReads then ZIO.fail(PersistenceError.QueryFailed("getProgress", "forced failure"))
      else progressRows.get.map(_.get((runId, phase)))

    override def updateProgress(progress: PhaseProgressRow): IO[PersistenceError, Unit] =
      if failWrites then ZIO.fail(PersistenceError.QueryFailed("updateProgress", "forced failure"))
      else
        progressRows.modify { current =>
          current.get((progress.runId, progress.phase)) match
            case Some(_) => ((), current.updated((progress.runId, progress.phase), progress))
            case None    => ((), current)
        }

    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                       =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                       =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                   =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]    =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                              =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]             =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]         =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]         =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]  =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]      =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]] =
      ZIO.dieMessage("unused in ProgressTrackerSpec")
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                       = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]            = ZIO.none
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]        = ZIO.unit
