package bankmod.mcp

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*
import bankmod.graph.validate.InvariantError

object GraphStoreSpec extends ZIOSpecDefault:

  // ── Fixtures ─────────────────────────────────────────────────────────────

  private val sid1 = ServiceId.from("svc-one").toOption.get
  private val sid2 = ServiceId.from("svc-two").toOption.get

  private val defaultSla = Sla(
    latencyP99Ms = LatencyMs.from(200).toOption.get,
    availabilityPct = Percentage.from(99).toOption.get,
    maxRetries = BoundedRetries.from(3).toOption.get,
  )

  private def mkService(id: ServiceId): Service =
    Service(
      id = id,
      tier = Criticality.Tier1,
      owner = Ownership.Platform,
      inbound = Set.empty,
      outbound = Set.empty,
      schemas = Set.empty,
      dataStores = Set.empty,
      sla = defaultSla,
    )

  private val seed: Graph    = Graph(services = Map(sid1 -> mkService(sid1)))
  private val updated: Graph = Graph(services = Map(sid1 -> mkService(sid1), sid2 -> mkService(sid2)))

  private val bogusErrors: NonEmptyChunk[InvariantError] =
    NonEmptyChunk.single(InvariantError.OrphanEdge(EdgeRef(sid1, sid2, PortName.from("x").toOption.get)))

  // ── Tests ────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] = suite("GraphStore")(
    test("seeded state matches sample") {
      for
        store   <- ZIO.service[GraphStore]
        current <- store.get
      yield assertTrue(current == seed)
    },
    test("successful update publishes to Hub and mutates state") {
      for
        store    <- ZIO.service[GraphStore]
        // Subscribe BEFORE performing the update so the emitted value is observed.
        fiber    <- store.updates.take(1).runCollect.fork
        _        <- ZIO.sleep(100.millis)
        returned <- store.update(_ => Right(updated))
        emitted  <- fiber.join
        current  <- store.get
      yield assertTrue(
        returned == updated,
        current == updated,
        emitted.toList == List(updated),
      )
    },
    test("failed update does not publish or mutate") {
      for
        store   <- ZIO.service[GraphStore]
        fiber   <- store.updates.interruptAfter(300.millis).runCollect.fork
        exit    <- store.update(_ => Left(bogusErrors)).exit
        emitted <- fiber.join
        current <- store.get
      yield assertTrue(
        exit == Exit.fail(bogusErrors),
        emitted.isEmpty,
        current == seed,
      )
    },
  ).provide(ZLayer.succeed(seed), GraphStoreLive.layer) @@ withLiveClock
