package llm4zio.flow

import llm4zio.core.{ Capability, LlmError }

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

  /** An external process (git, gh, a CLI agent) failed unexpectedly. */
  final case class Process(message: String, detail: String) extends FlowError

  /** A wrapped failure from the underlying LLM service. `cause` carries the typed error when available. */
  final case class Llm(message: String, cause: Option[LlmError] = None) extends FlowError

  /** A runtime capability denial on a flow surface (issue #716): the ambient grants were narrowed below what the
    * operation needs. In flow code this is a self-contradiction between a `Grants.restricted` block and the calls
    * inside it, so it fails fast rather than returning in the value channel.
    */
  final case class CapabilityDenied(capability: Capability, operation: String) extends FlowError:
    def message: String = s"capability $capability denied for $operation"
