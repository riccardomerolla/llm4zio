package llm4zio.flow

import llm4zio.core.TokenUsage

/** Rough per-1M-token USD rates for cost *estimation* in the terminal footer. Prefix-matched against the reported model
  * id. Estimates only — clearly flagged in the summary. Update as pricing changes.
  */
object PriceList:
  /** (model-id prefix, USD per 1M input tokens, USD per 1M output tokens). */
  private val rates: List[(String, Double, Double)] = List(
    ("claude-opus-4", 15.0, 75.0),
    ("claude-sonnet-4", 3.0, 15.0),
    ("claude-haiku-4", 1.0, 5.0),
    ("gemini-2.5-pro", 1.25, 10.0),
    ("gemini-2.5-flash", 0.30, 2.50),
  )

  def costUsd(model: String, usage: TokenUsage): Option[Double] =
    rates.collectFirst {
      case (prefix, in, out) if model.startsWith(prefix) =>
        usage.prompt.toDouble / 1_000_000 * in + usage.completion.toDouble / 1_000_000 * out
    }
