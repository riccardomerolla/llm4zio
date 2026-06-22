package llm4zio.eval

import zio.json.JsonCodec

import llm4zio.core.{ LlmService, SchemaDerivation }

/** The wire shape the judge model returns. */
final case class JudgeResponse(scores: List[DimensionScore]) derives JsonCodec

/** Layer 2 — the LLM-as-a-Judge. Builds a strict-JSON scoring prompt from `dimensions`, asks the model for
  * per-dimension scores via `executeStructured`, then clamps each score to its dimension scale and fills any omitted
  * dimension with 0.
  */
object Judge:

  val defaultSystem: String =
    "You are an impartial evaluator. Score each dimension strictly on its rubric; when unsure, score lower. " +
      "Return ONLY valid JSON, no prose."

  def of(llm: LlmService, dimensions: List[Dimension], system: String = defaultSystem): Evaluator[Sample] =
    (sample: Sample) =>
      llm
        .executeStructured[JudgeResponse](
          buildPrompt(system, dimensions, sample),
          SchemaDerivation.derive[JudgeResponse],
        )
        .map(toResult(dimensions, _))

  private def buildPrompt(system: String, dimensions: List[Dimension], sample: Sample): String =
    val dims = dimensions.map(d => s"- ${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
    val user = List(
      sample.query.map(q => s"Query: $q"),
      sample.context.map(c => s"Context: $c"),
      Some(s"Response: ${sample.response}"),
      sample.expected.map(e => s"Expected: $e"),
    ).flatten.mkString("\n")
    s"""$system
       |
       |Score each dimension on its 0..max scale per the rubric:
       |$dims
       |
       |Return ONLY this JSON: {"scores":[{"name":"<dimension>","score":<int>,"reasoning":"<one sentence>"}]}
       |
       |$user""".stripMargin

  private def toResult(dimensions: List[Dimension], response: JudgeResponse): EvalResult =
    val byName = response.scores.map(s => s.name -> s).toMap
    val scores = dimensions.map { d =>
      byName.get(d.name) match
        case Some(s) => DimensionScore(d.name, s.score.max(0).min(d.maxScore), s.reasoning)
        case None    => DimensionScore(d.name, 0, "missing")
    }
    EvalResult(scores)
