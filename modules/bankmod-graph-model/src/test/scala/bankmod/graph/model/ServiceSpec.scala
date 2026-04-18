package bankmod.graph.model

import zio.test.*

object ServiceSpec extends ZIOSpecDefault:

  private val sid1 = ServiceId.from("payments-api").toOption.get
  private val sid2 = ServiceId.from("accounts-service").toOption.get
  private val sid3 = ServiceId.from("fraud-detection").toOption.get

  private val inPort  = Port(PortName.from("http-in").toOption.get)
  private val outPort = PortName.from("http-out").toOption.get

  private val defaultSla = Sla(
    latencyP99Ms = Refinements.LatencyMs.from(500).toOption.get,
    availabilityPct = Refinements.Percentage.from(99).toOption.get,
    maxRetries = Refinements.BoundedRetries.from(3).toOption.get,
  )

  private def mkService(
    id: ServiceId,
    inbound: Set[Port] = Set.empty,
    outbound: Set[Edge] = Set.empty,
  ): Service =
    Service(
      id = id,
      tier = Criticality.Tier1,
      owner = Ownership.Platform,
      inbound = inbound,
      outbound = outbound,
      schemas = Set.empty,
      dataStores = Set.empty,
      sla = defaultSla,
    )

  def spec: Spec[TestEnvironment, Any] = suite("Service")(
    test("construction with empty inbound succeeds (entry-point service)") {
      val svc = mkService(sid1, inbound = Set.empty)
      assertTrue(svc.inbound.isEmpty)
    },
    test("construction with populated inbound succeeds") {
      val svc = mkService(sid1, inbound = Set(inPort))
      assertTrue(svc.inbound.size == 1)
    },
    test("construction with outbound edge to unknown target succeeds") {
      val proto = Protocol.rest("https://accounts.svc/v1").toOption.get
      val edge = Edge(
        fromPort = outPort,
        toService = sid2,
        toPort = PortName.from("http-in").toOption.get,
        protocol = proto,
        consistency = Consistency.Eventual,
        ordering = Ordering.Unordered,
      )
      val svc = mkService(sid1, outbound = Set(edge))
      assertTrue(svc.outbound.size == 1)
    },
    test("Graph construction with multiple services succeeds") {
      val svc1 = mkService(sid1)
      val svc2 = mkService(sid2)
      val svc3 = mkService(sid3)
      val graph = Graph(services = Map(sid1 -> svc1, sid2 -> svc2, sid3 -> svc3))
      assertTrue(graph.services.size == 3)
    },
    test("Graph allows cross-service edges without validation at construction time") {
      val proto = Protocol.grpc("grpc://fraud.svc:50051").toOption.get
      val edge = Edge(
        fromPort = outPort,
        toService = sid3,
        toPort = PortName.from("grpc-in").toOption.get,
        protocol = proto,
        consistency = Consistency.Strong,
        ordering = Ordering.TotalOrder,
      )
      // sid3 is NOT in the graph — construction should still succeed
      val svc1 = mkService(sid1, outbound = Set(edge))
      val graph = Graph(services = Map(sid1 -> svc1))
      assertTrue(graph.services.contains(sid1))
    },
  )
