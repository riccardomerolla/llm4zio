package sdlc.entity

import zio.Scope
import zio.test.*

import issues.entity.TokenUsage

object PricingSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Pricing")(
    test("lookup returns the exact match when (provider, model) is in the table") {
      val price = Pricing.lookup("anthropic", "claude-opus-4-7")
      assertTrue(price == TokenPrice(15.00, 75.00))
    },
    test("lookup is case-insensitive and trims whitespace") {
      val price = Pricing.lookup("  Anthropic  ", "  Claude-Opus-4-7  ")
      assertTrue(price == TokenPrice(15.00, 75.00))
    },
    test("lookup falls back to provider-only when model is unknown") {
      // lmstudio has a `("lmstudio", "")` row at $0/$0 — any unknown model
      // for that provider should pick that row, not the default.
      val price = Pricing.lookup("lmstudio", "llama-3.1-70b")
      assertTrue(price == TokenPrice(0.0, 0.0))
    },
    test("lookup falls back to default when provider is unknown") {
      val price = Pricing.lookup("never-heard-of-it", "some-model")
      assertTrue(price == Pricing.default)
    },
    test("default fallback is Sonnet-tier ($3 / $15 per 1M)") {
      assertTrue(Pricing.default == TokenPrice(3.0, 15.0))
    },
    test("costUsd separates input and output rates correctly") {
      // 1000 input + 2000 output tokens at Sonnet rate
      val usage = TokenUsage(inputTokens = 1000L, outputTokens = 2000L, totalTokens = 3000L)
      val price = Pricing.lookup("anthropic", "claude-sonnet-4-6")
      val cost  = Pricing.costUsd(usage, price)
      // 1000/1000 * 3 + 2000/1000 * 15 = 3 + 30 = 33
      assertTrue(math.abs(cost - 33.0) < 0.0001)
    },
    test("local/mock providers price at zero") {
      val usage = TokenUsage(inputTokens = 1000000L, outputTokens = 1000000L, totalTokens = 2000000L)
      assertTrue(
        Pricing.costUsd(usage, Pricing.lookup("ollama", "any")) == 0.0,
        Pricing.costUsd(usage, Pricing.lookup("lmstudio", "any")) == 0.0,
        Pricing.costUsd(usage, Pricing.lookup("mock", "")) == 0.0,
      )
    },
    test("Haiku is cheaper than Sonnet which is cheaper than Opus") {
      val usage = TokenUsage(inputTokens = 1000L, outputTokens = 1000L, totalTokens = 2000L)
      val haiku  = Pricing.costUsd(usage, Pricing.lookup("anthropic", "claude-haiku-4-5"))
      val sonnet = Pricing.costUsd(usage, Pricing.lookup("anthropic", "claude-sonnet-4-6"))
      val opus   = Pricing.costUsd(usage, Pricing.lookup("anthropic", "claude-opus-4-7"))
      assertTrue(haiku < sonnet, sonnet < opus)
    },
  )
