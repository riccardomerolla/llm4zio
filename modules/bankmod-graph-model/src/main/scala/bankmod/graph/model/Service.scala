package bankmod.graph.model

import Refinements.*

/** A named port on a service — the attachment point for inbound connections. */
final case class Port(name: PortName)

/** Service Level Agreement parameters for a service. */
final case class Sla(
  latencyP99Ms: LatencyMsR,
  availabilityPct: PercentageR,
  maxRetries: BoundedRetriesR,
)

/** A reference to an external schema (e.g. Avro / Protobuf / JSON Schema). */
final case class SchemaRef(
  hash: SchemaHash,
  version: SemVerR,
)

/** A typed reference to a (from, to, port) triple — used in invariants. */
final case class EdgeRef(
  from: ServiceId,
  to: ServiceId,
  port: PortName,
)

/** A directional dependency from a source port to a target service+port via a protocol. */
final case class Edge(
  fromPort: PortName,
  toService: ServiceId,
  toPort: PortName,
  protocol: Protocol,
  consistency: Consistency,
  ordering: Ordering,
)

/** A backing data store for a service (e.g. database table, cache, topic). */
final case class DataStore(
  name: TableName,
  schemaRef: Option[SchemaRef],
)

/** A single microservice node in the graph. */
final case class Service(
  id: ServiceId,
  tier: Criticality,
  owner: Ownership,
  inbound: Set[Port],
  outbound: Set[Edge],
  schemas: Set[SchemaRef],
  dataStores: Set[DataStore],
  sla: Sla,
)

/** The top-level services graph — keyed by ServiceId. */
final case class Graph(services: Map[ServiceId, Service])
