package eval.entity

import zio.json.*

final case class EvalCaseResult(
  prompt: String,
  expected: String,
  actual: String,
  verdict: EvalVerdict,
  exitCode: Int,
  durationMs: Long,
  error: Option[String] = None,
) derives JsonCodec:
  def passed: Boolean = verdict == EvalVerdict.Pass
