package eval.entity

import java.time.Instant

import zio.json.*

/** Immutable record of one execution of a dataset against an agent. Serialized to
  * `<workspace>/evals/runs/run-<runId>.json` and consumed by `eval compare`.
  */
final case class EvalRun(
  runId: String,
  datasetPath: String,
  agentName: String,
  startedAt: Instant,
  finishedAt: Instant,
  results: List[EvalCaseResult],
  summary: EvalSummary,
) derives JsonCodec
