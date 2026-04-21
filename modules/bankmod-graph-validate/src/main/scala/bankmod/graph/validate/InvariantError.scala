package bankmod.graph.validate

import bankmod.graph.model.{ EdgeRef, PortName, ServiceId }

/** Sealed hierarchy of all validation errors that can be produced by [[GraphValidator]].
  *
  * Each variant corresponds to one invariant class checked by [[Checks]].
  */
sealed trait InvariantError

object InvariantError:

  /** A directed cycle was detected; `path` lists the services from the cycle entry point back to itself.
    *
    * Example: A→B→C→A is reported as `CycleDetected(List(A, B, C, A))`.
    */
  final case class CycleDetected(path: List[ServiceId]) extends InvariantError

  /** A synchronous edge crosses from a higher-criticality tier to a lower-criticality tier.
    *
    * Per convention: Tier1 > Tier2 > Tier3 in criticality. A Tier1 service calling a Tier2 service synchronously is a
    * violation. Event-protocol edges are exempt.
    */
  final case class TierViolation(from: ServiceId, to: ServiceId) extends InvariantError

  /** An outbound edge references a target service that is not a key in the graph. */
  final case class OrphanEdge(edge: EdgeRef) extends InvariantError

  /** An outbound edge references a [[bankmod.graph.model.PortName]] that does not exist in the target service's
    * declared `inbound` port set.
    *
    * Only `toPort` is verified because `inbound: Set[Port]` models inbound listening ports; there is no separate
    * `outbound: Set[Port]` field in the M2 ADT.
    */
  final case class UnknownPort(service: ServiceId, port: PortName) extends InvariantError

  /** A PII-bearing service routes to a service that is not in the configured allow-list.
    *
    * The PII-bearing services and the allow-list are supplied via [[ValidationConfig]] because the M2 graph ADT carries
    * no PII annotation on [[bankmod.graph.model.Service]] or [[bankmod.graph.model.DataStore]]. The bank architect
    * configures this out-of-band.
    */
  final case class PiiBoundaryCrossed(from: ServiceId, to: ServiceId) extends InvariantError

  /** A financial-transaction edge carries [[bankmod.graph.model.Consistency.Eventual]] rather than
    * [[bankmod.graph.model.Consistency.Strong]].
    *
    * The set of financial edges is supplied via [[ValidationConfig]] for the same reason as PII: the graph ADT carries
    * no financial-txn annotation.
    */
  final case class WeakConsistencyOnFinancialEdge(edge: EdgeRef) extends InvariantError

  /** A packed-decimal edge has no declared conversion guard.
    *
    * A packed-decimal edge is one in `packedEdges`; a guarded edge is one in `guardedEdges`. Any packed-decimal edge
    * that is NOT in the guarded set is a violation.
    */
  final case class MissingPackedDecimalGuard(edge: EdgeRef) extends InvariantError
