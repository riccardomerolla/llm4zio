package llm4zio.flow

import zio.*
import zio.stream.*

/** A progress event emitted while a flow runs. */
enum FlowEvent:
  case StageStarted(stage: String)
  case StageCompleted(stage: String)
  case StageFailed(stage: String, message: String)
  case Aborted(message: String)
  case Info(message: String)

/** Sink for [[FlowEvent]]s. Flows publish; listeners (a terminal renderer, a
  * Telegram bridge, a test) consume. Default is [[FlowEvents.noop]].
  */
trait FlowEvents:
  def publish(event: FlowEvent): UIO[Unit]

object FlowEvents:
  /** Discards everything. */
  val noop: FlowEvents = _ => ZIO.unit

  /** Captures events into a buffer — handy for tests and simple embedding. */
  final class Collecting private[FlowEvents] (ref: Ref[Chunk[FlowEvent]]) extends FlowEvents:
    def publish(event: FlowEvent): UIO[Unit] = ref.update(_ :+ event)
    def recorded: UIO[Chunk[FlowEvent]]      = ref.get

  def collecting: UIO[Collecting] =
    Ref.make(Chunk.empty[FlowEvent]).map(new Collecting(_))

  /** Broadcasts events to any number of live subscribers. */
  final class Hub private[FlowEvents] (hub: zio.Hub[FlowEvent]) extends FlowEvents:
    def publish(event: FlowEvent): UIO[Unit]               = hub.publish(event).unit
    def subscribe: ZIO[Scope, Nothing, Dequeue[FlowEvent]] = hub.subscribe
    def stream: ZStream[Any, Nothing, FlowEvent]           = ZStream.fromHub(hub)

  def hub(capacity: Int = 64): UIO[Hub] =
    zio.Hub.bounded[FlowEvent](capacity).map(new Hub(_))
