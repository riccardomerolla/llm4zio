# Scenario 3 — Valid Event-Driven Edge Introduction

**Goal:** Architect proposes introducing a new event-driven edge that *does* satisfy
all invariants. Validator accepts; `proposeService` commits; the updated graph is
pushed as a resource-update notification.

## Setup

- Same as Scenario 1.

## Prompt to Claude Desktop

> I want to add a `payments.cleared` event from `payments-engine` to `audit-log`
> so audit can record cleared-payment confirmations asynchronously. Use the
> `introduceEvent` prompt as a starting template.

## Expected MCP traffic

1. `prompts/get introduceEvent` with `{ fromService: "payments-engine", toService:
   "audit-log", topic: "payments.cleared", reason: "audit needs async record of
   cleared payments" }`.
2. `resources/read graph://service/payments-engine` and `graph://service/audit-log`.
3. `tools/call validateEvolution` with a patch adding the event edge
   (`Protocol.Event("payments.cleared")`, `Consistency.Eventual`,
   `Ordering.PartialOrder`).
   **Expected outcome:** `accepted: true`, `errors: []`, `previewJson` populated.
4. `tools/call proposeService` with the same patch.
   **Expected outcome:** `committed: true`, `committedJson` populated.
5. **Background:** subscribed MCP clients receive
   `notifications/resources/updated { uri: "graph://full" }`.
6. `tools/call renderDiagram { scope: "full", format: "mermaid" }` — the rendered
   diagram now shows the new edge in the Tier3-subgraph section with a `--event-->`
   arrow style.

## Expected narrative from Claude

- "All invariants green. `payments-engine → audit-log` over `payments.cleared`,
  eventual consistency, partial ordering."
- "Committed. Here is the updated Mermaid rendering." (inlines the diagram.)

## What the architect should take away

The validator is not merely a rejector — it allows legitimate evolutions straight
through, records the commit, and immediately rerenders the artifact the MRM
committee will see. The round-trip from *proposal* to *approved living blueprint*
takes seconds.
