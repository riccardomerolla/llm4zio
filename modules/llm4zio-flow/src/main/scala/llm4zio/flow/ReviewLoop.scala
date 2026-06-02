package llm4zio.flow

import zio.*

/** Evaluate → fix → re-evaluate until the review is clean or `maxRounds` evaluations have run. `evaluate` runs every
  * round (so it sees the latest state); `fix` runs between rounds. Returns the final [[ReviewResult]] — clean if it
  * converged, otherwise the last (still-dirty) result.
  *
  * For `maxRounds = N`: `evaluate` runs up to N times, `fix` up to N-1 times.
  */
def fixLoop[R, E](
  evaluate: ZIO[R, E, ReviewResult],
  fix: ReviewResult => ZIO[R, E, Unit],
  maxRounds: Int = 3,
)(using events: FlowEvents
): ZIO[R, E, ReviewResult] =
  def loop(round: Int): ZIO[R, E, ReviewResult] =
    evaluate.flatMap { result =>
      if result.isClean || round >= maxRounds then
        val verdict = if result.isClean then "clean" else s"${result.issues.size} issue(s) remaining"
        events.publish(FlowEvent.Info(s"review settled after round $round: $verdict")).as(result)
      else
        events.publish(FlowEvent.Info(s"review round $round: ${result.issues.size} issue(s), fixing")) *>
          fix(result) *> loop(round + 1)
    }
  loop(1)
