package eval.entity

import zio.json.*

/** A single eval case: one prompt with its expected output and optional metadata.
  *
  * JSONL schema is deliberately compatible with Google agents-cli `evalsets`: one JSON object per line.
  */
final case class EvalCase(
  prompt: String,
  expected: String,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec
