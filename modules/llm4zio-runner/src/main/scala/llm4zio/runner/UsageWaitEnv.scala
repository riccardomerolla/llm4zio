package llm4zio.runner

import zio.*

import llm4zio.flow.UsageLimitPolicy

/** Parse the `LLM4ZIO_USAGE_WAIT` env value into a [[UsageLimitPolicy]]: `off`/unset disables; `on`/`true` enables with
  * the default cap; `<n>h` / `<n>m` enables with that cap.
  */
object UsageWaitEnv:
  private val hours = """(?i)(\d+)h""".r
  private val mins  = """(?i)(\d+)m""".r

  def parse(value: Option[String]): UsageLimitPolicy =
    value.map(_.trim.toLowerCase) match
      case None | Some("") | Some("off") | Some("false") => UsageLimitPolicy.off
      case Some("on") | Some("true")                     => UsageLimitPolicy.patient
      case Some(hours(n))                                => UsageLimitPolicy.patient.copy(maxWait = n.toInt.hours)
      case Some(mins(n))                                 => UsageLimitPolicy.patient.copy(maxWait = n.toInt.minutes)
      case Some(_)                                       => UsageLimitPolicy.patient
