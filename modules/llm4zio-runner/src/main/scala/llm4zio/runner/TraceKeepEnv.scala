package llm4zio.runner

/** Parse `LLM4ZIO_TRACE_KEEP` into the number of trace files kept by [[llm4zio.flow.FlowRecorder]]: how many
  * completed-flow trace files are retained before the oldest is pruned. Unset/blank/invalid/negative → [[default]]
  * (20); `0` → keep none; `<n>` (n ≥ 0) → that many.
  */
object TraceKeepEnv:
  val default: Int = 20

  def parse(value: Option[String]): Int =
    value.map(_.trim).filter(_.nonEmpty) match
      case None    => default
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse(default)
