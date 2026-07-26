package llm4zio.flow

import zio.test.*

import llm4zio.flow.Equiv.{ Mismatch, Observation }

object EquivDiffSpec extends ZIOSpecDefault:

  private val posted    = Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "2.50"))
  private val ledger    =
    Observation.DbMutation("LEDGER", "insert", key = Map("ACCT_ID" -> "A1"), set = Map("AMOUNT" -> "-502.50"))
  private val ordered   = ComparisonPolicy(Equiv.Ordering.Ordered, ignore = Set.empty)
  private val unordered = ComparisonPolicy(Equiv.Ordering.Unordered, ignore = Set.empty)

  def spec: Spec[Environment, Any] = suite("Equiv.diff")(
    test("identical observation sequences produce no mismatches") {
      assertTrue(Equiv.diff(List(posted, ledger), List(posted, ledger), ordered).isEmpty)
    },
    test("fields named in the policy's ignore set never count — timestamps and generated ids") {
      val expected = Observation.Record("audit", Map("action" -> "XFER", "TS" -> "10:00:00"))
      val actual   = Observation.Record("audit", Map("action" -> "XFER", "TS" -> "10:00:07"))
      assertTrue(
        Equiv.diff(List(expected), List(actual), ordered.copy(ignore = Set("TS"))).isEmpty,
        Equiv.diff(List(expected), List(actual), ordered).nonEmpty,
      )
    },
    test("a differing field pairs up as a FieldDiff carrying both values") {
      val actual = Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "2.60"))
      assertTrue(
        Equiv.diff(List(posted), List(actual), ordered) ==
          List(Mismatch.FieldDiff("record output:TRANSOUT", "fee", expected = "2.50", actual = "2.60"))
      )
    },
    test("an absent expected observation is Missing; an extra actual one is Unexpected") {
      assertTrue(
        Equiv.diff(List(posted, ledger), List(posted), ordered) == List(Mismatch.Missing(ledger)),
        Equiv.diff(List(posted), List(posted, ledger), ordered) == List(Mismatch.Unexpected(ledger)),
      )
    },
    test("under Unordered, the same observations in a different order are equivalent") {
      assertTrue(
        Equiv.diff(List(posted, ledger), List(ledger, posted), unordered).isEmpty,
        Equiv.diff(List(posted, ledger), List(ledger, posted), ordered).nonEmpty,
      )
    },
    test("under Unordered, leftovers still pair by identity so field diffs surface") {
      val cheaper = Observation.Record("output:TRANSOUT", Map("status" -> "POSTED", "fee" -> "0.00"))
      assertTrue(
        Equiv.diff(List(posted, ledger), List(ledger, cheaper), unordered) ==
          List(Mismatch.FieldDiff("record output:TRANSOUT", "fee", expected = "2.50", actual = "0.00"))
      )
    },
  )
