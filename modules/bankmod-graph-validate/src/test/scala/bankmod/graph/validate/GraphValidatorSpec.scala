package bankmod.graph.validate

import zio.test.{ Gen, * }

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*
import bankmod.graph.validate.InvariantError.*

object GraphValidatorSpec extends ZIOSpecDefault:

  // ── Fixture helpers ─────────────────────────────────────────────────────────

  private val defaultSla: Sla = Sla(
    latencyP99Ms = LatencyMs.from(200).toOption.get,
    availabilityPct = Percentage.from(99).toOption.get,
    maxRetries = BoundedRetries.from(3).toOption.get,
  )

  private def sid(s: String): ServiceId = ServiceId.from(s).toOption.get
  private def port(s: String): PortName = PortName.from(s).toOption.get

  private val portIn: PortName  = port("http-in")
  private val portOut: PortName = port("http-out")

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
      protocol = Protocol.rest("https://example.com/v1").toOption.get,
      consistency = consistency,
      ordering = Ordering.Unordered,
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

  // ── Service ID index for property tests ────────────────────────────────────
  // Precomputed pool of valid ServiceIds for use in generators.
  // ServiceId must match ^[a-z][a-z0-9-]{1,40}$, so "svc-00" through "svc-19" are all valid.
  private val svcIdPool: Vector[ServiceId] =
    (0 until 20).map(i => sid(s"svc-${"%02d".format(i)}")).toVector

  // ── Property test generators ─────────────────────────────────────────────

  /** Generate an acyclic graph with 3 to 8 services.
    *
    * Edges are only allowed from service at index i to service at index j where j > i (using the sorted position in
    * `svcIdPool`). This topological construction guarantees a DAG. All declared `toPort` values are registered in the
    * target service's `inbound` set so the structural check passes cleanly.
    */
  private val genDagGraph: Gen[Any, Graph] =
    Gen.int(3, 8).flatMap { n =>
      val ids = svcIdPool.take(n)
      // For each service index i, pick 0 or 1 target index j > i
      Gen.collectAll(ids.indices.toList.map { i =>
        val possibleTargets = ids.indices.toList.drop(i + 1)
        if possibleTargets.isEmpty then Gen.const(Option.empty[Int])
        else Gen.oneOf(Gen.const(Option.empty[Int]), Gen.int(i + 1, n - 1).map(Some.apply))
      }).map { maybeTargets =>
        // maybeTargets(i) = Some(j) means service i has one outbound edge to service j
        val indexedEdges: List[(Int, Int)] = maybeTargets.zipWithIndex.flatMap {
          case (Some(j), i) => List((i, j))
          case (None, _)    => List.empty
        }

        // Which indices have inbound edges (to build Port declarations)
        val inboundFor: Map[Int, Set[PortName]] =
          indexedEdges
            .map { case (_, j) => j -> portIn }
            .groupBy(_._1)
            .map { case (k, vs) => k -> vs.map(_._2).toSet }

        // Map from service-i to its outbound target index
        val outboundFor: Map[Int, Int] = indexedEdges.toMap

        val services: Map[ServiceId, Service] = ids.indices.toList.map { i =>
          val fromId              = ids(i)
          val outEdges: Set[Edge] = outboundFor.get(i).map { j =>
            restEdge(portOut, ids(j), portIn)
          }.toSet
          val inPorts: Set[Port]  = inboundFor.getOrElse(i, Set.empty).map(Port.apply)
          fromId -> mkSvc(fromId, inbound = inPorts, outbound = outEdges)
        }.toMap

        Graph(services)
      }
    }

  /** Generate a graph with a guaranteed cycle by building a DAG then injecting a back-edge from the last service back
    * to the first service.
    */
  private val genCyclicGraph: Gen[Any, Graph] =
    Gen.int(3, 8).map { n =>
      val ids = svcIdPool.take(n)

      // Build a simple chain: ids(0)→ids(1)→...→ids(n-1), then inject ids(n-1)→ids(0) for a cycle.
      val services: Map[ServiceId, Service] = ids.indices.map { i =>
        val id                 = ids(i)
        val outEdges           =
          if i < n - 1 then
            // Chain edge to next
            Set(restEdge(portOut, ids(i + 1), portIn))
          else
            // Back-edge from last node back to first → creates cycle
            Set(restEdge(portOut, ids(0), portIn))
        val inPorts: Set[Port] = Set(Port(portIn)) // every node accepts inbound
        id -> mkSvc(id, inbound = inPorts, outbound = outEdges)
      }.toMap

      Graph(services)
    }

  // ── Tests ───────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment, Any] = suite("GraphValidator")(
    suite("validate (context-free overload)")(
      test("valid single-service graph passes validation") {
        val idA = sid("svc-alpha")
        val svc = mkSvc(idA)
        val g   = Graph(Map(idA -> svc))
        assertTrue(GraphValidator.validate(g) == Right(g))
      },
      test("valid DAG with correct ports passes validation") {
        val idA  = sid("svc-alpha")
        val idB  = sid("svc-bravo")
        val edge = restEdge(portOut, idB, portIn)
        val svcA = mkSvc(idA, outbound = Set(edge))
        val svcB = mkSvc(idB, inbound = Set(Port(portIn)))
        val g    = Graph(Map(idA -> svcA, idB -> svcB))
        assertTrue(GraphValidator.validate(g) == Right(g))
      },
      test("cyclic graph yields Left with at least one CycleDetected") {
        val idA    = sid("svc-alpha")
        val idB    = sid("svc-bravo")
        val edgeAB = restEdge(portOut, idB, portIn)
        val edgeBA = restEdge(portOut, idA, portIn)
        val svcA   = mkSvc(idA, inbound = Set(Port(portIn)), outbound = Set(edgeAB))
        val svcB   = mkSvc(idB, inbound = Set(Port(portIn)), outbound = Set(edgeBA))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        val result = GraphValidator.validate(g)
        assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.exists(_.isInstanceOf[CycleDetected])),
        )
      },
    ),
    suite("validate (ValidationConfig overload)")(
      test("multiple simultaneous violations are all reported") {
        // Set up: 1 cycle, 1 orphan edge, 1 PII boundary violation, 1 weak consistency
        val idA     = sid("svc-alpha")
        val idB     = sid("svc-bravo")
        val idC     = sid("svc-charlie")
        val missing = sid("missing-svc")

        // A→B (cycle: A→B→A) + A→missing (orphan) + A→C (PII leak)
        val edgeAB     = restEdge(portOut, idB, portIn, consistency = Consistency.Eventual)
        val edgeBA     = restEdge(portOut, idA, portIn)
        val edgeOrphan = restEdge(portOut, missing, portIn)
        val edgePii    = restEdge(portOut, idC, portIn)
        val svcA       = mkSvc(idA, inbound = Set(Port(portIn)), outbound = Set(edgeAB, edgeOrphan, edgePii))
        val svcB       = mkSvc(idB, inbound = Set(Port(portIn)), outbound = Set(edgeBA))
        val svcC       = mkSvc(idC, inbound = Set(Port(portIn)))
        val g          = Graph(Map(idA -> svcA, idB -> svcB, idC -> svcC))

        val financialRef = EdgeRef(from = idA, to = idB, port = portIn)
        val cfg          = ValidationConfig(
          piiServices = Set(idA),
          allowedPiiSinks = Set(idB), // only idB allowed; idC is a violation
          financialEdges = Set(financialRef),
        )

        val result = GraphValidator.validate(g, cfg)

        assertTrue(
          result.isLeft,
          result.left.toOption.exists { errors =>
            val list = errors.toList
            list.exists(_.isInstanceOf[CycleDetected]) &&
            list.exists(_.isInstanceOf[OrphanEdge]) &&
            list.exists(_.isInstanceOf[PiiBoundaryCrossed]) &&
            list.exists(_.isInstanceOf[WeakConsistencyOnFinancialEdge])
          },
        )
      },
      test("packed-decimal config violation is reported") {
        val idA    = sid("svc-alpha")
        val idB    = sid("svc-bravo")
        val edge   = restEdge(portOut, idB, portIn)
        val svcA   = mkSvc(idA, outbound = Set(edge))
        val svcB   = mkSvc(idB, inbound = Set(Port(portIn)))
        val g      = Graph(Map(idA -> svcA, idB -> svcB))
        val ref    = EdgeRef(from = idA, to = idB, port = portIn)
        val cfg    = ValidationConfig(packedEdges = Set(ref), guardedEdges = Set.empty)
        val result = GraphValidator.validate(g, cfg)
        assertTrue(
          result.isLeft,
          result.left.toOption.exists(_.exists(_.isInstanceOf[MissingPackedDecimalGuard])),
        )
      },
    ),
    suite("property tests")(
      test("any generated DAG passes context-free validation (100 samples)") {
        check(genDagGraph) { g =>
          assertTrue(GraphValidator.validate(g).isRight)
        }
      } @@ TestAspect.samples(100),
      test("any graph with injected cycle produces at least one CycleDetected (100 samples)") {
        check(genCyclicGraph) { g =>
          val result = GraphValidator.validate(g)
          assertTrue(
            result.isLeft,
            result.left.toOption.exists(_.exists(_.isInstanceOf[CycleDetected])),
          )
        }
      } @@ TestAspect.samples(100),
    ),
  )
