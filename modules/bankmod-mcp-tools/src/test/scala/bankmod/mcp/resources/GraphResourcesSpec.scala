package bankmod.mcp.resources

import zio.test.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*

object GraphResourcesSpec extends ZIOSpecDefault:

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get
  private def url: UrlLikeR             = UrlLike.from("https://example.com").toOption.get

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private def svc(id: ServiceId, inbound: Set[Port], outbound: Set[Edge]): Service =
    Service(id, Criticality.Tier1, Ownership.Platform, inbound, outbound, Set.empty, Set.empty, sla)

  private val a = sid("svc-a")
  private val b = sid("svc-b")

  private val edgeAB = Edge(port("out"), b, port("in"), Protocol.Rest(url), Consistency.Strong, Ordering.TotalOrder)

  private val fixture = Graph(
    Map(
      a -> svc(a, inbound = Set.empty, outbound = Set(edgeAB)),
      b -> svc(b, inbound = Set(Port(port("in"))), outbound = Set.empty),
    )
  )

  def spec = suite("GraphResources.read")(
    test("graph://full returns JSON that round-trips to the fixture") {
      val result  = GraphResources.read("graph://full", fixture)
      val decoded = result.toOption.flatMap(r => Schemas.graphCodec.decode(r.body).toOption)
      assertTrue(
        result.isRight,
        result.toOption.get.mimeType == "application/json",
        decoded == Some(fixture),
      )
    },
    test("graph://service/{id} returns a body containing the service id") {
      val result = GraphResources.read("graph://service/svc-a", fixture)
      assertTrue(
        result.isRight,
        result.toOption.get.body.contains("svc-a"),
      )
    },
    test("graph://service/{unknown} returns Left") {
      val result = GraphResources.read("graph://service/svc-missing", fixture)
      assertTrue(result.isLeft)
    },
    test("graph://edge/{from}/{to}/{port} returns a body with the edge fields") {
      val result = GraphResources.read("graph://edge/svc-a/svc-b/in", fixture)
      val body   = result.toOption.map(_.body).getOrElse("")
      assertTrue(
        result.isRight,
        body.contains("svc-a"),
        body.contains("svc-b"),
        body.contains("\"in\""),
      )
    },
    test("graph://edge with wrong port returns Left") {
      val result = GraphResources.read("graph://edge/svc-a/svc-b/wrong-port", fixture)
      assertTrue(result.isLeft)
    },
    test("graph://invariant/{kind} returns the catalog entry") {
      val result = GraphResources.read("graph://invariant/CycleDetected", fixture)
      assertTrue(
        result.isRight,
        result.toOption.get.body.contains("CycleDetected"),
      )
    },
    test("graph://invariant/{unknown} returns Left") {
      val result = GraphResources.read("graph://invariant/NotARealKind", fixture)
      assertTrue(result.isLeft)
    },
    test("graph://slice/{id}/{depth} returns a neighborhood containing the target") {
      val result = GraphResources.read("graph://slice/svc-a/1", fixture)
      assertTrue(
        result.isRight,
        result.toOption.get.body.contains("svc-b"),
      )
    },
    test("graph://slice with non-numeric depth returns Left") {
      val result = GraphResources.read("graph://slice/svc-a/notanumber", fixture)
      assertTrue(result.isLeft)
    },
    test("unknown URI returns Left") {
      val result = GraphResources.read("graph://unknown/thing", fixture)
      assertTrue(result.isLeft)
    },
  )
