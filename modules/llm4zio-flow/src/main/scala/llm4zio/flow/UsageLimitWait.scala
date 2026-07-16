package llm4zio.flow

import java.time.Instant

import zio.*

/** Chunked sleep shared by the usage-limit wait layers ([[withUsageLimitRetry]], [[UsageLimitAware]]): sleeps `total`
  * in `heartbeat`-sized chunks, publishing a "still waiting" [[FlowEvent.Info]] pulse after each full chunk so a
  * watcher can tell sleeping from stuck. No pulse after the terminal chunk (the caller announces the resume).
  * Non-positive heartbeat, or a total that fits in one chunk, degrades to a single plain sleep.
  */
private[flow] object UsageLimitWait:

  def sleep(
    provider: String,
    total: Duration,
    resetAt: Option[Instant],
    heartbeat: Duration,
  )(using
    events: FlowEvents
  ): UIO[Unit] =
    if heartbeat.isZero || heartbeat.isNegative || total.compareTo(heartbeat) <= 0 then ZIO.sleep(total)
    else
      def loop(elapsed: Duration): UIO[Unit] =
        val remaining = total.minus(elapsed)
        if remaining.isZero || remaining.isNegative then ZIO.unit
        else
          val chunk = if remaining.compareTo(heartbeat) <= 0 then remaining else heartbeat
          val next  = elapsed.plus(chunk)
          ZIO.sleep(chunk) *> {
            if next.compareTo(total) < 0 then
              events.publish(FlowEvent.Info(pulse(provider, next, total.minus(next), resetAt))) *> loop(next)
            else ZIO.unit
          }
      loop(Duration.Zero)

  private def pulse(provider: String, elapsed: Duration, remaining: Duration, resetAt: Option[Instant]): String =
    val resumes = resetAt.fold("")(at => s", resumes $at")
    s"⏳ still waiting on $provider quota — ${fmt(elapsed)} elapsed, ~${fmt(remaining)} remaining$resumes"

  private def fmt(d: Duration): String =
    val mins = d.toMinutes
    if mins >= 60 then s"${mins / 60}h${mins % 60}m" else s"${mins}m"
