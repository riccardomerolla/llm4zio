package llm4zio.flow

import zio.Scope
import zio.test.*

import llm4zio.core.TokenUsage

object CostTrackerSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CostTracker")(
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
    test("shows cached tokens when present and sums them across records") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(1000, 200, 1200, Some(800))))
        _ <- t.record(FlowEvent.TokensUsed("coder", Some("gemini-2.5-flash"), TokenUsage(500, 100, 600, Some(200))))
        s <- t.summary
      yield assertTrue(s.contains("1000 cached"))
    },
  )
