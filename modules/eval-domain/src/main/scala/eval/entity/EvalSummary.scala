package eval.entity

import zio.json.*

final case class EvalSummary(
  total: Int,
  passed: Int,
  failed: Int,
  passRate: Double,
) derives JsonCodec

object EvalSummary:
  def from(results: List[EvalCaseResult]): EvalSummary =
    val total  = results.size
    val passed = results.count(_.passed)
    val failed = total - passed
    val rate   = if total == 0 then 0.0 else passed.toDouble / total.toDouble
    EvalSummary(total, passed, failed, rate)
