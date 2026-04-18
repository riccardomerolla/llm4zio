package bankmod.graph.model

/** Structural invariants that can be checked against a [[Graph]].
  *
  * Each variant encodes a distinct safety property. The `bankmod-graph-validate` module implements the actual
  * evaluation logic against a concrete [[Graph]].
  */
enum Invariant:

  /** No directed cycles exist among the given service scope. */
  case NoCycle(scope: Set[ServiceId])

  /** Service tier numbers are monotonically increasing from callers to callees (Tier1 → Tier2 → Tier3). */
  case TierMonotonic

  /** The specified event edge delivers at-least-once. */
  case EventAtLeastOnce(edge: EdgeRef)

  /** PII data may only be accessed by services in the allowed set. */
  case PiiBoundary(allowed: Set[ServiceId])

  /** No cross-tier PII flow exists in the given scope. */
  case NoCrossTierPiiFlow(scope: Set[ServiceId])

  /** Strong consistency is enforced on all edges that participate in financial transactions. */
  case StrongConsistencyOnFinancialTxns(edges: Set[EdgeRef])

  /** Packed-decimal boundary conversions are checked at the specified edge. */
  case PackedDecimalBoundaryChecked(edge: EdgeRef)
