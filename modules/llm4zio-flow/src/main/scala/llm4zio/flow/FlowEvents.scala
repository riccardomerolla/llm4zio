package llm4zio.flow

import zio.*
import zio.stream.*

import llm4zio.core.TokenUsage

/** A progress event emitted while a flow runs. */
enum FlowEvent:
  case StageStarted(stage: String)
  case StageCompleted(stage: String)
  case StageFailed(stage: String, message: String)
  case Aborted(message: String)
  case Info(message: String)
  case ToolUse(tool: String, args: String)
  case AssistantMessage(text: String)
  case TokensUsed(agent: String, model: Option[String], usage: TokenUsage)
  // Capability audit trail (issue #716). Used is emitted only for publish-grade operations (push, PR/comment, exec).
  case CapabilityUsed(capability: String, operation: String)
  case CapabilityDenied(capability: String, operation: String)
  case CapabilityUnenforceable(detail: String)
  case Declassified(label: String)

/** Sink for [[FlowEvent]]s. Flows publish; listeners (a terminal renderer, a trace recorder, a test) consume. Default
  * is [[FlowEvents.noop]].
  */
trait FlowEvents:
  def publish(event: FlowEvent): UIO[Unit]

object FlowEvents:
  /** Discards everything. */
  val noop: FlowEvents = _ => ZIO.unit

  /** Derive the sink from an in-scope [[FlowContext]], so a flow body written as `FlowContext ?=> …` never needs a
    * `given FlowEvents = ctx.events` line. Lives in the companion: implicit scope finds it with no extra import.
    */
  given fromContext(using ctx: FlowContext): FlowEvents = ctx.events

  /** Captures events into a buffer — handy for tests and simple embedding. */
  final class Collecting private[FlowEvents] (ref: Ref[Chunk[FlowEvent]]) extends FlowEvents:
    def publish(event: FlowEvent): UIO[Unit] = ref.update(_ :+ event)
    def recorded: UIO[Chunk[FlowEvent]]      = ref.get

  def collecting: UIO[Collecting] =
    Ref.make(Chunk.empty[FlowEvent]).map(new Collecting(_))

  /** Broadcasts events to any number of live subscribers. */
  final class Hub private[FlowEvents] (hub: zio.Hub[FlowEvent], published: Ref[Long]) extends FlowEvents:
    // Count before offering, so `publishedCount` is always >= what any consumer has processed.
    def publish(event: FlowEvent): UIO[Unit]               = published.update(_ + 1) *> hub.publish(event).unit
    def subscribe: ZIO[Scope, Nothing, Dequeue[FlowEvent]] = hub.subscribe
    def stream: ZStream[Any, Nothing, FlowEvent]           = ZStream.fromHub(hub)

    /** Total events published so far. Paired with a consumer's processed count, this lets a listener drain trailing
      * events (notably a final `StageFailed`) before the run's scope tears the consumer down.
      */
    def publishedCount: UIO[Long] = published.get

  def hub(capacity: Int = 64): UIO[Hub] =
    for
      h <- zio.Hub.bounded[FlowEvent](capacity)
      p <- Ref.make(0L)
    yield new Hub(h, p)
