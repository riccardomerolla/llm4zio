package bankmod.app

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import bankmod.graph.model.Refinements.*
import bankmod.graph.model.{ Schemas, * }
import bankmod.mcp.{ GraphStore, GraphStoreLive }

object ModelWatcherSpec extends ZIOSpecDefault:

  // ── Fixtures ─────────────────────────────────────────────────────────────

  private val sidA = ServiceId.from("svc-a").toOption.get
  private val sidB = ServiceId.from("svc-b").toOption.get
  private val pIn  = PortName.from("in").toOption.get
  private val pOut = PortName.from("out").toOption.get
  private val url  = UrlLike.from("https://example.com").toOption.get

  private val sla = Sla(
    LatencyMs.from(100).toOption.get,
    Percentage.from(99).toOption.get,
    BoundedRetries.from(3).toOption.get,
  )

  private val edgeAB =
    Edge(pOut, sidB, pIn, Protocol.Rest(url), Consistency.Strong, Ordering.TotalOrder)

  private val seed = Graph(
    Map(
      sidA -> Service(sidA, Criticality.Tier1, Ownership.Platform, Set.empty, Set(edgeAB), Set.empty, Set.empty, sla),
      sidB -> Service(sidB, Criticality.Tier1, Ownership.Platform, Set(Port(pIn)), Set.empty, Set.empty, Set.empty, sla),
    )
  )

  private val updated = Graph(
    Map(
      sidA -> Service(sidA, Criticality.Tier1, Ownership.Platform, Set.empty, Set.empty, Set.empty, Set.empty, sla),
      sidB -> Service(sidB, Criticality.Tier1, Ownership.Platform, Set(Port(pIn)), Set.empty, Set.empty, Set.empty, sla),
    )
  )

  private def tempJson(g: Graph): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val f = Files.createTempFile("bankmod-watcher-", ".json")
        Files.writeString(f, Schemas.graphCodec.encodeToString(g))
        f
      }
    )(f => ZIO.attemptBlocking(Files.deleteIfExists(f)).orDie)

  private val storeLayer: ZLayer[Any, Nothing, GraphStore] =
    ZLayer.succeed[Graph](seed) >>> GraphStoreLive.layer

  // ── Spec ─────────────────────────────────────────────────────────────────

  def spec: Spec[Any, Any] = suite("ModelWatcher")(
    test("reload: reads a valid JSON file and swaps the graph") {
      ZIO.scoped {
        for
          path  <- tempJson(updated)
          _     <- ModelWatcher.reload(path)
          store <- ZIO.service[GraphStore]
          now   <- store.get
        yield assertTrue(now == updated)
      }
    },
    test("reload: malformed JSON leaves the store unchanged") {
      ZIO.scoped {
        for
          path  <- ZIO.acquireRelease(
                     ZIO.attemptBlocking {
                       val f = Files.createTempFile("bankmod-watcher-bad-", ".json")
                       Files.writeString(f, "{ not valid json")
                       f
                     }
                   )(f => ZIO.attemptBlocking(Files.deleteIfExists(f)).orDie)
          _     <- ModelWatcher.reload(path)
          store <- ZIO.service[GraphStore]
          now   <- store.get
        yield assertTrue(now == seed)
      }
    },
    test("watch: writing to the file publishes an update on the store hub") {
      ZIO.scoped {
        for
          path       <- tempJson(seed)
          store      <- ZIO.service[GraphStore]
          _          <- ModelWatcher.reload(path) // prime: file == store == seed
          subscribed <- Promise.make[Nothing, Graph]
          _          <- store.updates.runHead
                          .flatMap(g => ZIO.foreachDiscard(g)(subscribed.succeed))
                          .fork
          _          <- ZIO.sleep(200.millis)     // let the subscriber attach to the hub
          _          <- ModelWatcher.watch(path).forkScoped
          _          <- ZIO.sleep(300.millis)     // let watch() finish its initial no-op reload
          _          <- ZIO.attemptBlocking(
                          Files.writeString(path, Schemas.graphCodec.encodeToString(updated))
                        ).orDie
          observed   <- subscribed.await.timeout(8.seconds).someOrFailException
        yield assertTrue(observed == updated)
      }
    } @@ withLiveClock @@ timeout(15.seconds),
  ).provide(storeLayer)
