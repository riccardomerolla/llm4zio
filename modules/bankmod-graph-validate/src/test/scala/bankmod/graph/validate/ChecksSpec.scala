package bankmod.graph.validate

import zio.test.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*
import bankmod.graph.validate.InvariantError.*

object ChecksSpec extends ZIOSpecDefault:

  // ── Fixture helpers ─────────────────────────────────────────────────────────

  private val defaultSla: Sla = Sla(
    latencyP99Ms = LatencyMs.from(200).toOption.get,
    availabilityPct = Percentage.from(99).toOption.get,
    maxRetries = BoundedRetries.from(3).toOption.get,
  )

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get

  private def restEdge(
    fromPort: PortName,
    toSvc: ServiceId,
    toPort: PortName,
    consistency: Consistency = Consistency.Strong,
  ): Edge =
    Edge(
      fromPort = fromPort,
      toService = toSvc,
      toPort = toPort,
      protocol = Protocol.rest("https://example.com/api").toOption.get,
      consistency = consistency,
      ordering = Ordering.Unordered,
    )

  private def eventEdge(fromPort: PortName, toSvc: ServiceId, toPort: PortName): Edge =
    Edge(
      fromPort = fromPort,
      toService = toSvc,
      toPort = toPort,
      protocol = Protocol.event("payments.created").toOption.get,
      consistency = Consistency.Eventual,
      ordering = Ordering.TotalOrder,
    )

  private def mkSvc(
    id: ServiceId,
    tier: Criticality = Criticality.Tier1,
    inbound: Set[Port] = Set.empty,
    outbound: Set[Edge] = Set.empty,
  ): Service =
    Service(
      id = id,
      tier = tier,
      owner = Ownership.Platform,
      inbound = inbound,
      outbound = outbound,
      schemas = Set.empty,
      dataStores = Set.empty,
      sla = defaultSla,
    )

  // ── Service IDs ─────────────────────────────────────────────────────────────

  private val idA: ServiceId = sid("svc-alpha")
  private val idB: ServiceId = sid("svc-bravo")
  private val idC: ServiceId = sid("svc-charlie")

  private val portIn: PortName  = port("http-in")
  private val portOut: PortName = port("http-out")

  // ── detectCycles ────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment, Any] = suite("Checks")(
    suite("detectCycles")(
      test("3-service cycle A→B→C→A produces CycleDetected(A,B,C,A)") {
        val edgeAB = restEdge(portOut, idB, portIn)
        val edgeBC = restEdge(portOut, idC, portIn)
        val edgeCA = restEdge(portOut, idA, portIn)

        val svcA = mkSvc(idA, inbound = Set(Port(portIn)), outbound = Set(edgeAB))
        val svcB = mkSvc(idB, inbound = Set(Port(portIn)), outbound = Set(edgeBC))
        val svcC = mkSvc(idC, inbound = Set(Port(portIn)), outbound = Set(edgeCA))
        val g    = Graph(Map(idA -> svcA, idB -> svcB, idC -> svcC))

        val errors = Checks.detectCycles(g)

        assertTrue(
          errors.nonEmpty,
          errors.exists {
            case CycleDetected(path) =>
              // Path must start where the cycle was entered and end with the same node
              path.head == path.last && path.toSet.contains(idA) && path.toSet.contains(idB) &&
              path.toSet.contains(idC)
            case _                   => false
          },
        )
      },
      test("DAG with no back-edges produces no cycles") {
        // A→B, A→C, B→C — acyclic
        val edgeAB = restEdge(portOut, idB, portIn)
        val edgeAC = restEdge(portOut, idC, portIn)
        val edgeBC = restEdge(portOut, idC, portIn)

        val svcA = mkSvc(idA, inbound = Set(Port(portIn)), outbound = Set(edgeAB, edgeAC))
        val svcB = mkSvc(idB, inbound = Set(Port(portIn)), outbound = Set(edgeBC))
        val svcC = mkSvc(idC, inbound = Set(Port(portIn)))
        val g    = Graph(Map(idA -> svcA, idB -> svcB, idC -> svcC))

        assertTrue(Checks.detectCycles(g).isEmpty)
      },
    ),
    suite("tierMonotonicity")(
      test("Tier1→Tier2 synchronous edge produces TierViolation") {
        // Tier1.ordinal == 0 < Tier2.ordinal == 1 → violation
        val edge   = restEdge(portOut, idB, portIn)
        val svcA   = mkSvc(idA, tier = Criticality.Tier1, inbound = Set(Port(portIn)), outbound = Set(edge))
        val svcB   = mkSvc(idB, tier = Criticality.Tier2, inbound = Set(Port(portIn)))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        val errors = Checks.tierMonotonicity(g)
        assertTrue(errors == List(TierViolation(from = idA, to = idB)))
      },
      test("Tier1→Tier2 event edge is exempt from tier check") {
        val edge = eventEdge(portOut, idB, portIn)
        val svcA = mkSvc(idA, tier = Criticality.Tier1, outbound = Set(edge))
        val svcB = mkSvc(idB, tier = Criticality.Tier2, inbound = Set(Port(portIn)))
        val g    = Graph(Map(idA -> svcA, idB -> svcB))
        assertTrue(Checks.tierMonotonicity(g).isEmpty)
      },
      test("Tier2→Tier1 synchronous edge is not a violation (lower tier calling higher)") {
        // Tier2.ordinal == 1 > Tier1.ordinal == 0 → not a violation
        val edge = restEdge(portOut, idA, portIn)
        val svcB = mkSvc(idB, tier = Criticality.Tier2, outbound = Set(edge))
        val svcA = mkSvc(idA, tier = Criticality.Tier1, inbound = Set(Port(portIn)))
        val g    = Graph(Map(idA -> svcA, idB -> svcB))
        assertTrue(Checks.tierMonotonicity(g).isEmpty)
      },
    ),
    suite("structural")(
      test("edge to missing service yields OrphanEdge") {
        val missing = sid("missing-svc")
        val edge    = restEdge(portOut, missing, portIn)
        val svcA    = mkSvc(idA, outbound = Set(edge))
        val g       = Graph(Map(idA -> svcA))
        val errors  = Checks.structural(g)
        assertTrue(errors == List(OrphanEdge(EdgeRef(from = idA, to = missing, port = portIn))))
      },
      test("edge to existing service but missing inbound port yields UnknownPort") {
        val unknownPort = port("non-existent-port")
        val edge        = restEdge(portOut, idB, unknownPort)
        // svcB declares portIn, but edge targets unknownPort
        val svcA        = mkSvc(idA, outbound = Set(edge))
        val svcB        = mkSvc(idB, inbound = Set(Port(portIn))) // declares portIn, not unknownPort
        val g           = Graph(Map(idA -> svcA, idB -> svcB))
        val errors      = Checks.structural(g)
        assertTrue(errors == List(UnknownPort(idB, unknownPort)))
      },
      test("edge to existing service with matching inbound port yields no error") {
        val edge = restEdge(portOut, idB, portIn)
        val svcA = mkSvc(idA, outbound = Set(edge))
        val svcB = mkSvc(idB, inbound = Set(Port(portIn)))
        val g    = Graph(Map(idA -> svcA, idB -> svcB))
        assertTrue(Checks.structural(g).isEmpty)
      },
    ),
    suite("piiBoundary")(
      test("PII service routing to non-allowed service yields PiiBoundaryCrossed") {
        val piiId     = idA
        val targetId  = idB
        val allowedId = idC
        val edge      = restEdge(portOut, targetId, portIn)
        val svcA      = mkSvc(piiId, inbound = Set(Port(portIn)), outbound = Set(edge))
        val svcB      = mkSvc(targetId, inbound = Set(Port(portIn)))
        val g         = Graph(Map(piiId -> svcA, targetId -> svcB))
        val errors    = Checks.piiBoundary(g, piiServices = Set(piiId), allowedSinks = Set(allowedId))
        assertTrue(errors == List(PiiBoundaryCrossed(from = piiId, to = targetId)))
      },
      test("PII service routing to allowed service yields no error") {
        val piiId    = idA
        val targetId = idB
        val edge     = restEdge(portOut, targetId, portIn)
        val svcA     = mkSvc(piiId, outbound = Set(edge))
        val svcB     = mkSvc(targetId, inbound = Set(Port(portIn)))
        val g        = Graph(Map(piiId -> svcA, targetId -> svcB))
        val errors   = Checks.piiBoundary(g, piiServices = Set(piiId), allowedSinks = Set(targetId))
        assertTrue(errors.isEmpty)
      },
      test("non-PII service is not checked for boundary") {
        val nonPiiId = idA
        val targetId = idB
        val edge     = restEdge(portOut, targetId, portIn)
        val svcA     = mkSvc(nonPiiId, outbound = Set(edge))
        val svcB     = mkSvc(targetId, inbound = Set(Port(portIn)))
        val g        = Graph(Map(nonPiiId -> svcA, targetId -> svcB))
        // nonPiiId is not in piiServices → no check, no error
        val errors   = Checks.piiBoundary(g, piiServices = Set.empty, allowedSinks = Set.empty)
        assertTrue(errors.isEmpty)
      },
    ),
    suite("strongConsistencyOnFinancialTxns")(
      test("financial edge with Eventual consistency yields WeakConsistencyOnFinancialEdge") {
        val edge   = restEdge(portOut, idB, portIn, consistency = Consistency.Eventual)
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val svcA   = mkSvc(idA, outbound = Set(edge))
        val svcB   = mkSvc(idB, inbound = Set(Port(portIn)))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        val errors = Checks.strongConsistencyOnFinancialTxns(g, financialEdges = Set(ref))
        assertTrue(errors == List(WeakConsistencyOnFinancialEdge(ref)))
      },
      test("financial edge with Strong consistency yields no error") {
        val edge   = restEdge(portOut, idB, portIn, consistency = Consistency.Strong)
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val svcA   = mkSvc(idA, outbound = Set(edge))
        val svcB   = mkSvc(idB, inbound = Set(Port(portIn)))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        val errors = Checks.strongConsistencyOnFinancialTxns(g, financialEdges = Set(ref))
        assertTrue(errors.isEmpty)
      },
      test("non-financial edge with Eventual consistency is not checked") {
        val edge   = restEdge(portOut, idB, portIn, consistency = Consistency.Eventual)
        val svcA   = mkSvc(idA, outbound = Set(edge))
        val svcB   = mkSvc(idB, inbound = Set(Port(portIn)))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        // Edge not in financialEdges → no violation
        val errors = Checks.strongConsistencyOnFinancialTxns(g, financialEdges = Set.empty)
        assertTrue(errors.isEmpty)
      },
    ),
    suite("packedDecimalGuard")(
      test("packed-decimal edge without guard yields MissingPackedDecimalGuard") {
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val errors = Checks.packedDecimalGuard(packedEdges = Set(ref), guardedEdges = Set.empty)
        assertTrue(errors == List(MissingPackedDecimalGuard(ref)))
      },
      test("packed-decimal edge with guard yields no error") {
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val errors = Checks.packedDecimalGuard(packedEdges = Set(ref), guardedEdges = Set(ref))
        assertTrue(errors.isEmpty)
      },
      test("guarded edge not in packed set yields no error") {
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val errors = Checks.packedDecimalGuard(packedEdges = Set.empty, guardedEdges = Set(ref))
        assertTrue(errors.isEmpty)
      },
    ),
  )
