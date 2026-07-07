package llm4zio.javaapi

import llm4zio.flow.FlowError

/** The category of a catastrophic flow failure, mirroring the cases of `llm4zio.flow.FlowError`. (Scala 3 parameterless
  * enum cases aren't reachable as `ErrorCategory.X` from Java — a Java caller reads `getCategory().name()` /
  * `.ordinal`, or compares via the `is*` predicates below.)
  */
enum ErrorCategory:
  case Persistence, PlanParse, Aborted, Process, Llm, Unknown

  /** True when this is the [[ErrorCategory.Aborted]] category — the one a Java flow most often branches on. */
  def isAborted: Boolean = this == ErrorCategory.Aborted

/** The single unchecked exception a Java-authored flow sees when a step fails catastrophically. It carries the typed
  * [[ErrorCategory]] (so a caller can branch) alongside the human-readable message and any underlying cause.
  * Recoverable outcomes are never thrown — they come back as value-channel results.
  */
final class Llm4zioException(category: ErrorCategory, message: String, cause: Option[Throwable])
  extends RuntimeException(message, cause.orNull):

  /** The typed category of the underlying [[FlowError]]. */
  def getCategory: ErrorCategory = category

object Llm4zioException:
  /** Map a typed [[FlowError]] to the Java-facing exception. */
  def from(error: FlowError): Llm4zioException =
    val category = error match
      case _: FlowError.Persistence => ErrorCategory.Persistence
      case _: FlowError.PlanParse   => ErrorCategory.PlanParse
      case _: FlowError.Aborted     => ErrorCategory.Aborted
      case _: FlowError.Process     => ErrorCategory.Process
      case _: FlowError.Llm         => ErrorCategory.Llm
    val cause    = error match
      case FlowError.Persistence(_, c) => c
      case _                           => None
    new Llm4zioException(category, error.message, cause)

  /** Reverse of [[from]]: collapse a Throwable thrown out of a Java flow body back into a typed [[FlowError]], so the
    * runner's failure rendering (✖ banner, exit codes) sees a flow-layer error. A non-[[Llm4zioException]] (a bug in
    * the Java body) maps to [[FlowError.Llm]].
    */
  def toFlowError(t: Throwable): FlowError = t match
    case e: Llm4zioException =>
      e.getCategory match
        case ErrorCategory.Persistence => FlowError.Persistence(e.getMessage, Option(e.getCause))
        case ErrorCategory.PlanParse   => FlowError.PlanParse(e.getMessage)
        case ErrorCategory.Aborted     => FlowError.Aborted(e.getMessage)
        case ErrorCategory.Process     => FlowError.Process(e.getMessage, "")
        case ErrorCategory.Llm         => FlowError.Llm(e.getMessage)
        case ErrorCategory.Unknown     => FlowError.Llm(e.getMessage)
    case other               => FlowError.Llm(Option(other.getMessage).getOrElse(other.toString))
