package llm4zio.javaapi

import zio.test.*
import zio.{ Promise, Scope, ZIO, durationInt }

import llm4zio.flow.FlowError

/** The blocking bridge that backs every JavaFlow call: run a `ZIO[Any, FlowError, A]` on the flow's runtime, hand the
  * value back to Java, or throw [[Llm4zioException]] carrying the typed error category — and, on cancellation, actually
  * interrupt the underlying fiber instead of orphaning it.
  */
object BridgeSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & Scope, Any] = suite("Bridge")(
    test("runSync hands back the success value of an effect") {
      for
        rt  <- ZIO.runtime[Any]
        got <- ZIO.attemptBlocking(Bridge.runSync(rt, ZIO.succeed(42)))
      yield assertTrue(got == 42)
    },
    test("a FlowError failure surfaces as Llm4zioException carrying the typed category") {
      for
        rt     <- ZIO.runtime[Any]
        thrown <- ZIO.attemptBlocking {
                    try { Bridge.runSync(rt, ZIO.fail(FlowError.Llm("boom"))); None }
                    catch case e: Llm4zioException => Some(e)
                  }
      yield assertTrue(
        thrown.map(_.getCategory).contains(ErrorCategory.Llm),
        thrown.exists(_.getMessage == "boom"),
      )
    },
    test("interrupting the flow fiber cancels the inner effect and unwinds the Java body promptly") {
      for
        rt          <- ZIO.runtime[Any]
        interrupted <- Promise.make[Nothing, Unit]
        started     <- Promise.make[Nothing, Unit]
        // The exact shape of a Java flow: the body blocks inside Bridge.runSync on a long-running inner effect.
        body         = ZIO.attemptBlockingInterrupt {
                         Bridge.runSync(
                           rt,
                           (ZIO.succeed(()).tap(_ => started.succeed(())) *> ZIO.never)
                             .onInterrupt(interrupted.succeed(())),
                         )
                       }
        fiber       <- body.fork
        _           <- started.await
        _           <- fiber.interrupt.timeoutFail("flow fiber did not unwind")(10.seconds)
        _           <- interrupted.await.timeoutFail("inner effect was not interrupted")(10.seconds)
      yield assertTrue(true)
    },
    test("toFlowError round-trips each category back to the matching FlowError") {
      val cases = List(
        FlowError.Persistence("p")        -> ErrorCategory.Persistence,
        FlowError.PlanParse("pp")         -> ErrorCategory.PlanParse,
        FlowError.Aborted("a")            -> ErrorCategory.Aborted,
        FlowError.Process("pr", "detail") -> ErrorCategory.Process,
        FlowError.Llm("l")                -> ErrorCategory.Llm,
      )
      assertTrue(cases.forall { (original, category) =>
        Llm4zioException.from(original).getCategory == category &&
        Llm4zioException.toFlowError(Llm4zioException.from(original)).getClass == original.getClass
      })
    },
    test("a plain Java exception maps to FlowError.Process keeping the class name and the stack trace") {
      val boom   = new IllegalArgumentException("bad input")
      val mapped = Llm4zioException.toFlowError(boom)
      assertTrue(
        mapped match
          case FlowError.Process(message, detail) =>
            message.contains("IllegalArgumentException") && message.contains("bad input") &&
            detail.contains("at llm4zio.javaapi.BridgeSpec")
          case _                                  => false
      )
    },
  ) @@ TestAspect.withLiveClock
