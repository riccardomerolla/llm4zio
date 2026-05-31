package llm4zio.core

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StructuredOutputsSpec extends ZIOSpecDefault:

  final case class TriagePick(lane: String, note: String) derives JsonCodec

  private val emptySchema = Json.Obj()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("StructuredOutputs")(
    test("parseFromText decodes a plain JSON object") {
      for pick <- StructuredOutputs.parseFromText[TriagePick](
                    """{"lane":"Frontend","note":"safari"}""",
                    emptySchema,
                  )
      yield assertTrue(pick == TriagePick("Frontend", "safari"))
    },
    test("parseFromText extracts JSON from a fenced block surrounded by chatter") {
      val text =
        """Here is your answer:
          |
          |```json
          |{"lane":"Backend","note":"db"}
          |```
          |
          |Hope that helps!""".stripMargin
      for pick <- StructuredOutputs.parseFromText[TriagePick](text, emptySchema)
      yield assertTrue(pick == TriagePick("Backend", "db"))
    },
    test("parseFromText recovers from a CLI stderr warning prepended to the body") {
      // This is the real case that broke the user's triage: claude-cli prints
      // "Warning: no stdin data received in 3s..." to stdout before the JSON.
      val text =
        """Warning: no stdin data received in 3s, proceeding without it. If piping from a slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.
          |{"lane":"Custom","note":"docs-readme-update"}""".stripMargin
      for pick <- StructuredOutputs.parseFromText[TriagePick](text, emptySchema)
      yield assertTrue(pick == TriagePick("Custom", "docs-readme-update"))
    },
    test("parseFromText error names the JSON-looking candidate when nothing decodes") {
      val text = """Warning: nope.
                   |{"wrong":"shape"}""".stripMargin
      for exit <- StructuredOutputs.parseFromText[TriagePick](text, emptySchema).exit
      yield assert(exit)(
        Assertion.fails(
          Assertion.isSubtype[LlmError.ParseError](
            Assertion.hasField[LlmError.ParseError, String](
              "message",
              _.message,
              Assertion.containsString("\"wrong\":\"shape\""),
            )
          )
        )
      )
    },
    test("jsonCandidates finds objects after non-JSON preambles") {
      val candidates = StructuredOutputs.jsonCandidates(
        """noise noise {"a":1} more noise {"b":2}"""
      )
      assertTrue(
        candidates.contains("""{"a":1}"""),
        candidates.contains("""{"b":2}"""),
      )
    },
  )
