package sdlc.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import issues.entity.TokenUsage

/** Per-1k-token pricing for a (provider, model) pair in USD.
  *
  * Phase 6 (big-review R1): replaces the hardcoded $1µ-per-token mock with
  * realistic provider-specific rates so per-employee P&L means something.
  */
final case class TokenPrice(
  inputPer1k: Double,
  outputPer1k: Double,
) derives JsonCodec,
    Schema

object Pricing:

  /** Public list pricing snapshot as of 2025-Q1. USD per 1k tokens.
    *
    * Conservative — published rates without volume discounts. Override per
    * deployment via the `cost.pricing.*` config keys (deferred to a follow-up
    * commit; for now this table is the source of truth).
    */
  val table: Map[(String, String), TokenPrice] = Map(
    // Anthropic Claude
    ("anthropic", "claude-opus-4-7")     -> TokenPrice(15.00, 75.00),
    ("anthropic", "claude-opus-4-6")     -> TokenPrice(15.00, 75.00),
    ("anthropic", "claude-sonnet-4-6")   -> TokenPrice(3.00, 15.00),
    ("anthropic", "claude-sonnet-4-5")   -> TokenPrice(3.00, 15.00),
    ("anthropic", "claude-haiku-4-5")    -> TokenPrice(0.80, 4.00),
    // OpenAI
    ("openai", "gpt-5")                  -> TokenPrice(5.00, 15.00),
    ("openai", "gpt-4o")                 -> TokenPrice(2.50, 10.00),
    ("openai", "gpt-4o-mini")            -> TokenPrice(0.15, 0.60),
    // Google Gemini
    ("gemini", "gemini-2.0-pro")         -> TokenPrice(1.25, 5.00),
    ("gemini", "gemini-2.0-flash")       -> TokenPrice(0.075, 0.30),
    ("geminicli", "gemini-2.0-pro")      -> TokenPrice(1.25, 5.00),
    ("geminicli", "gemini-2.0-flash")    -> TokenPrice(0.075, 0.30),
    ("geminiapi", "gemini-2.0-pro")      -> TokenPrice(1.25, 5.00),
    ("geminiapi", "gemini-2.0-flash")    -> TokenPrice(0.075, 0.30),
    // Local: free
    ("lmstudio", "")                     -> TokenPrice(0.0, 0.0),
    ("ollama", "")                       -> TokenPrice(0.0, 0.0),
    ("mock", "")                         -> TokenPrice(0.0, 0.0),
  )

  /** Conservative middle-of-market fallback when (provider, model) is unknown.
    * Sonnet-tier rates: $3/$15 per 1M tokens. Chosen so the dashboard never
    * silently undercounts cost.
    */
  val default: TokenPrice = TokenPrice(3.0, 15.0)

  /** Look up the price for a (provider, model). Falls back to provider-only,
    * then to `default`. Both inputs are case-insensitive and trimmed.
    */
  def lookup(provider: String, model: String): TokenPrice =
    val p = provider.trim.toLowerCase
    val m = model.trim.toLowerCase
    table.getOrElse((p, m), table.getOrElse((p, ""), default))

  /** Compute the USD cost of a TokenUsage at the given price. */
  def costUsd(usage: TokenUsage, price: TokenPrice): Double =
    (usage.inputTokens.toDouble / 1000.0) * price.inputPer1k +
      (usage.outputTokens.toDouble / 1000.0) * price.outputPer1k
