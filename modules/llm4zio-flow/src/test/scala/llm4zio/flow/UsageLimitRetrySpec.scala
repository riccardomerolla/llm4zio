package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.LlmError

object UsageLimitRetrySpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("withUsageLimitRetry")(
    test("sleeps then re-enters the flow until it succeeds") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        calls  <- Ref.make(0)
        flow    = calls.updateAndGet(_ + 1).flatMap(n =>
                    if n == 1 then ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(Some(now.plusSeconds(3600)), "codex", "limit"))))
                    else ZIO.succeed("done")
                  )
        fiber  <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.patient)(flow).fork
                  }
        _      <- TestClock.adjust(1.hour + 1.minute)
        out    <- fiber.join
        n      <- calls.get
      yield assertTrue(out == "done", n == 2)
    },
    test("disabled policy does not retry") {
      for
        events <- FlowEvents.collecting
        flow    = ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(None, "codex", "limit"))))
        exit   <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.off)(flow).exit
                  }
      yield assertTrue(exit.isFailure)
    },
    test("gives up after the re-entry cap") {
      for
        events <- FlowEvents.collecting
        now    <- Clock.instant
        flow    = ZIO.fail(FlowError.Llm("limit", Some(LlmError.UsageLimitError(Some(now.plusSeconds(60)), "codex", "limit"))))
        fiber  <- {
                    given FlowEvents = events
                    withUsageLimitRetry(UsageLimitPolicy.patient, maxReentries = 3)(flow).fork
                  }
        _      <- TestClock.adjust(10.minutes)
        exit   <- fiber.join.exit
      yield assertTrue(exit.isFailure)
    },
  )
