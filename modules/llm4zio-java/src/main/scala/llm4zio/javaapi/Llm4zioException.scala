package llm4zio.javaapi

import java.io.{ PrintWriter, StringWriter }

import llm4zio.flow.FlowError

/** The category of a catastrophic flow failure, mirroring the cases of `llm4zio.flow.FlowError` (plus
  * [[ErrorCategory.Interrupted]] for cancellation and [[ErrorCategory.Unknown]] for defects). Extends `java.lang.Enum`,
  * so it compiles to a real Java enum — Java callers reference `ErrorCategory.Aborted` as a constant and `switch` over
  * it natively. The `is*` predicates are kept for source compatibility.
  */
enum ErrorCategory extends java.lang.Enum[ErrorCategory]:
  case Persistence, PlanParse, Aborted, Process, Llm, Interrupted, Unknown, CapabilityDenied

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
      case _: FlowError.Persistence      => ErrorCategory.Persistence
      case _: FlowError.PlanParse        => ErrorCategory.PlanParse
      case _: FlowError.Aborted          => ErrorCategory.Aborted
      case _: FlowError.Process          => ErrorCategory.Process
      case _: FlowError.Llm              => ErrorCategory.Llm
      case _: FlowError.CapabilityDenied => ErrorCategory.CapabilityDenied
    val cause    = error match
      case FlowError.Persistence(_, c) => c
      case _                           => None
    new Llm4zioException(category, error.message, cause)

  /** Reverse of [[from]]: collapse a Throwable thrown out of a Java flow body back into a typed [[FlowError]], so the
    * runner's failure rendering (✖ banner, exit codes) sees a flow-layer error. A non-[[Llm4zioException]] (a bug in
    * the Java body — an NPE, an UncheckedIOException, …) maps to [[FlowError.Process]] per the recoverable-vs-
    * catastrophic split, keeping the exception class in the message and the full stack trace in the detail so the
    * failure stays debuggable from the trace/log.
    */
  def toFlowError(t: Throwable): FlowError = t match
    case e: Llm4zioException =>
      e.getCategory match
        case ErrorCategory.Persistence      => FlowError.Persistence(e.getMessage, Option(e.getCause))
        case ErrorCategory.PlanParse        => FlowError.PlanParse(e.getMessage)
        case ErrorCategory.Aborted          => FlowError.Aborted(e.getMessage)
        case ErrorCategory.Process          => FlowError.Process(e.getMessage, "")
        case ErrorCategory.Llm              => FlowError.Llm(e.getMessage)
        case ErrorCategory.Interrupted      => FlowError.Aborted("interrupted")
        case ErrorCategory.Unknown          => FlowError.Llm(e.getMessage)
        // The typed capability/operation cannot be reconstructed from a message; Aborted keeps the failure fatal
        // with the denial story intact in the message.
        case ErrorCategory.CapabilityDenied => FlowError.Aborted(e.getMessage)
    case other               => FlowError.Process(other.toString, stackTrace(other))

  private def stackTrace(t: Throwable): String =
    val sw = new StringWriter
    t.printStackTrace(new PrintWriter(sw))
    sw.toString
