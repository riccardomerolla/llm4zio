package bankmod.app

import java.nio.file.{ Files, Path, StandardWatchEventKinds, WatchKey, WatchService }
import scala.jdk.CollectionConverters.*

import zio.*
import zio.stream.ZStream

import bankmod.graph.model.Schemas
import bankmod.graph.validate.GraphValidator
import bankmod.mcp.GraphStore

/** Keeps a running [[GraphStore]] in sync with a JSON-serialised `Graph` on disk.
  *
  * [[reload]] is the pure primitive: read, decode, validate, swap. It swallows every failure mode (missing file, bad
  * JSON, invariant violations) so the watcher never crashes — failures are logged and the store is left alone.
  *
  * [[watch]] runs an infinite `WatchService` loop over `path`'s parent directory, debounced so a flurry of editor
  * saves collapses to a single reload. macOS's native `WatchService` is polling-based and coarse-grained, so we also
  * trigger an initial reload on startup — the "debounce" is really a quiet-period timer.
  */
object ModelWatcher:

  private val debounce: Duration = 500.millis

  /** Read `path`, decode a `Graph`, run the validator, and atomically swap the store.
    * Never fails — errors are logged and the store is untouched.
    */
  def reload(path: Path): ZIO[GraphStore, Nothing, Unit] =
    val work =
      for
        store <- ZIO.service[GraphStore]
        raw   <- ZIO.attemptBlocking(Files.readString(path))
        g     <- ZIO.fromEither(Schemas.graphCodec.decode(raw))
        _     <- store.update(_ => GraphValidator.validate(g)).mapError(errs => s"invariant violations: ${errs.toList.map(_.getClass.getSimpleName).mkString(", ")}")
      yield ()

    work.catchAll {
      case t: Throwable => ZIO.logWarning(s"ModelWatcher.reload($path) failed: ${t.getMessage}")
      case s: String    => ZIO.logWarning(s"ModelWatcher.reload($path) rejected: $s")
    }

  /** Watch `path`'s parent directory for MODIFY/CREATE events on `path` itself, reload on each,
    * debounced so rapid successive writes collapse to one reload.
    */
  def watch(path: Path): ZIO[GraphStore & Scope, Throwable, Unit] =
    val parent = path.toAbsolutePath.getParent
    val fileName = path.getFileName

    val acquireWatcher: ZIO[Scope, Throwable, WatchService] =
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val ws = parent.getFileSystem.newWatchService()
          parent.register(
            ws,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE,
          )
          ws
        }
      )(ws => ZIO.attemptBlocking(ws.close()).orDie)

    def drainEvents(ws: WatchService): UIO[Boolean] =
      ZIO.attemptBlocking {
        val key: WatchKey = ws.take()
        val hit           = key.pollEvents().asScala.exists { ev =>
          val ctx = ev.context()
          ctx.isInstanceOf[Path] && ctx.asInstanceOf[Path] == fileName
        }
        key.reset()
        hit
      }.orDie

    acquireWatcher.flatMap { ws =>
      // Block on events, debounce, reload, repeat.
      // The store is seeded via ZLayer at boot — the watcher only reacts to file changes.
      ZStream
        .repeatZIO(drainEvents(ws))
        .filter(identity)
        .debounce(debounce)
        .tap(_ => reload(path))
        .runDrain
    }
