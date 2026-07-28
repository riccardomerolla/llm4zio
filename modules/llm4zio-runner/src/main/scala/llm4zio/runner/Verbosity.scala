package llm4zio.runner

import llm4zio.flow.FlowEvent

/** How much a flow renders to the live terminal. Purely a rendering filter — the trace file is always full and the cost
  * footer + final banner always show, regardless of level.
  */
enum Verbosity:
  case Quiet, Normal, Verbose, Debug

  /** Whether this level renders `event` to the terminal. Stage/abort/fail always render; prose/tool/info render at
    * normal and up; token lines render at verbose and up. (Raw provider lines reach the terminal at debug via a Tee,
    * not through the event stream, so they are not part of this gate.)
    */
  def renders(event: FlowEvent): Boolean = event match
    case _: FlowEvent.StageStarted | _: FlowEvent.StageCompleted | _: FlowEvent.StageFailed | _: FlowEvent.Aborted =>
      true
    // Safety signals always render, at every level — a denial or an unenforceable restriction must never be silent.
    case _: FlowEvent.CapabilityDenied | _: FlowEvent.CapabilityUnenforceable                                      =>
      true
    case _: FlowEvent.Info | _: FlowEvent.ToolUse | _: FlowEvent.AssistantMessage                                  =>
      this != Verbosity.Quiet
    case _: FlowEvent.CapabilityUsed | _: FlowEvent.Declassified                                                   =>
      this != Verbosity.Quiet
    case _: FlowEvent.TokensUsed                                                                                   =>
      this == Verbosity.Verbose || this == Verbosity.Debug

object VerbosityEnv:
  val default: Verbosity = Verbosity.Normal

  /** Parse `LLM4ZIO_VERBOSITY`. Unset/blank/unknown → [[default]] (normal). Case-insensitive, trimmed. */
  def parse(value: Option[String]): Verbosity =
    value.map(_.trim.toLowerCase) match
      case Some("quiet")   => Verbosity.Quiet
      case Some("normal")  => Verbosity.Normal
      case Some("verbose") => Verbosity.Verbose
      case Some("debug")   => Verbosity.Debug
      case _               => default
