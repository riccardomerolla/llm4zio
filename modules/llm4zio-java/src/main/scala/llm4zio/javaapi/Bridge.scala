package llm4zio.javaapi

import zio.{ Exit, Runtime, Unsafe, ZIO }

import llm4zio.flow.FlowError

/** The blocking bridge that backs every JavaFlow call: synchronously run a `ZIO[Any, FlowError, A]` on the flow's
  * scoped runtime and hand the value back to the calling (Java) thread, or throw a [[Llm4zioException]] carrying the
  * typed [[FlowError]] category. This is the single place ZIO's effect/error model is collapsed into Java's value/throw
  * model.
  */
private[javaapi] object Bridge:

  /** Open an [[Unsafe]] window and [[run]] the effect — the form the Java handles call, since they have no `Unsafe` in
    * scope of their own.
    */
  def runSync[A](runtime: Runtime[Any], effect: ZIO[Any, FlowError, A]): A =
    Unsafe.unsafe(implicit u => run(runtime, effect))

  def run[A](runtime: Runtime[Any], effect: ZIO[Any, FlowError, A])(using Unsafe): A =
    runtime.unsafe.run(effect) match
      case Exit.Success(a)     => a
      case Exit.Failure(cause) =>
        cause.failureOption match
          case Some(error) => throw Llm4zioException.from(error) // scalafix:ok DisableSyntax.throw
          case None        =>
            // No typed failure in the cause: a defect, or an interruption. Squash to a Throwable and surface it as an
            // Unknown-category exception so the Java side still sees a single exception type.
            val squashed = cause.squashWith(_ => new RuntimeException(cause.prettyPrint))
            throw new Llm4zioException(
              ErrorCategory.Unknown,
              squashed.getMessage,
              Some(squashed),
            ) // scalafix:ok DisableSyntax.throw
