package integration

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.config.{ EclipseStoreConfig, StorageTarget }
import io.github.riccardomerolla.zio.eclipsestore.domain.RootContainer
import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import io.github.riccardomerolla.zio.eclipsestore.service.{ EclipseStoreService, LifecycleCommand }
import org.eclipse.store.storage.embedded.types.EmbeddedStorage

/** Diagnostic spec: tests raw EclipseStoreService restart to isolate the persistence bug. */
object StoreRestartIssueE2ESpec extends ZIOSpecDefault:

  private def runWithClockAdvance[R, E, A](
    effect: ZIO[R, E, A],
    tick: Duration = 1.second,
    maxTicks: Int = 300,
  ): ZIO[R, E, A] =
    def awaitWithClock(fiber: Fiber[E, A], remainingTicks: Int): ZIO[Any, E, A] =
      fiber.poll.flatMap {
        case Some(exit) =>
          exit match
            case Exit.Success(value) => ZIO.succeed(value)
            case Exit.Failure(cause) => ZIO.failCause(cause)
        case None       =>
          if remainingTicks <= 0 then fiber.interrupt *> ZIO.dieMessage("timed out while advancing TestClock")
          else TestClock.adjust(tick) *> awaitWithClock(fiber, remainingTicks - 1)
      }

    for
      fiber  <- effect.fork
      result <- awaitWithClock(fiber, maxTicks)
    yield result

  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("store-restart-e2e-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files
            .walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path =>
              val _ = Files.deleteIfExists(path)
            )
      }.ignore
    )(use)

  private def rawLayer(dir: Path): ZLayer[Any, EclipseStoreError, EclipseStoreService] =
    ZLayer.succeed(
      EclipseStoreConfig(
        storageTarget = StorageTarget.FileSystem(dir),
        autoCheckpointInterval = Some(java.time.Duration.ofSeconds(5L)),
      )
    ) >>> EclipseStoreService.live.fresh

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StoreRestartIssueE2ESpec")(
      test("diagnose what storageManager.root() returns after restart") {
        withTempDir { dir =>
          val storePath = dir.resolve("raw-store")

          // Phase 1: Write via EclipseStoreService, checkpoint, close
          val writePhase =
            (for
              svc <- ZIO.service[EclipseStoreService]
              _   <- svc.put("key1", "value1")
              _   <- svc.put("key2", "value2")
              _   <- svc.maintenance(LifecycleCommand.Checkpoint)
            yield ()).provideLayer(rawLayer(storePath))

          // Phase 2: Open raw EmbeddedStorage directly (no ZIO wrapper) and inspect
          val inspectPhase = ZIO.attemptBlocking {
            val sm = EmbeddedStorage.start(storePath)
            try
              val root = sm.root()
              root match
                case rc: RootContainer =>
                  // Access private field via reflection
                  val field = classOf[RootContainer].getDeclaredField("instances")
                  field.setAccessible(true)
                  val state =
                    field.get(rc).asInstanceOf[java.util.concurrent.ConcurrentHashMap[String, AnyRef]]
                  val stateSize   = state.size()
                  val stateKeys   =
                    scala.jdk.CollectionConverters.SetHasAsScala(state.keySet()).asScala.toSet
                  val kvRootEntry = Option(state.get("kv-root"))
                  val kvRootClass = kvRootEntry.map(_.getClass.getName)
                  val kvRootSize  = kvRootEntry.collect {
                    case m: java.util.concurrent.ConcurrentHashMap[?, ?] => m.size()
                  }
                  (s"RootContainer", stateSize, stateKeys, kvRootClass, kvRootSize)
                case other              =>
                  (
                    s"${if other == null then "null" else other.getClass.getName}",
                    -1,
                    Set.empty[String],
                    None: Option[String],
                    None: Option[Int],
                  )
            finally
              val _ = sm.shutdown()
          }.orDie

          for
            _                                                        <- runWithClockAdvance(writePhase)
            _                                                        <- ZIO.logInfo("--- Write phase done, store closed ---")
            result                                                   <- inspectPhase
            (rootType, stateSize, stateKeys, kvRootClass, kvRootSize) = result
            _                                                        <- ZIO.logInfo(s"Root type: $rootType")
            _                                                        <- ZIO.logInfo(s"instanceState size: $stateSize, keys: $stateKeys")
            _                                                        <- ZIO.logInfo(s"kv-root entry class: $kvRootClass")
            _                                                        <- ZIO.logInfo(s"kv-root ConcurrentHashMap size: $kvRootSize")
          yield assertTrue(
            rootType == "RootContainer",
            kvRootSize.exists(_ > 0), // kv-root should have data
          )
        }
      },
    )
