package llm4zio.observability

import zio.*

/** A sink for low-level provider stream signals — raw output lines and stream errors. Defined in core so providers can
  * emit to it; the flow layer supplies the implementation (a JSONL flight recorder) and installs it via [[current]].
  *
  * The contract is deliberately `UIO`: recording must never fail or interrupt the work it observes.
  */
trait StreamRecorder:
  /** A single raw line read from a provider's stream, before any normalization into `LlmChunk`. */
  def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]

  /** A stream error surfaced by a provider (including the no-chunk empty-stream / malformed-tool-call case). */
  def streamError(provider: String, model: Option[String], message: String): UIO[Unit]

object StreamRecorder:
  /** Discards everything. The ambient default, so providers that run outside a flow record nothing. */
  val noop: StreamRecorder = new StreamRecorder:
    def rawLine(provider: String, model: Option[String], line: String): UIO[Unit]        = ZIO.unit
    def streamError(provider: String, model: Option[String], message: String): UIO[Unit] = ZIO.unit

  /** Ambient recorder for the current fiber and its children. The runner installs a live recorder for the duration of a
    * flow via `current.locallyScoped(recorder)`; providers read it with `current.get`. A top-level `FiberRef` is the
    * ZIO-idiomatic way to thread cross-cutting context without touching every constructor.
    */
  val current: FiberRef[StreamRecorder] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(noop))
