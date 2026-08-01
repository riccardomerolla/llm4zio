package llm4zio.modernize

import zio.Scope
import zio.test.*

import llm4zio.flow.*

object VerifyTriageSpec extends ZIOSpecDefault:

  private def vector(program: String, id: String) =
    EquivVector(
      schema = 1,
      program = program,
      id = id,
      tier = Equiv.Tier.Generated,
      rules = List("R1"),
      inputs = zio.json.ast.Json.Obj(),
      observations = Nil,
    )

  /** `Mismatch.Missing` wraps an `Observation`, not a String. */
  private def missing(kind: String) =
    Equiv.Mismatch.Missing(Equiv.Observation.Record(kind, Map("amount" -> "10.00")))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("verify triage")(
    test("failing vectors group by program") {
      val failing = List(
        VectorVerdict(vector("ACCTXFR", "a"), List(missing("ledger"))),
        VectorVerdict(vector("ACCTXFR", "b"), List(missing("reject"))),
        VectorVerdict(vector("BALINQ", "c"), List(missing("report"))),
      )
      val grouped = failing.groupBy(_.vector.program)
      assertTrue(
        grouped.keySet == Set("ACCTXFR", "BALINQ"),
        grouped("ACCTXFR").size == 2,
        grouped("BALINQ").size == 1,
      )
    },
    test("triagePrompt for one program names only that program's spec") {
      val prompt = VerifyFlow.triagePrompt(
        pack = TestPacks.minimal,
        program = "ACCTXFR",
        failing = List(VectorVerdict(vector("ACCTXFR", "a"), List(missing("ledger")))),
        specText = "ACCTXFR spec body",
      )
      assertTrue(
        prompt.contains("ACCTXFR"),
        prompt.contains("ACCTXFR spec body"),
        !prompt.contains("BALINQ"),
      )
    },
  )
