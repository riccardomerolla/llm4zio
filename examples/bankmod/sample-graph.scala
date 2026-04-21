// This file is NOT compiled by sbt.
// It is a standalone reference copy of the canonical 8-service fixture.
// The source-of-truth version (compiled and used by golden-file tests) lives at:
//   modules/bankmod-graph-render/src/test/scala/bankmod/graph/render/SampleGraph.scala
//
// Usage (Scala REPL or scala-cli):
//   scala-cli sample-graph.scala
//
// To render to Mermaid:
//   val mmd = MermaidInterpreter.render(SampleGraph.sample)
//   println(mmd)

package bankmod.graph.render

import bankmod.graph.model.*
import bankmod.graph.model.Refinements.*

/** Canonical 8-service fixture — 3 tiers, event-driven edges, PII boundary.
  *
  * Services:
  *   - Tier1 (highest criticality): `accounts-api`, `ledger-core`, `payments-engine`
  *   - Tier2 (important internal): `customer-profile`, `fraud-scorer`, `statement-service`
  *   - Tier3 (support): `audit-log`, `notification-bus`
  *
  * Notable edges:
  *   - `payments-engine` → `ledger-core` (REST, Strong, TotalOrder) — packed-decimal-guarded boundary
  *   - `ledger-core` → `statement-service` (Event on `ledger.posted`, Eventual, PartialOrder) — event-driven
  *   - `customer-profile` holds PII; allowed sinks per PiiBoundary invariant: `audit-log`, `accounts-api`
  */
object SampleGraph:

  private def sid(s: String): ServiceId   = ServiceId.from(s).toOption.get
  private def port(s: String): PortName   = PortName.from(s).toOption.get
  private def topic(s: String): TopicName = TopicName.from(s).toOption.get

  private def sla(latMs: Int, availPct: Int, retries: Int): Sla = Sla(
    latencyP99Ms    = LatencyMs.from(latMs).toOption.get,
    availabilityPct = Percentage.from(availPct).toOption.get,
    maxRetries      = BoundedRetries.from(retries).toOption.get,
  )

  private def restEdge(fromPort: String, toSvc: String, toPort: String): Edge =
    Edge(
      fromPort    = port(fromPort),
      toService   = sid(toSvc),
      toPort      = port(toPort),
      protocol    = Protocol.rest(s"https://$toSvc.svc/v1").toOption.get,
      consistency = Consistency.Strong,
      ordering    = Ordering.TotalOrder,
    )

  private def grpcEdge(fromPort: String, toSvc: String, toPort: String): Edge =
    Edge(
      fromPort    = port(fromPort),
      toService   = sid(toSvc),
      toPort      = port(toPort),
      protocol    = Protocol.grpc(s"grpc://$toSvc.svc:50051").toOption.get,
      consistency = Consistency.Strong,
      ordering    = Ordering.TotalOrder,
    )

  private def eventEdge(fromPort: String, toSvc: String, toPort: String, topicStr: String): Edge =
    Edge(
      fromPort    = port(fromPort),
      toService   = sid(toSvc),
      toPort      = port(toPort),
      protocol    = Protocol.Event(topic(topicStr)),
      consistency = Consistency.Eventual,
      ordering    = Ordering.PartialOrder,
    )

  val sample: Graph = Graph(
    services = Map(
      sid("accounts-api") -> Service(
        id         = sid("accounts-api"),
        tier       = Criticality.Tier1,
        owner      = Ownership.Product,
        inbound    = Set(Port(port("http-in"))),
        outbound   = Set(
          restEdge("http-out", "ledger-core", "http-in"),
          grpcEdge("grpc-out", "customer-profile", "grpc-in"),
        ),
        schemas    = Set.empty,
        dataStores = Set.empty,
        sla        = sla(100, 99, 3),
      ),
      sid("ledger-core") -> Service(
        id         = sid("ledger-core"),
        tier       = Criticality.Tier1,
        owner      = Ownership.Platform,
        inbound    = Set(Port(port("http-in"))),
        outbound   = Set(
          eventEdge("event-out", "statement-service", "event-in", "ledger.posted"),
        ),
        schemas    = Set.empty,
        dataStores = Set(DataStore(TableName.from("ledger-entries").toOption.get, None)),
        sla        = sla(50, 99, 3),
      ),
      sid("payments-engine") -> Service(
        id         = sid("payments-engine"),
        tier       = Criticality.Tier1,
        owner      = Ownership.Platform,
        inbound    = Set(Port(port("http-in"))),
        outbound   = Set(
          restEdge("http-out", "ledger-core", "http-in"),  // packed-decimal-guarded boundary
          grpcEdge("grpc-out", "fraud-scorer", "grpc-in"),
        ),
        schemas    = Set.empty,
        dataStores = Set.empty,
        sla        = sla(200, 99, 3),
      ),
      sid("customer-profile") -> Service(
        id         = sid("customer-profile"),
        tier       = Criticality.Tier2,
        owner      = Ownership.Product,
        // PII boundary: holds PII; allowed sinks: audit-log, accounts-api
        inbound    = Set(Port(port("grpc-in"))),
        outbound   = Set(
          eventEdge("event-out", "audit-log", "event-in", "profile.updated"),
        ),
        schemas    = Set.empty,
        dataStores = Set(DataStore(TableName.from("customer-pii").toOption.get, None)),
        sla        = sla(150, 99, 3),
      ),
      sid("fraud-scorer") -> Service(
        id         = sid("fraud-scorer"),
        tier       = Criticality.Tier2,
        owner      = Ownership.Platform,
        inbound    = Set(Port(port("grpc-in"))),
        outbound   = Set(
          eventEdge("event-out", "audit-log", "event-in", "fraud.scored"),
        ),
        schemas    = Set.empty,
        dataStores = Set.empty,
        sla        = sla(300, 99, 3),
      ),
      sid("statement-service") -> Service(
        id         = sid("statement-service"),
        tier       = Criticality.Tier2,
        owner      = Ownership.Product,
        inbound    = Set(Port(port("event-in"))),
        outbound   = Set(
          eventEdge("event-out", "notification-bus", "event-in", "statements.ready"),
        ),
        schemas    = Set.empty,
        dataStores = Set.empty,
        sla        = sla(500, 99, 3),
      ),
      sid("audit-log") -> Service(
        id         = sid("audit-log"),
        tier       = Criticality.Tier3,
        owner      = Ownership.Shared,
        inbound    = Set(Port(port("event-in"))),
        outbound   = Set.empty,
        schemas    = Set.empty,
        dataStores = Set(DataStore(TableName.from("audit-events").toOption.get, None)),
        sla        = sla(1000, 99, 0),
      ),
      sid("notification-bus") -> Service(
        id         = sid("notification-bus"),
        tier       = Criticality.Tier3,
        owner      = Ownership.Shared,
        inbound    = Set(Port(port("event-in"))),
        outbound   = Set.empty,
        schemas    = Set.empty,
        dataStores = Set.empty,
        sla        = sla(800, 99, 0),
      ),
    )
  )
