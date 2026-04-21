package bankmod.mcp.tools

import zio.Scope
import zio.test.*

object ExplainInvariantViolationToolSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ExplainInvariantViolationTool.run")(
    test("CycleDetected kind — non-empty explanation, non-empty fixes, rawErrorJson echoed back") {
      val rawJson = """{"path":["svc-a","svc-b","svc-a"]}"""
      val out     = ExplainInvariantViolationTool.run(
        ExplainInvariantViolationInput("CycleDetected", rawJson)
      )
      assertTrue(
        out.kind == "CycleDetected",
        out.explanation.nonEmpty,
        out.fixSuggestions.nonEmpty,
        out.rawErrorJson == rawJson,
      )
    },
    test("UnknownPort kind — at least one fix suggestion") {
      val out = ExplainInvariantViolationTool.run(
        ExplainInvariantViolationInput("UnknownPort", """{"edge":"x"}""")
      )
      assertTrue(
        out.kind == "UnknownPort",
        out.fixSuggestions.nonEmpty,
      )
    },
    test("MissingPackedDecimalGuard kind — at least one fix suggestion") {
      val out = ExplainInvariantViolationTool.run(
        ExplainInvariantViolationInput("MissingPackedDecimalGuard", """{"edge":"y"}""")
      )
      assertTrue(
        out.kind == "MissingPackedDecimalGuard",
        out.fixSuggestions.nonEmpty,
      )
    },
    test("Unknown kind 'Nonsense' — explanation mentions 'Unknown invariant kind', empty fixes") {
      val out = ExplainInvariantViolationTool.run(
        ExplainInvariantViolationInput("Nonsense", """{}""")
      )
      assertTrue(
        out.kind == "Nonsense",
        out.explanation.contains("Unknown invariant kind"),
        out.fixSuggestions.isEmpty,
      )
    },
  )
