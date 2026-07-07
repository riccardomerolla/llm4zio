package llm4zio.javaapi

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import zio.{ Cause, FiberFailure, Runtime, Unsafe, ZIO }

import llm4zio.flow.FlowError

/** The blocking bridge that backs every JavaFlow call: synchronously run a `ZIO[Any, FlowError, A]` on the flow's
  * runtime and hand the value back to the calling (Java) thread, or throw a [[Llm4zioException]] carrying the typed
  * [[FlowError]] category. This is the single place ZIO's effect/error model is collapsed into Java's value/throw
  * model.
  *
  * Interruption contract: if the calling thread is interrupted while waiting (Ctrl-C / SIGTERM reaching the flow via
  * `attemptBlockingInterrupt`), the underlying fiber is cancelled — running its finalizers, e.g. killing an in-flight
  * coder subprocess — the thread's interrupt status is restored, and an [[ErrorCategory.Interrupted]] exception unwinds
  * the Java body. Without this, cancellation would strand a root fiber and orphan its subprocess.
  */
private[javaapi] object Bridge:

  def runSync[A](runtime: Runtime[Any], effect: ZIO[Any, FlowError, A]): A =
    val future =
      Unsafe.unsafe(implicit u => runtime.unsafe.runToFuture(effect.mapError(Llm4zioException.from)))
    try Await.result(future, Duration.Inf)
    catch
      case ie: InterruptedException =>
        val _ = future.cancel()
        Thread.currentThread().interrupt()
        throw new Llm4zioException(
          ErrorCategory.Interrupted,
          "interrupted",
          Some(ie),
        ) // scalafix:ok DisableSyntax.throw
      case e: Llm4zioException      => throw e                // scalafix:ok DisableSyntax.throw
      case FiberFailure(cause)      => throw fromCause(cause) // scalafix:ok DisableSyntax.throw

  /** Collapse a fiber cause (defect, interruption, or a failure that escaped the mapError) into the single Java-facing
    * exception type.
    */
  private def fromCause(cause: Cause[Any]): Llm4zioException =
    cause.failureOption match
      case Some(e: Llm4zioException) => e
      case Some(fe: FlowError)       => Llm4zioException.from(fe)
      case _                         =>
        if cause.isInterruptedOnly then new Llm4zioException(ErrorCategory.Interrupted, "interrupted", None)
        else
          val squashed = cause.squashWith {
            case t: Throwable => t
            case other        => new RuntimeException(String.valueOf(other))
          }
          new Llm4zioException(
            ErrorCategory.Unknown,
            Option(squashed.getMessage).getOrElse(squashed.toString),
            Some(squashed),
          )
