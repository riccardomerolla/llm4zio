package bankmod.mcp.tools

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*
import bankmod.mcp.{GraphStore, GraphStoreLive}

object ProposeServiceToolSpec extends ZIOSpecDefault:

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

  private val a = sid("svc-a")
  private val b = sid("svc-b")

  // Seed: a single-service graph containing just A with one inbound port "in-a".
  private val seedGraph: Graph = Graph(
    Map(
      a -> svc(a, Set(Port(port("in-a"))), Set.empty)
    )
  )

  // Valid patch: A (unchanged) + B with matching inbound port, edge A -> B on "in-b".
  private val validPatch: Graph = Graph(
    Map(
      a -> svc(a, Set(Port(port("in-a"))), Set(edge(b, "in-b"))),
      b -> svc(b, Set(Port(port("in-b"))), Set.empty),
    )
  )

  // Cycle: A -> B, B -> A. Both with matching inbound ports so the only error is CycleDetected.
  private val cyclePatch: Graph = Graph(
    Map(
      a -> svc(a, Set(Port(port("in-a"))), Set(edge(b, "in-b"))),
      b -> svc(b, Set(Port(port("in-b"))), Set(edge(a, "in-a"))),
    )
  )

  private val validJson: String = Schemas.graphCodec.encodeToString(validPatch)
  private val cycleJson: String = Schemas.graphCodec.encodeToString(cyclePatch)

  private val storeLayer: ULayer[GraphStore] =
    ZLayer.succeed(seedGraph) >>> GraphStoreLive.layer

  // ── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] = suite("ProposeServiceTool.handle")(
    test("valid patch: committed=true, store mutated, previewJson round-trips") {
      (for
        store   <- ZIO.service[GraphStore]
        out     <- ProposeServiceTool.handle(ProposeServiceInput(validJson))
        current <- store.get
        decoded  = Schemas.graphCodec.decode(out.committedJson)
      yield assertTrue(
        out.committed,
        out.errors.isEmpty,
        out.committedJson.nonEmpty,
        current == validPatch,
        decoded == Right(validPatch),
      )).provide(storeLayer)
    },
    test("cycle patch: committed=false, CycleDetected error, store unchanged") {
      (for
        store   <- ZIO.service[GraphStore]
        out     <- ProposeServiceTool.handle(ProposeServiceInput(cycleJson))
        current <- store.get
      yield assertTrue(
        !out.committed,
        out.committedJson.isEmpty,
        out.errors.exists(_.kind == "CycleDetected"),
        current == seedGraph,
      )).provide(storeLayer)
    },
    test("invalid JSON: committed=false, DecodeError, store unchanged") {
      (for
        store   <- ZIO.service[GraphStore]
        out     <- ProposeServiceTool.handle(ProposeServiceInput("not json at all"))
        current <- store.get
      yield assertTrue(
        !out.committed,
        out.committedJson.isEmpty,
        out.errors.exists(_.kind == "DecodeError"),
        current == seedGraph,
      )).provide(storeLayer)
    },
    test("successful commit publishes to updates hub") {
      (for
        store   <- ZIO.service[GraphStore]
        fiber   <- store.updates.take(1).runCollect.fork
        _       <- ZIO.sleep(100.millis)
        out     <- ProposeServiceTool.handle(ProposeServiceInput(validJson))
        emitted <- fiber.join
      yield assertTrue(
        out.committed,
        emitted.toList == List(validPatch),
      )).provide(storeLayer)
    },
  ) @@ withLiveClock
