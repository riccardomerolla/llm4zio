package llm4zio.runner

/** Parse `LLM4ZIO_FLAKY_RETRIES` into the flaky-stream retry budget for [[llm4zio.flow.TransientRetry]]: how many times
  * an intermittent empty-stream / malformed-tool-call failure is retried (each retry spawns a fresh process) before
  * failing. Unset/blank/invalid → [[default]] (6); `0` → fail fast; `<n>` (n ≥ 0) → that many.
  */
object FlakyRetryEnv:
  val default: Int = 6

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
