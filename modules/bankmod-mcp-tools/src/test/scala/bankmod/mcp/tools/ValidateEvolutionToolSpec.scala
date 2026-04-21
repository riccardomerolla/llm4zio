package bankmod.mcp.tools

import zio.Scope
import zio.test.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*

object ValidateEvolutionToolSpec extends ZIOSpecDefault:

  // ── Fixture helpers ─────────────────────────────────────────────────────────

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get
  private def url: UrlLikeR             = UrlLike.from("https://example.com").toOption.get

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private def svc(
    id: ServiceId,
    inbound: Set[Port],
    outbound: Set[Edge],
  ): Service =
    Service(
      id,
      Criticality.Tier1,
      Ownership.Platform,
      inbound,
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

  // Valid: A -> B on port "in-b"; B exists and declares inbound port "in-b".
  private val a = sid("svc-a")
  private val b = sid("svc-b")
  private val c = sid("svc-c")

  private val validGraph: Graph = Graph(
    Map(
      a -> svc(a, Set.empty, Set(edge(b, "in-b"))),
      b -> svc(b, Set(Port(port("in-b"))), Set.empty),
    )
  )

  // Cycle: A -> B on "in-b"; B -> A on "in-a". Both have matching inbound ports
  // so the only reported error is CycleDetected.
  private val cycleGraph: Graph = Graph(
    Map(
      a -> svc(a, Set(Port(port("in-a"))), Set(edge(b, "in-b"))),
      b -> svc(b, Set(Port(port("in-b"))), Set(edge(a, "in-a"))),
    )
  )

  // Orphan: A -> C, but C is not a key in the services map.
  private val orphanGraph: Graph = Graph(
    Map(
      a -> svc(a, Set.empty, Set(edge(c, "in-c"))),
      b -> svc(b, Set(Port(port("in-b"))), Set.empty),
    )
  )

  private val validJson: String  = Schemas.graphCodec.encodeToString(validGraph)
  private val cycleJson: String  = Schemas.graphCodec.encodeToString(cycleGraph)
  private val orphanJson: String = Schemas.graphCodec.encodeToString(orphanGraph)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ValidateEvolutionTool.run")(
    test("valid graph: accepted=true, no errors, non-empty previewJson") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput(validJson))
      assertTrue(
        out.accepted,
        out.errors.isEmpty,
        out.previewJson.nonEmpty,
      )
    },
    test("valid graph: previewJson round-trips to equal the input graph") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput(validJson))
      Schemas.graphCodec.decode(out.previewJson) match
        case Right(g) => assertTrue(g == validGraph)
        case Left(e)  => assertTrue(false) ?? s"previewJson did not round-trip: $e"
    },
    test("cycle graph: accepted=false with a CycleDetected error") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput(cycleJson))
      assertTrue(
        !out.accepted,
        out.previewJson.isEmpty,
        out.errors.exists(_.kind == "CycleDetected"),
      )
    },
    test("orphan graph: errors contain an OrphanEdge") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput(orphanJson))
      assertTrue(
        !out.accepted,
        out.errors.exists(_.kind == "OrphanEdge"),
      )
    },
    test("invalid JSON: single DecodeError") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput("not json at all"))
      assertTrue(
        !out.accepted,
        out.previewJson.isEmpty,
        out.errors.size == 1,
        out.errors.head.kind == "DecodeError",
      )
    },
    test("empty services JSON: accepted=true (trivially valid)") {
      val out = ValidateEvolutionTool.run(ValidateEvolutionInput("""{"services":{}}"""))
      assertTrue(
        out.accepted,
        out.errors.isEmpty,
        out.previewJson.nonEmpty,
      )
    },
  )
