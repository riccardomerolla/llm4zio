# Scenario 2 — Tier Monotonicity Violation

**Goal:** Architect proposes a dependency that would make a Tier1 service depend on a
Tier2 service. Validator rejects with `TierViolation`; the patch is never committed.

## Setup

- Same as Scenario 1 — bankmod running on 8090, Claude Desktop wired up.

## Prompt to Claude Desktop

> The `ledger-core` service needs to enrich each posting with a human-readable
> statement summary. `statement-service` already computes those. Propose a graph
> patch adding `ledger-core → statement-service` over REST.

## Expected MCP traffic

1. `resources/read graph://full`
2. `resources/read graph://service/ledger-core` — Tier1.
3. `resources/read graph://service/statement-service` — Tier2.
4. `tools/call validateEvolution` with the proposed patch.
   **Expected outcome:** `accepted: false`, `errors: [{ kind: "TierViolation", ... }]`
   referencing the `ledger-core → statement-service` edge.
5. `tools/call explainInvariantViolation` with `{ kind: "TierViolation" }`.

## Expected narrative from Claude

- "Rejected: `TierViolation: ledger-core (Tier1) → statement-service (Tier2)`."
- "A higher-criticality service cannot depend on a lower-criticality one, because
  the upstream's SLO would be bounded by the weakest downstream."
- "Suggested fixes: (a) invert the dependency — make `statement-service` subscribe
  to a `ledger.posted` event from `ledger-core` (event-driven, already present in
  the baseline); (b) promote `statement-service` to Tier1; (c) extract the summary
  logic into a Tier0 utility library."

## What the architect should take away

The typed model caught a subtle but consequential SLO-chain mistake automatically.
Fix (a) is the idiomatic one — and because the event edge *already exists* in the
baseline graph, the validator has effectively pointed the architect at a
pre-approved alternative.
