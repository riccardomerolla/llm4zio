package llm4zio.flow

import zio.json.ast.Json
import zio.test.*

import llm4zio.flow.Equiv.{ Mismatch, Observation, Tier }

object EquivReportSpec extends ZIOSpecDefault:

  private def vector(id: String, tier: Tier, rules: List[String]): EquivVector =
    EquivVector(
      schema = 1,
      program = "ACCTXFR",
      id = id,
      tier = tier,
      rules = rules,
      inputs = Json.Obj(),
      observations = List(Observation.Record("output:TRANSOUT", Map("status" -> "POSTED"))),
    )

  private val allRules = List("2100-VALIDATE-XFER", "2400-CALC-FEE", "2500-POST-LEDGER")

  private val passingGenerated = VectorVerdict(vector("gen-1", Tier.Generated, List("2100-VALIDATE-XFER")), Nil)
  private val passingCaptured  = VectorVerdict(vector("cap-1", Tier.Captured, List("2100-VALIDATE-XFER")), Nil)
  private val failingFee       = VectorVerdict(
    vector("gen-fee-1", Tier.Generated, List("2400-CALC-FEE")),
    List(Mismatch.FieldDiff("record output:TRANSOUT", "fee", expected = "2.50", actual = "2.60")),
  )

  def spec: Spec[Environment, Any] = suite("EquivReport")(
    test("a rule whose vectors all pass is proven, with generated and captured counted in separate columns") {
      val report = EquivReport.render(List(passingGenerated, passingCaptured), allRules)
      val row    = report.linesIterator.find(_.contains("2100-VALIDATE-XFER")).getOrElse("")
      assertTrue(row.contains("1/1"), row.contains("proven"), !row.contains("2/2"))
    },
    test("a rule with a failing vector is FAILING and its mismatches are listed with program, id, and tier") {
      val report = EquivReport.render(List(failingFee), allRules)
      assertTrue(
        report.linesIterator.exists(l => l.contains("2400-CALC-FEE") && l.contains("FAILING")),
        report.contains("ACCTXFR gen-fee-1 (generated)"),
        report.contains("fee"),
        report.contains("2.50"),
        report.contains("2.60"),
      )
    },
    test("rules never exercised by any vector are listed loudly as UNEXERCISED") {
      val report = EquivReport.render(List(passingGenerated), allRules)
      assertTrue(
        report.linesIterator.exists(l => l.contains("2500-POST-LEDGER") && l.contains("UNEXERCISED")),
        report.linesIterator.exists(l => l.contains("2400-CALC-FEE") && l.contains("UNEXERCISED")),
      )
    },
    test("the two tiers are explained and never summed into one number") {
      val report = EquivReport.render(List(passingGenerated, passingCaptured), allRules)
      assertTrue(
        report.contains("spec conformance"),
        report.contains("behavioural equivalence"),
        report.contains("never summed"),
      )
    },
  )
