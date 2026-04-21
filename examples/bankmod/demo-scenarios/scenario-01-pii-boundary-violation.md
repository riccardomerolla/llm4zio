# Scenario 1 — PII Boundary Violation

**Goal:** Architect proposes routing PII-tagged data from `customer-profile` to a service
outside the allowed-PII set. The validator rejects with `PiiBoundaryCrossed`; the
`explainInvariantViolation` tool surfaces a canned fix.

## Setup

- `bankmod-app.jar` running on port 8090, seeded with the 8-service fixture.
- Claude Desktop configured with the bankmod MCP endpoint (`http://localhost:8090/mcp`).

## Prompt to Claude Desktop

> I want to start pushing PII from `customer-profile` into `notification-bus` so that
> outbound SMS/email jobs can personalise copy. Propose a graph patch that adds a
> direct REST edge `customer-profile → notification-bus` and validate it.

## Expected MCP traffic

1. `resources/read graph://full` — Claude reads the current graph.
2. `resources/read graph://service/customer-profile` — focused service context.
3. `resources/read graph://invariant/PiiBoundaryCrossed` — invariant catalog entry.
4. `tools/call validateEvolution` with a `patchJson` that adds a `Rest` edge from
   `customer-profile (pii)` → `notification-bus`.
   **Expected outcome:** `accepted: false`, `errors: [{ kind: "PiiBoundaryCrossed", ... }]`.
5. `tools/call explainInvariantViolation` with `{ kind: "PiiBoundaryCrossed" }` —
   Claude surfaces the explanation and fix suggestions to the architect.

## Expected narrative from Claude

- "The patch was rejected by `PiiBoundaryCrossed`. The invariant forbids routing
  PII-tagged data to services outside the `pii-allowed` set."
- "Suggested fixes: (a) send a *non-PII projection* of the event (e.g. a customer
  token) via `notification-bus` and let it hydrate from an allowed PII service;
  (b) route through `notification-worker` which is in the allowed set; (c) declare
  `notification-bus` in the allowed set (requires governance review)."

## What the architect should take away

The validator made an MRM-relevant rejection *before* any code was written, and the
reason string is precise enough to hand to a change-control reviewer.
