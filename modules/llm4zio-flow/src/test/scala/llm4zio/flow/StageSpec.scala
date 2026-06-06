package llm4zio.flow

import zio.*
import zio.test.*

object StageSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("stage / fail / FlowEvents")(
    test("stage emits started then completed and returns the value") {
      for
        ev  <- FlowEvents.collecting
        r   <- stage("build")(ZIO.succeed(42))(using ev)
        rec <- ev.recorded
      yield assertTrue(
        r == 42,
        rec == Chunk(FlowEvent.StageStarted("build"), FlowEvent.StageCompleted("build")),
      )
    },
    test("stage emits failed and preserves the error when the body fails") {
      for
        ev  <- FlowEvents.collecting
        res <- stage("build")(ZIO.fail("boom"))(using ev).either
        rec <- ev.recorded
      yield assertTrue(
        res == Left("boom"),
        rec == Chunk(FlowEvent.StageStarted("build"), FlowEvent.StageFailed("build", "boom")),
      )
    },
    test("a FlowError failure renders its message, not the noisy case-class toString") {
      for
        ev  <- FlowEvents.collecting
        res <- stage("load")(ZIO.fail(FlowError.Llm("Gemini CLI stream error: quota", None)))(using ev).either
        rec <- ev.recorded
      yield assertTrue(
        res == Left(FlowError.Llm("Gemini CLI stream error: quota", None)),
        rec == Chunk(
          FlowEvent.StageStarted("load"),
          FlowEvent.StageFailed("load", "Gemini CLI stream error: quota"),
        ),
      )
    },
    test("fail publishes Aborted and fails with FlowError.Aborted") {
      for
        ev  <- FlowEvents.collecting
        res <- fail("nope")(using ev).either
        rec <- ev.recorded
      yield assertTrue(res == Left(FlowError.Aborted("nope")), rec == Chunk(FlowEvent.Aborted("nope")))
    },
    test("hub delivers published events to a subscriber") {
      ZIO.scoped {
        for
          hub <- FlowEvents.hub()
          q   <- hub.subscribe
          _   <- hub.publish(FlowEvent.Info("a"))
          _   <- hub.publish(FlowEvent.Info("b"))
          a   <- q.take
          b   <- q.take
        yield assertTrue(a == FlowEvent.Info("a"), b == FlowEvent.Info("b"))
      }
    },
  )
