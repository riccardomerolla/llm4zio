package bankmod.mcp.tools

import zio.test.*

object ListInvariantsToolSpec extends ZIOSpecDefault:

  private val expectedKinds: Set[String] = Set(
    "CycleDetected",
    "TierViolation",
    "OrphanEdge",
    "UnknownPort",
    "PiiBoundaryCrossed",
    "WeakConsistencyOnFinancialEdge",
    "MissingPackedDecimalGuard",
  )

  def spec = suite("ListInvariantsTool.run")(
    test("catalog has exactly 7 entries") {
      val out = ListInvariantsTool.run(ListInvariantsInput())
      assertTrue(out.invariants.size == 7)
    },
    test("every catalog kind matches a real InvariantError variant name") {
      val out       = ListInvariantsTool.run(ListInvariantsInput())
      val kinds     = out.invariants.map(_.kind).toSet
      assertTrue(kinds == expectedKinds)
    },
    test("every entry has a non-empty shortDescription") {
      val out = ListInvariantsTool.run(ListInvariantsInput())
      assertTrue(out.invariants.forall(_.shortDescription.nonEmpty))
    },
  )
