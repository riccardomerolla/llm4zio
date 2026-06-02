package llm4zio.flow

import zio.*

/** Wrap a flow step: emit [[FlowEvent.StageStarted]], run `effect`, then emit [[FlowEvent.StageCompleted]] on success
  * or [[FlowEvent.StageFailed]] on error. The effect's own value and error channel are passed through unchanged.
  */
def stage[R, E, A](name: String)(effect: ZIO[R, E, A])(using events: FlowEvents): ZIO[R, E, A] =
  events.publish(FlowEvent.StageStarted(name)) *>
    effect.tapBoth(
      e => events.publish(FlowEvent.StageFailed(name, e.toString)),
      _ => events.publish(FlowEvent.StageCompleted(name)),
    )

/** Abort the flow with a message: publishes [[FlowEvent.Aborted]] and fails with [[FlowError.Aborted]].
  */
def fail(message: String)(using events: FlowEvents): IO[FlowError, Nothing] =
  events.publish(FlowEvent.Aborted(message)) *> ZIO.fail(FlowError.Aborted(message))
