package llm4zio.flow

import zio.IO
import zio.json.JsonCodec
import zio.json.ast.Json

import llm4zio.core.LlmService
import llm4zio.tools.JsonSchema

/** A generated pull-request title + body. */
final case class PrSummary(title: String, body: String) derives JsonCodec

object PrSummary:
  val schema: JsonSchema =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "title" -> Json.Obj("type" -> Json.Str("string")),
        "body"  -> Json.Obj("type" -> Json.Str("string")),
      ),
    )

/** Generate a PR title + body from a diff using the reasoning connector. */
def summarisePr(reasoning: LlmService, diff: String, context: Option[String] = None): IO[FlowError, PrSummary] =
  val ctxBlock = context.fold("")(c => s"\nContext:\n$c\n")
  val prompt =
    s"""Summarise this change as a pull request. Respond ONLY with JSON
       |{"title":"...","body":"..."}.$ctxBlock
       |Diff:
       |$diff""".stripMargin
  reasoning
    .executeStructured[PrSummary](prompt, PrSummary.schema)
    .mapError(e => FlowError.Llm(e.toString))
