package llm4zio.eval

import zio.json.JsonCodec
import zio.{ IO, ZIO }

import llm4zio.core.LlmError

/** One scored axis of an evaluation: how to score it (`rubric`) and the top of its scale (`maxScore`, default 0–2). */
final case class Dimension(name: String, rubric: String, maxScore: Int = 2)

/** A model's score on one dimension, with a one-line justification. */
final case class DimensionScore(name: String, score: Int, reasoning: String = "") derives JsonCodec

/** The outcome of evaluating one input across dimensions. */
final case class EvalResult(scores: List[DimensionScore], summary: String = "") derives JsonCodec:
  def score(name: String): Option[Int] = scores.find(_.name == name).map(_.score)
  def total: Int                       = scores.map(_.score).sum

  /** Gate predicate: every dimension scored at least `minPerDimension`. Empty ⇒ vacuously true. */
  def meets(minPerDimension: Int): Boolean = scores.forall(_.score >= minPerDimension)

/** The default judge input: the response under test plus the optional material to judge it against. */
final case class Sample(
  response: String,
  query: Option[String] = None,
  context: Option[String] = None,
  expected: Option[String] = None,
)

/** Per-dimension stats across N repeated evaluations. */
final case class DimensionStats(name: String, median: Int, min: Int, max: Int):
  def spread: Int                      = max - min
  def isFlaky(threshold: Int): Boolean = spread > threshold

/** The result of running an evaluator N times: the raw runs, per-dimension stats, and the spread threshold used. */
final case class RepeatedEval(runs: List[EvalResult], stats: List[DimensionStats], spreadThreshold: Int):
  def flakyDimensions: List[String] = stats.filter(_.isFlaky(spreadThreshold)).map(_.name)
  def isFlaky: Boolean              = flakyDimensions.nonEmpty

  /** A single representative result built from per-dimension medians. */
  def aggregate: EvalResult =
    EvalResult(
      stats.map(s => DimensionScore(s.name, s.median, s"median of ${runs.size} runs")),
      s"aggregate of ${runs.size} runs",
    )

object Eval:
  /** Run `e` on `input` `n` times; aggregate per-dimension median/min/max and flag dimensions whose spread exceeds
    * `spreadThreshold` ("flaky"). The talk's non-determinism fix: run each case N times, flag variance above a bound.
    */
  def repeat[In](e: Evaluator[In], input: In, n: Int = 3, spreadThreshold: Int = 1): IO[LlmError, RepeatedEval] =
    ZIO.foreach((1 to n).toList)(_ => e.evaluate(input)).map { runs =>
      val names = runs.flatMap(_.scores.map(_.name)).distinct
      val stats = names.map { name =>
        val vals = runs.flatMap(_.score(name)).sorted
        DimensionStats(name, median(vals), vals.headOption.getOrElse(0), vals.lastOption.getOrElse(0))
      }
      RepeatedEval(runs, stats, spreadThreshold)
    }

  /** Lower-middle element of the sorted scores (integer buckets; no fractional median). Empty ⇒ 0. */
  private def median(sorted: List[Int]): Int =
    if sorted.isEmpty then 0 else sorted((sorted.size - 1) / 2)
