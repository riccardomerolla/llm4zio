package llm4zio.runner

import zio.{Console, Scope, ZIO}

import llm4zio.flow.{FlowEvent, FlowEvents}

/** Renders [[FlowEvent]]s to the terminal. */
object TerminalListener:

  /** One line per event. Pure, so it's trivially testable. */
  def render(event: FlowEvent): String = event match
    case FlowEvent.StageStarted(stage)        => s"▶ $stage"
    case FlowEvent.StageCompleted(stage)      => s"✔ $stage"
    case FlowEvent.StageFailed(stage, detail) => s"✗ $stage — $detail"
    case FlowEvent.Aborted(message)           => s"✗ aborted: $message"
    case FlowEvent.Info(message)              => s"  · $message"

  /** Subscribe to a hub and print every event until the scope closes. */
  def consume(events: FlowEvents.Hub): ZIO[Scope, Nothing, Unit] =
    events.stream.foreach(event => Console.printLine(render(event)).orDie).forkScoped.unit
