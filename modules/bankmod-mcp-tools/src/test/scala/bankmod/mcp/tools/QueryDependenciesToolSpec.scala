package bankmod.mcp.tools

import zio.Scope
import zio.test.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*

object QueryDependenciesToolSpec extends ZIOSpecDefault:

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get
  private def url: UrlLikeR             = UrlLike.from("https://example.com").toOption.get

  private val a = sid("svc-a")
  private val b = sid("svc-b")
  private val c = sid("svc-c")
  private val d = sid("svc-d")

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private def svc(id: ServiceId, outbound: Set[Edge]): Service =
    Service(
      id,
      Criticality.Tier1,
      Ownership.Platform,
      Set.empty,
      outbound,
      Set.empty,
      Set.empty,
      sla,
    )

  private def edge(to: ServiceId, portName: String): Edge =
    Edge(
      port("out"),
      to,
      port(portName),
      Protocol.Rest(url),
      Consistency.Strong,
      Ordering.TotalOrder,
    )

  private val fixture = Graph(
    Map(
      a -> svc(a, Set(edge(b, "in"), edge(d, "in"))),
      b -> svc(b, Set(edge(c, "in"))),
      c -> svc(c, Set.empty),
      d -> svc(d, Set.empty),
    )
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("QueryDependenciesTool.run")(
    test("depth=1 from A returns 2 hops: A->B and A->D") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("svc-a", 1), fixture)
      result match
        case Right(out) =>
          val pairs = out.neighborhood.map(h => (h.from, h.to)).toSet
          assertTrue(
            out.root == "svc-a",
            out.neighborhood.size == 2,
            pairs == Set(("svc-a", "svc-b"), ("svc-a", "svc-d")),
            out.neighborhood.forall(_.hopDepth == 1),
          )
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("depth=2 from A returns 3 hops including B->C") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("svc-a", 2), fixture)
      result match
        case Right(out) =>
          val triples = out.neighborhood.map(h => (h.from, h.to, h.hopDepth)).toSet
          assertTrue(
            out.neighborhood.size == 3,
            triples == Set(
              ("svc-a", "svc-b", 1),
              ("svc-a", "svc-d", 1),
              ("svc-b", "svc-c", 2),
            ),
          )
        case Left(f)    =>
          assertTrue(false) ?? s"expected Right, got Left($f)"
    },
    test("unknown serviceId produces Failure") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("svc-missing", 1), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("Unknown service"),
      )
    },
    test("invalid ServiceId pattern produces Failure") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("Bad Name", 1), fixture)
      assertTrue(result.isLeft)
    },
    test("depth=0 produces Failure (out of range)") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("svc-a", 0), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("depth must be in"),
      )
    },
    test("depth=6 produces Failure (out of range)") {
      val result = QueryDependenciesTool.run(QueryDependenciesInput("svc-a", 6), fixture)
      assertTrue(
        result.isLeft,
        result.swap.toOption.get.message.contains("depth must be in"),
      )
    },
  )
