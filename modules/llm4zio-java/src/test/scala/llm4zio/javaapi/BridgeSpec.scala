package llm4zio.javaapi

import zio.test.*
import zio.{ Runtime, Scope, Unsafe, ZIO }

import llm4zio.flow.FlowError

/** The blocking bridge that backs every JavaFlow call: run a `ZIO[Any, FlowError, A]` on the scoped runtime, hand the
  * value back to Java, or throw [[Llm4zioException]] carrying the typed error category.
  */
object BridgeSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & Scope, Any] = suite("Bridge")(
    test("run hands back the success value of an effect") {
      val got = Unsafe.unsafe(implicit u => Bridge.run(Runtime.default, ZIO.succeed(42)))
      assertTrue(got == 42)
    },
    test("a FlowError failure surfaces as Llm4zioException carrying the typed category") {
      val thrown = Unsafe.unsafe { implicit u =>
        try { Bridge.run(Runtime.default, ZIO.fail(FlowError.Llm("boom"))); None }
        catch case e: Llm4zioException => Some(e)
      }
      assertTrue(
        thrown.map(_.getCategory).contains(ErrorCategory.Llm),
        thrown.exists(_.getMessage == "boom"),
      )
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
  )
