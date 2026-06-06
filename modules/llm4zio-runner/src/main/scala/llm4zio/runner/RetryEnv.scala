package llm4zio.runner

/** Parse the `LLM4ZIO_RETRIES` env value into a retry count for [[llm4zio.flow.TransientRetry]]: how many times a
  * transient provider blip (timeout, 5xx, connection reset, gemini's "API Error") is retried before failing.
  *
  *   - unset / blank / invalid → [[default]] (3)
  *   - `0` → fail fast (no retries)
  *   - `<n>` (n ≥ 0) → that many retries
  */
object RetryEnv:
  val default: Int = 3

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
