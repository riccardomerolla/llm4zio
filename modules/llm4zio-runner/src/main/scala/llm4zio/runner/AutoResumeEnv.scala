package llm4zio.runner

/** Parse `LLM4ZIO_AUTO_RESUME` into the auto-resume budget for [[llm4zio.flow.AutoResume.withAutoResume]]: how many
  * times the whole flow body is re-entered after a transient/flaky failure that survived in-run retry (each re-entry
  * resumes from the persisted plan). Unset/blank/invalid → [[default]] (2); `0` → disabled; `<n>` (n ≥ 0).
  */
object AutoResumeEnv:
  val default: Int = 2

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
