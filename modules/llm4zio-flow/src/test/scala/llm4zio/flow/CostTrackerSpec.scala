package llm4zio.flow

import zio.test.*

import llm4zio.core.TokenUsage
import zio.Scope

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
        s.contains("Total:"),
        s.contains("*"),
      )
    },
    test("ignores non-token events") {
      for
        t <- CostTracker.make
        _ <- t.record(FlowEvent.Info("noise"))
        s <- t.summary
      yield assertTrue(s.contains("Total: $0.00"))
    },
  )
