package llm4zio.flow

import zio.Scope
import zio.test.*

import llm4zio.core.TokenUsage

object PriceListSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("PriceList")(
    test("computes cost for a known model (prefix match)") {
      // haiku 4.5: $1/Mtok in, $5/Mtok out. 1_000_000 in + 0 out = $1.00
      val c = PriceList.costUsd("claude-haiku-4-5-20251001", TokenUsage(1_000_000, 0, 1_000_000))
      assertTrue(c.contains(1.0))
    },
    test("returns None for an unknown model") {
      assertTrue(PriceList.costUsd("mystery-model", TokenUsage(10, 10, 20)).isEmpty)
    },
    test("prices OpenAI codex models (gpt-5.5: $5/M in, $30/M out)") {
      assertTrue(
        PriceList.costUsd("gpt-5.5", TokenUsage(1_000_000, 0, 1_000_000)).contains(5.0),
        PriceList.costUsd("gpt-5.5", TokenUsage(0, 100_000, 100_000)).contains(3.0),
      )
    },
    test("prices cache reads at 10% of the input rate") {
      // opus 4: $15/M in → cached reads at $1.50/M. 1M cached, nothing else = $1.50
      val cachedOnly = PriceList.costUsd("claude-opus-4-8", TokenUsage(0, 0, 0, cached = Some(1_000_000)))
      val combined   = PriceList.costUsd("gpt-5.5", TokenUsage(1_000_000, 0, 1_000_000, cached = Some(1_000_000)))
      assertTrue(
        cachedOnly.exists(c => math.abs(c - 1.5) < 0.0001),
        combined.exists(c => math.abs(c - 5.5) < 0.0001), // $5 fresh input + $0.50 cached
      )
    },
  )
