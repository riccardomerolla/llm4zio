package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, StandardOpenOption }

import zio.*
import zio.stream.ZStream

import llm4zio.observability.StreamRecorder

/** A per-run flight recorder. Serializes high-level [[FlowEvent]]s (via [[consume]]/[[record]]) and low-level provider
  * signals (via the [[StreamRecorder]] interface) to a single JSONL file. A [[Semaphore]] makes `seq` order equal write
  * order across the concurrent hub subscriber and provider fibers. Writing never fails the flow: an I/O error flips
  * `degraded` and logs once.
  */
final class FlowRecorder private (
  path: Path,
  val runId: String,
  seq: Ref[Long],
  lock: Semaphore,
  degraded: Ref[Boolean],
) extends StreamRecorder:

  private def append(event: TraceEvent): UIO[Unit] =
    lock.withPermit {
      degraded.get.flatMap {
        case true  => ZIO.unit
        case false =>
          for
            n  <- seq.getAndUpdate(_ + 1)
            ts <- Clock.instant
            ln  = TraceLine(n, ts.toString, runId, event.kind, event.fields).toJson
            _  <- ZIO
                    .attemptBlocking {
                      Files.write(
                        path,
                        (ln + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                      )
                      ()
                    }
                    .catchAll(e =>
                      ZIO.logWarning(s"flow trace disabled (write failed: ${e.getMessage})") *> degraded.set(true)
                    )
          yield ()
      }
    }

  /** The trace file this recorder writes to. */
  def tracePath: Path = path

  def record(event: FlowEvent): UIO[Unit]                                              = append(TraceEvent.fromFlow(event))
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]        =
    append(TraceEvent.RawLine(provider, model, line))
  def streamError(provider: String, model: Option[String], message: String): UIO[Unit] =
    append(TraceEvent.StreamError(provider, model, message))

  /** Fork a subscriber that records every event from `hub` until the scope closes. Subscribes synchronously before
    * forking so no event published after the call is missed (a lazy `ZStream.fromHub` would race with early publishes).
    */
  def consume(hub: FlowEvents.Hub): ZIO[Scope, Nothing, Unit] =
    for
      queue <- hub.subscribe
      _     <- ZStream.fromQueue(queue).foreach(record).forkScoped
    yield ()

object FlowRecorder:
  /** Open a recorder for `path`. Creating the parent directory is best-effort: if it fails, the recorder starts already
    * degraded (records nothing) rather than failing. Returns a `UIO` — opening a flight recorder must never break a
    * run.
    */
  def open(path: Path, runId: String): UIO[FlowRecorder] =
    for
      seq      <- Ref.make(0L)
      lock     <- Semaphore.make(1)
      created  <- ZIO.attemptBlocking(Option(path.getParent).foreach(Files.createDirectories(_))).either
      degraded <- Ref.make(created.isLeft)
    yield new FlowRecorder(path, runId, seq, lock, degraded)

  /** Prune old traces, open a fresh `trace-<runId>.jsonl` under `dir`, subscribe to `hub`, and install the recorder as
    * the ambient [[StreamRecorder]] for the current scope. Returns the recorder. Pure flow/core — no HTTP, so the
    * runner stays a thin caller.
    */
  def install(
    hub: FlowEvents.Hub,
    dir: Path,
    keep: Int,
    rawTerminalSink: Option[String => UIO[Unit]] = None,
  ): ZIO[Scope, Nothing, FlowRecorder] =
    for
      _      <- FlowTrace.prune(dir, keep)
      runId  <- FlowTrace.runId
      rec    <- open(dir.resolve(s"trace-$runId.jsonl"), runId)
      _      <- rec.consume(hub)
      ambient = rawTerminalSink.fold[StreamRecorder](rec)(sink => new Tee(rec, sink))
      _      <- StreamRecorder.current.locallyScoped(ambient)
    yield rec
