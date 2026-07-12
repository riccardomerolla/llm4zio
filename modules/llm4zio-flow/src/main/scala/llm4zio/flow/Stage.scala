package llm4zio.flow

import zio.*

import llm4zio.core.LlmError

/** Wrap a flow step: emit [[FlowEvent.StageStarted]], run `effect`, then emit [[FlowEvent.StageCompleted]] on success
  * or [[FlowEvent.StageFailed]] on error. The effect's own value and error channel are passed through unchanged.
  */
def stage[R, E, A](name: String)(effect: ZIO[R, E, A])(using events: FlowEvents): ZIO[R, E, A] =
  events.publish(FlowEvent.StageStarted(name)) *>
    effect.tapBoth(
      e => events.publish(FlowEvent.StageFailed(name, describeError(e))),
      _ => events.publish(FlowEvent.StageCompleted(name)),
    )

/** A human-readable one-liner for a failure detail: a [[FlowError]]'s `message` (not its case-class `toString`, which
  * leaks `Llm(…,None)` noise), a Throwable's message, otherwise `toString`. A wrapped structured-output
  * [[LlmError.ParseError]] additionally renders a bounded snippet of the raw provider output — the message alone
  * ("failed to parse…") hides WHAT the model actually returned, which is exactly what a debugging user needs. Public so
  * alternative surfaces (the Java facade's stage) render failures identically to this combinator.
  */
def describeError(e: Any): String = e match
  case FlowError.Llm(message, Some(LlmError.ParseError(_, raw))) if !raw.isBlank =>
    s"$message\nraw output (${raw.length} chars): ${boundedSnippet(raw)}"
  case fe: FlowError                                                             => fe.message
  case t: Throwable                                                              => Option(t.getMessage).getOrElse(t.toString)
  case other                                                                     => String.valueOf(other)

/** Head + tail of a long text, bounded for a terminal/trace line. */
private def boundedSnippet(text: String, head: Int = 600, tail: Int = 200): String =
  val t = text.strip
  if t.length <= head + tail then t
  else s"${t.take(head)}\n… [${t.length - head - tail} chars elided] …\n${t.takeRight(tail)}"

/** Abort the flow with a message: publishes [[FlowEvent.Aborted]] and fails with [[FlowError.Aborted]].
  */
def fail(message: String)(using events: FlowEvents): IO[FlowError, Nothing] =
  events.publish(FlowEvent.Aborted(message)) *> ZIO.fail(FlowError.Aborted(message))
