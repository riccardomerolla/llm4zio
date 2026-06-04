package llm4zio.flow

import zio.*

/** Opt-in policy for waiting out a provider usage/credit cap.
  *
  * @param enabled      master switch (off ⇒ usage limits fail fast, but typed)
  * @param maxWait      ceiling on total wait before giving up and re-raising the UsageLimitError
  * @param pollInterval probe cadence when the reset time is unknown
  */
final case class UsageLimitPolicy(
  enabled: Boolean = false,
  maxWait: Duration = 4.hours,
  pollInterval: Duration = 2.minutes,
)

object UsageLimitPolicy:
  val off: UsageLimitPolicy     = UsageLimitPolicy(enabled = false)
  val patient: UsageLimitPolicy = UsageLimitPolicy(enabled = true)
