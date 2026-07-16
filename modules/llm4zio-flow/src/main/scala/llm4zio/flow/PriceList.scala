package llm4zio.flow

import llm4zio.core.TokenUsage

/** Rough per-1M-token USD rates for cost *estimation* in the terminal footer. Prefix-matched against the reported model
  * id. Estimates only — clearly flagged in the summary. Update as pricing changes. Cache reads are estimated at 10% of
  * the input rate (Anthropic's and OpenAI's published cached-input discount as of this table's date).
  */
object PriceList:
  /** When the pricing table below was last refreshed — shown in the footer footnote and stamped into cost-ledger
    * records so historical estimates stay interpretable after rates change.
    */
  val asOf: String = "2026-07"

  /** (model-id prefix, USD per 1M input tokens, USD per 1M output tokens). */
  private val rates: List[(String, Double, Double)] = List(
    ("claude-opus-4", 15.0, 75.0),
    ("claude-sonnet-4", 3.0, 15.0),
    ("claude-haiku-4", 1.0, 5.0),
    ("gemini-2.5-pro", 1.25, 10.0),
    ("gemini-2.5-flash", 0.30, 2.50),
    ("gpt-5.5", 5.0, 30.0),
  )

  def costUsd(model: String, usage: TokenUsage): Option[Double] =
    rates.collectFirst {
      case (prefix, in, out) if model.startsWith(prefix) =>
        usage.prompt.toDouble / 1_000_000 * in +
          usage.completion.toDouble / 1_000_000 * out +
          usage.cached.getOrElse(0).toDouble / 1_000_000 * in * 0.1
    }
