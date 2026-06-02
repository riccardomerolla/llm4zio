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
  )
