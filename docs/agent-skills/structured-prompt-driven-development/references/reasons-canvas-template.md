# REASONS Canvas Template

The REASONS Canvas is the central executable blueprint of an SPDD feature. It contains **everything an LLM (or a human) needs to produce the code**, and **only that**. Keep it tight: a Canvas longer than ~600 lines is a story that should be split.

File-name convention: `[ID]-[YYYYMMDDHHMM]-[Feat]-Title.md` (e.g. `BILL-001-202604291100-[Feat]-multi-plan-billing.md`).

```markdown
# [Feat] <feature title>

**ID:** <STORY-ID>
**Story:** <link/path to the User Story>
**Analysis:** <link/path to the Analysis Context>
**Status:** Draft | InReview | Approved | Stale | Superseded
**Norms profile:** <name + link>
**Safeguards profile:** <name + link>
**Last updated:** <ISO datetime, by whom>

---

## R — Requirements

### Problem statement
<one paragraph: who has the problem, why it matters now>

### Definition of Done
- [ ] AC1: Given … When … Then …
- [ ] AC2: …
- [ ] AC3: …
(Lift the GWT lines verbatim from the Story; if the Story says "30,000 tokens → $0.20 charge", that exact number stays here.)

### Out of scope
- <explicit non-goals>

---

## E — Entities

For each entity:

### `<EntityName>`
- **Purpose:** one sentence.
- **Fields:** `name: Type — meaning, invariants`.
- **Relationships:** `1..N <Other>`, `belongsTo <Other>`.
- **Invariants:** rules that must always hold (also appear in S — Safeguards).
- **Events** (if event-sourced): `Created | Updated | …`.

Diagram (optional, ASCII or mermaid):

```
Customer 1..* UsageEvent
Customer  *..1 Plan
Plan      1..* PriceRule
```

---

## A — Approach

### Strategy
<one paragraph: the chosen design pattern or algorithm; why this one>

### Decisions and trade-offs
| Decision | Chosen | Rejected | Reason |
|---|---|---|---|
| Pricing storage | DB table | hard-coded enum | Adding a third plan must not require a deploy |
| Quota counting | Total across models | Per-model | Matches Standard plan contract |

### Risks (carried from Analysis)
- **R1**: <risk> — mitigation: <how>
- **R2**: …

---

## S — Structure

### Where the change lives
- New module/package: `<path>`
- Modified files (anticipated):
  - `path/to/Foo.scala` — add `…`
  - `path/to/Bar.scala` — extend `…`
- New files:
  - `path/to/NewService.scala` — owns `<responsibility>`

### Dependencies
- Inbound: `<who calls this>`
- Outbound: `<what this calls>`
- New external libs: <none | list>

### Module boundary
<which BCE / hex layer this lives in; what it MUST NOT depend on>

---

## O — Operations

The **specific** half of the Canvas: every method that needs to exist, with its signature and step-by-step behaviour. This section drives `/spdd-generate`.

For each operation:

### `op-001: <verb-noun>`
- **Signature:** `def calculate(usage: UsageEvent, plan: Plan, ledger: UsageLedger): IO[BillingError, Charge]`
- **Inputs:** what they mean and where they come from.
- **Steps:**
  1. <imperative>
  2. <imperative>
  3. <imperative>
- **Outputs:** structure + meaning.
- **Errors raised:** `BillingError.QuotaExceeded`, `BillingError.UnknownModel`.
- **Tests:** test-id-001 (normal), test-id-002 (boundary), test-id-003 (error). See API test doc.
- **Norms:** <which N items apply>
- **Safeguards:** <which S items apply>

Number operations sequentially. The implementer works through them in order.

---

## N — Norms (cross-cutting standards)

Reference the active project NormProfile rather than copying. Inline only deviations.

- Profile: `<name>` v<version> — see `<link>`.
- Deviations from profile:
  - `<rule id>` — does not apply because …

If standalone (no profile yet):
- Naming: `<rules>`
- Logging: `<rules>` (e.g. structured logs at INFO for every Operation entry/exit, error context on raise)
- Error model: `<rules>` (e.g. typed ADT, no Throwable across boundaries)
- Observability: `<rules>` (e.g. one metric per Operation)
- Security: `<rules>` (e.g. all PII fields tagged)
- Testing: `<rules>` (e.g. unit + integration; mocks only at IO edges)

---

## S — Safeguards (non-negotiable invariants)

Reference the active project SafeguardProfile. Inline only feature-specific safeguards.

- Profile: `<name>` v<version> — see `<link>`.
- Feature-specific safeguards:
  - **SG-1 idempotency:** the same `UsageEvent.id` MUST NOT be billed twice.
  - **SG-2 financial accuracy:** money is `BigDecimal`, never `Double`; rounding is HALF_UP at 4 decimals.
  - **SG-3 perf:** `calculate` p99 < 5 ms at 1k req/s; enforced in load test.

Safeguards are the only items that **block dispatch** at the governance gate.

---

## Worked example (mandatory, lifted from the Story)

> Given a Standard customer with 100K monthly quota, 90K used, overage for fast-model is $0.01/1K.
> When 30,000 fast-model tokens are submitted,
> Then the bill shows: 10,000 within quota, 20,000 overage, $0.20 charge.

This example must round-trip through `op-001` exactly. If it doesn't, the Canvas is wrong, not the test.
