package bankmod.graph.model

/** Service criticality tier. */
enum Criticality:
  case Tier1 // Highest criticality — customer-facing, revenue-critical
  case Tier2 // Important internal services
  case Tier3 // Support / analytics

/** Data consistency guarantee expected on interactions. */
enum Consistency:
  case Strong   // ACID / linearizable
  case Eventual // BASE / causal

/** Message ordering guarantee for event-driven interactions. */
enum Ordering:
  case TotalOrder   // All consumers see events in the same order
  case PartialOrder // Ordered within a partition
  case Unordered    // No ordering guarantee

/** Organisational ownership of a service. */
enum Ownership:
  case Platform // Core infrastructure team
  case Product  // Product / feature team
  case External // Third-party / partner
  case Shared   // Shared ownership / guild
