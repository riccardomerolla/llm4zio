package llm4zio.flow

import zio.*

import llm4zio.core.LlmError

/** Flow-level backstop for provider usage caps: when `flow` fails with a usage-limit (a `FlowError.Llm` carrying a
  * [[LlmError.UsageLimitError]]), sleep until the reset (capped at `policy.maxWait`) then re-enter `flow`. Bounded by
  * `maxReentries`. Intended for the streaming coder + interactive `Drive` paths, where in-place retry is impossible;
  * re-entry leans on `PlanStore`/session resumability to skip completed work.
  */
def withUsageLimitRetry[R, A](
  policy: UsageLimitPolicy,
  maxReentries: Int = 3,
)(
  flow: ZIO[R, FlowError, A]
)(using events: FlowEvents
): ZIO[R, FlowError, A] =
  def loop(attempt: Int, waited: Duration): ZIO[R, FlowError, A] =
    flow.catchSome {
      case e @ FlowError.Llm(_, Some(u: LlmError.UsageLimitError)) if policy.enabled && attempt < maxReentries =>
        Clock.instant.flatMap { now =>
          val sleepFor = u.resetAt match
            case Some(at) =>
              val remaining = Duration.fromInterval(now, at)
              (if remaining.isNegative then Duration.Zero else remaining) + 30.seconds
            case None     => policy.pollInterval
          if waited + sleepFor > policy.maxWait then ZIO.fail(e)
          else
            events.publish(FlowEvent.Info(
              s"⏳ usage limit (${u.provider}) — sleeping ${math.max(1, sleepFor.toMinutes)}m, re-entering"
            )) *>
              ZIO.sleep(sleepFor) *>
              loop(attempt + 1, waited + sleepFor)
        }
    }
  loop(0, Duration.Zero)
