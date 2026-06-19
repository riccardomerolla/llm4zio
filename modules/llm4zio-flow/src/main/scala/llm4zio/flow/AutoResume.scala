package llm4zio.flow

import zio.*

/** Flow-level backstop for flaky/transient provider failures that survive in-run retry: re-enter the whole flow body
  * (re-reading the resumable plan, skipping completed tasks via `implementTaskLoop`) — exactly what a manual relaunch
  * does. Sibling of [[withUsageLimitRetry]], which handles usage caps; layered outside it so the two compose.
  */
object AutoResume:
  /** Default pause between re-entries. Re-reading the plan is cheap; this just avoids a tight loop. */
  val defaultBackoff: Duration = 2.seconds

  /** Resumable = a transient or flaky-stream LLM failure that survived in-run retry. Everything else (process, parse,
    * persistence, abort, non-transient LLM) fails fast.
    */
  def shouldResume(e: FlowError): Boolean = e match
    case FlowError.Llm(_, Some(cause)) => TransientRetry.isTransient(cause) || TransientRetry.isFlakyStream(cause)
    case _                             => false

  def withAutoResume[R, A](
    maxReentries: Int,
    backoff: Duration = defaultBackoff,
  )(
    flow: ZIO[R, FlowError, A]
  )(using events: FlowEvents
  ): ZIO[R, FlowError, A] =
    def loop(attempt: Int): ZIO[R, FlowError, A] =
      flow.catchSome {
        case e if shouldResume(e) && attempt < maxReentries =>
          events.publish(
            FlowEvent.Info(
              s"↻ auto-resume after transient failure — re-entering from plan, attempt ${attempt + 1}/$maxReentries: ${e.message}"
            )
          ) *> ZIO.sleep(backoff) *> loop(attempt + 1)
      }
    loop(0)
