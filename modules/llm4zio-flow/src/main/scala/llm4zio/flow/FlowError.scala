package llm4zio.flow

/** Typed errors raised by the flow layer. */
sealed trait FlowError:
  def message: String

object FlowError:
  /** Reading or writing a plan/file failed. */
  final case class Persistence(message: String, cause: Option[Throwable] = None) extends FlowError

  /** A persisted plan document could not be parsed. */
  final case class PlanParse(message: String) extends FlowError

  /** A flow step aborted deliberately (see `fail`). */
  final case class Aborted(message: String) extends FlowError
