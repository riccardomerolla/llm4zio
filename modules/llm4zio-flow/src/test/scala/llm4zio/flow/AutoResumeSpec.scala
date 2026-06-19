package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.LlmError

object AutoResumeSpec extends ZIOSpecDefault:

  private val transientLlm = FlowError.Llm("boom", Some(LlmError.ProviderError("connection reset", None)))
  private val flakyLlm     = FlowError.Llm("boom", Some(LlmError.ProviderError("Invalid stream: empty response", None)))
  private val fatalLlm     = FlowError.Llm("nope", Some(LlmError.InvalidRequestError("bad prompt")))

  // A flow that fails `failTimes` times with `err`, then succeeds; counts attempts.
  private def counting(ref: Ref[Int], err: FlowError, failTimes: Int): IO[FlowError, String] =
    ref.updateAndGet(_ + 1).flatMap(n => if n <= failTimes then ZIO.fail(err) else ZIO.succeed("ok"))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AutoResume")(
    test("shouldResume: transient/flaky Llm yes; fatal Llm and other FlowErrors no") {
      assertTrue(
        AutoResume.shouldResume(transientLlm),
        AutoResume.shouldResume(flakyLlm),
        !AutoResume.shouldResume(fatalLlm),
        !AutoResume.shouldResume(FlowError.Process("git", "x")),
        !AutoResume.shouldResume(FlowError.Persistence("io")),
        !AutoResume.shouldResume(FlowError.PlanParse("bad")),
        !AutoResume.shouldResume(FlowError.Aborted("stop")),
        // cause required: Llm without cause must NOT trigger auto-resume
        !AutoResume.shouldResume(FlowError.Llm("x", None)),
      )
    },
    test("re-enters a transient failure within budget, then succeeds") {
      given FlowEvents = FlowEvents.noop
      for
        ref <- Ref.make(0)
        out <- AutoResume.withAutoResume(2, backoff = Duration.Zero)(counting(ref, transientLlm, failTimes = 2))
        n   <- ref.get
      yield assertTrue(out == "ok", n == 3) // 2 failures + 1 success
    },
    test("fails once the budget is exhausted") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <- AutoResume.withAutoResume(2, backoff = Duration.Zero)(counting(ref, transientLlm, failTimes = 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 3) // initial + 2 re-entries
    },
    test("does not re-enter a non-resumable error") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <-
          AutoResume.withAutoResume(3, backoff = Duration.Zero)(counting(ref, FlowError.Process("git", "x"), 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 1) // failed immediately, no re-entry
    },
    test("maxReentries = 0 runs the flow exactly once") {
      given FlowEvents = FlowEvents.noop
      for
        ref  <- Ref.make(0)
        exit <- AutoResume.withAutoResume(0, backoff = Duration.Zero)(counting(ref, transientLlm, 5)).exit
        n    <- ref.get
      yield assertTrue(exit.isFailure, n == 1)
    },
  )
