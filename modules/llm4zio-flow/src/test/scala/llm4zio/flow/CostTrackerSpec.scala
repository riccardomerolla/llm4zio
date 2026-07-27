package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage

object CostTrackerSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CostTracker")(
    test("consume subscribes before returning, so an event published immediately after is recorded") {
      ZIO.scoped {
        for
          t    <- CostTracker.make
          hub  <- FlowEvents.hub()
          _    <- t.consume(hub)
          _    <- hub.publish(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(10, 2, 12)))
          seen <- t.cells.repeatUntil(_.nonEmpty).timeout(5.seconds)
        yield assertTrue(seen.exists(_.nonEmpty))
      }
    } @@ TestAspect.withLiveClock,
    test("accumulates per agent and per model and renders a summary") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(1000, 200, 1200)))
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(500, 100, 600)))
        s <- t.summary
      yield assertTrue(
        s.contains("By agent:"),
        s.contains("coder: 1500 in, 300 out"),
        s.contains("By model:"),
        s.contains("gemini-2.5-flash: 1500 in, 300 out"),
        s.contains("Total: $"),
        s.contains("*"),
      )
    },
    test("buckets an unknown (None) model under '(unknown)' with no cost") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.TokensUsed("coder", None, TokenUsage(100, 20, 120)))
        s <- t.summary
      yield assertTrue(
        s.contains("(unknown): 100 in, 20 out"),
        s.contains("Total: $0.00"),
        !s.contains("*"),
      )
    },
    test("ignores non-token events") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.Info("noise"))
        s <- t.summary
      yield assertTrue(s.contains("Total: $0.00"))
    },
    test("attributes tokens to the innermost open stage, and to '(no stage)' outside any") {
      for
        t     <- CostTracker.make
        _     <- t.record(FlowEvent.TokensUsed("reasoning", Some("m"), TokenUsage(10, 1, 11)))
        _     <- t.record(FlowEvent.StageStarted("Extract"))
        _     <- t.record(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(100, 10, 110)))
        _     <- t.record(FlowEvent.StageStarted("task 1"))
        _     <- t.record(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(5, 1, 6)))
        _     <- t.record(FlowEvent.StageCompleted("task 1"))
        _     <- t.record(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 1, 2)))
        _     <- t.record(FlowEvent.StageCompleted("Extract"))
        _     <- t.record(FlowEvent.StageStarted("Gate"))
        _     <- t.record(FlowEvent.TokensUsed("reasoning", Some("m"), TokenUsage(50, 5, 55)))
        _     <- t.record(FlowEvent.StageFailed("Gate", "boom"))
        _     <- t.record(FlowEvent.TokensUsed("reasoning", Some("m"), TokenUsage(2, 2, 4)))
        cells <- t.cells
      yield assertTrue(
        cells.find(c => c.stage == "Extract" && c.agent == "coder").exists(c => c.prompt == 101 && c.completion == 11),
        cells.exists(c => c.stage == "task 1" && c.agent == "coder" && c.prompt == 5),
        cells.exists(c => c.stage == "Gate" && c.agent == "reasoning" && c.prompt == 50),
        cells.filter(_.stage == "(no stage)").map(_.prompt).sum == 12L,
      )
    },
    test("cells carry a per-cell cost estimate for priced models and none for unknown ones") {
      for
        t     <- CostTracker.make
        _     <- t.record(FlowEvent.StageStarted("Judge"))
        _     <-
          t.record(FlowEvent.TokensUsed("reasoning", Some("gemini-2.5-pro"), TokenUsage(1_000_000, 100_000, 1_100_000)))
        _     <- t.record(FlowEvent.TokensUsed("coder", None, TokenUsage(10, 1, 11)))
        cells <- t.cells
      yield assertTrue(
        cells.find(_.model == "gemini-2.5-pro").flatMap(_.costUsd).exists(c => math.abs(c - 2.25) < 0.0001),
        cells.find(_.model == "(unknown)").exists(_.costUsd.isEmpty),
      )
    },
    test("shows cached tokens when present and sums them across records") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(1000, 200, 1200, Some(800))))
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(500, 100, 600, Some(200))))
        s <- t.summary
      yield assertTrue(s.contains("1000 cached"))
    },
  )
