package llm4zio.javaapi

import zio.json.JsonCodec

import llm4zio.core.SchemaDerivation
import llm4zio.tools.JsonSchema

/** Architecture-decision-record extraction for reverse-engineering flows — the Java counterpart of the `Adr`/`AdrSet`
  * structured-output phase in `reverse-engineer.sc`. The record type and schema live here so a Java flow never touches
  * codecs or schemas; [[JavaFlow.adrs]] runs the extraction.
  */
object Adrs:
  /** One inferred architecture decision. */
  final case class Adr(
    number: Int,
    title: String,
    status: String,
    context: String,
    decision: String,
    consequences: String,
  ) derives JsonCodec

  final private[javaapi] case class AdrSet(adrs: List[Adr]) derives JsonCodec

  private[javaapi] val schema: JsonSchema = SchemaDerivation.derive[AdrSet]

  /** Render an ADR in the conventional Markdown shape. */
  def render(a: Adr): String =
    s"""# ${a.number}. ${a.title}
       |
       |Status: ${a.status}
       |
       |## Context
       |
       |${a.context}
       |
       |## Decision
       |
       |${a.decision}
       |
       |## Consequences
       |
       |${a.consequences}
       |""".stripMargin
