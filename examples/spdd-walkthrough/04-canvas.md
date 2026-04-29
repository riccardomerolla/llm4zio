# [Feat] Standard-plan billing for fast-model usage

**ID:** BILL-1
**Story:** [02-user-story.md](02-user-story.md)
**Analysis:** [03-analysis.md](03-analysis.md)
**Status:** Approved (was Draft → InReview → Approved)
**Norms profile:** `norms-default` v3 — `<link>`
**Safeguards profile:** `safeguards-billing` v1 — `<link>`
**Last updated:** 2026-04-29T11:15:00Z by spdd-architect (agent)

---

## R — Requirements

### Problem statement

Customers on the Standard plan need their `UsageEvent`s to be billed against a 100,000-token monthly quota with overage at $0.01 / 1,000 fast-model tokens. The system must be deterministic, idempotent on retries, and never overcharge (R1, R2).

### Definition of Done

- [ ] AC1 — Standard customer with 90,000 used + 30,000 fast tokens → `{fromQuota: 10000, overage: 20000, charged: "0.20"}`.
- [ ] AC2 — Standard customer with 99,999 used + 1 fast token → `{fromQuota: 1, overage: 0, charged: "0.00"}`.
- [ ] AC3 — unknown model → HTTP 400 `UNKNOWN_MODEL`, no ledger row.

### Out of scope

- Pro plan, premium-model overage, plan changes mid-period, currencies other than USD.

---

## E — Entities

### `Plan`

- **Purpose:** the policy that tells `BillingCalculator` how to bill an event.
- **Fields:**
  - `id: PlanId — opaque string`
  - `monthlyQuotaTokens: Long — must be >= 0`
  - `priceRules: Map[ModelId, PriceRule] — non-empty`
- **Relationships:** `1..1 PlanCatalog`; referenced by `Customer.planId`.
- **Invariants:** quota >= 0; every rate in `priceRules` has `>= 0` rate.

### `PriceRule`

- **Fields:** `modelId: ModelId`, `ratePerThousand: BigDecimal — >= 0, scale 4`
- **Invariants:** rate is HALF_UP-rounded at 4 decimals (Norms profile).

### `UsageEvent` (existing — reused)

- Already idempotent via `eventId`.

### `UsageLedger`

- **Purpose:** period-scoped consumption accumulator.
- **Fields:**
  - `customerId: CustomerId`
  - `periodStart: Instant — UTC, first millisecond of the calendar month`
  - `consumed: Long — total tokens this period across models`
  - `entries: List[LedgerEntry] — one per `UsageEvent`, keyed by `eventId``
- **Invariants:** `consumed == entries.map(_.tokens).sum`. `entries` distinct by `eventId` (SG-1).

### `Charge`

- **Fields:** `fromQuota: Long`, `overage: Long`, `charged: BigDecimal`, `currency: "USD"`.

```
Customer 1..1 Plan
Customer 1..N UsageEvent
Customer 1..1 UsageLedger (per period)
Plan     1..N PriceRule
```

---

## A — Approach

### Strategy

Pure-function billing on the calculation path: `BillingCalculator.calculate(event, plan, ledger)` returns `IO[BillingError, Charge]` with no side effects. The caller (the boundary-layer endpoint) appends to the ledger only after a successful calculation. Period rollover is lazy: a helper computes `periodStart` from `Clock.instant`, mints a new ledger row if needed, and is the only place that touches the clock.

### Decisions and trade-offs

| Decision | Chosen | Rejected | Reason |
|---|---|---|---|
| Plan storage | `PlanCatalog` table | Hard-coded enum | Adding a plan must not require a deploy (story scope-out item) |
| Money type | `BigDecimal` HALF_UP @ 4dp | `Double` | R1 — silent rounding error |
| Period rollover | Lazy on first event | Cron | R3 — clock-skew risk + idempotency |
| Idempotency | Ledger key includes `eventId` | Caller dedupes | SG-1 — never bill twice on retry |

### Risks (carried from Analysis)

- **R1 financial accuracy** → mitigated by Norms (BigDecimal HALF_UP @ 4dp) + property test in `op-001` test plan.
- **R2 replay** → mitigated by ledger `eventId` key; second insert returns the original `Charge`.
- **R3 clock skew** → out of scope for this story; flagged separately.

---

## S — Structure

### Where the change lives

- New module: none (all in `billing-domain`).
- Modified files:
  - `modules/billing-domain/.../entity/BillingError.scala` — add `UnknownModel(modelId: String)`.
  - `modules/billing-domain/.../boundary/UsageController.scala` — call `BillingCalculator.calculate` after validation.
- New files:
  - `modules/billing-domain/.../entity/Plan.scala`
  - `modules/billing-domain/.../entity/PriceRule.scala`
  - `modules/billing-domain/.../entity/Charge.scala`
  - `modules/billing-domain/.../control/PlanCatalog.scala`
  - `modules/billing-domain/.../control/BillingCalculator.scala`
  - `modules/billing-domain/.../control/UsageLedger.scala`

### Dependencies

- Inbound: `UsageController` (existing).
- Outbound: `Clock` (for `periodStart`); `PlanCatalog` (for `Plan` lookup); `UsageLedgerRepository` (event-sourced, follows the canvas-domain pattern).
- New external libs: none.

### Module boundary

`billing-domain` only. Must NOT depend on `analysis-domain`, `knowledge-domain`, or any other ADE domain. Pure entity + control + boundary; no cross-domain calls.

---

## O — Operations

### `op-001: calculate`

- **Signature:** `def calculate(event: UsageEvent, plan: Plan, ledger: UsageLedger): IO[BillingError, Charge]`
- **Inputs:**
  - `event` — the incoming `UsageEvent`, validated at the boundary (`tokens >= 0`, non-empty `modelId`).
  - `plan` — the customer's active plan, fetched by the boundary from `PlanCatalog`.
  - `ledger` — the customer's current-period `UsageLedger`, fetched by the boundary.
- **Steps:**
  1. Look up `plan.priceRules.get(event.modelId)`. If absent, return `IO.fail(BillingError.UnknownModel(event.modelId))` and write nothing to the ledger.
  2. If `ledger.entries.exists(_.eventId == event.eventId)`, return the previously-recorded `Charge` for that entry (idempotency, SG-1).
  3. Compute the quota/overage split:
     - `remaining = max(0, plan.monthlyQuotaTokens - ledger.consumed)`
     - `fromQuota = min(event.tokens, remaining)`
     - `overage = event.tokens - fromQuota`
  4. Price the overage:
     - `overageThousands = BigDecimal(overage) / 1000` (scale 4, HALF_UP)
     - `charged = (overageThousands * priceRule.ratePerThousand).setScale(2, HALF_UP)`
  5. Return `Charge(fromQuota, overage, charged, currency = "USD")`.
- **Outputs:** `Charge`.
- **Errors raised:** `BillingError.UnknownModel`.
- **Tests:** test-001 (AC1 normal), test-002 (AC2 boundary), test-003 (AC3 error), test-004 (R2 replay), test-005 (R1 property: `Σ charged == Σ overage × rate`).
- **Norms:** `norms-default/money-bigdecimal-halfup-4dp`, `norms-default/error-typed-adt`.
- **Safeguards:** `safeguards-billing/SG-1-idempotency`, `safeguards-billing/SG-2-no-overcharge`.

(Step 6 — appending the ledger entry — is intentionally NOT in `op-001`; that is the boundary's job after a successful `calculate`. This keeps `op-001` pure.)

---

## N — Norms (cross-cutting standards)

Profile: **`norms-default`** v3.

Deviations from profile: none.

(Inlined for the reader's convenience — the profile actually owns these:)
- `naming/method-camel-case`
- `error-typed-adt` — typed error ADT, no `Throwable` in business logic
- `money-bigdecimal-halfup-4dp` — BigDecimal HALF_UP @ 4dp internally, scale 2 on output
- `logging-structured-info-on-op-entry-exit`
- `testing-zio-test-property-on-numeric-functions`

---

## S — Safeguards (non-negotiable invariants)

Profile: **`safeguards-billing`** v1.

Feature-specific safeguards (block dispatch at the governance gate):

- **SG-1 idempotency** — the same `UsageEvent.eventId` MUST NOT be billed twice. Negative test: `op-001` called with the same event twice returns the same `Charge`; `ledger.entries` size stays at 1.
- **SG-2 no overcharge** — for any non-empty stream of events, `Σ charged ≤ Σ (overage_tokens × ratePerThousand / 1000)` after rounding. Property test required.
- **SG-3 no negative output** — `fromQuota >= 0`, `overage >= 0`, `charged >= 0`. Enforced by types and an assertion in `op-001` step 5.

---

## Worked example (AC1, traced through `op-001`)

> **Given** Standard customer with `monthlyQuotaTokens = 100_000`, `ledger.consumed = 90_000`, `priceRules(fast-model).ratePerThousand = "0.0100"`.
> **When** `event = UsageEvent(eventId = "evt-1", tokens = 30_000, modelId = "fast-model")`.

Trace through `op-001`:

1. `plan.priceRules.get("fast-model")` → `Some(PriceRule(rate = 0.0100))`. No fail.
2. `ledger.entries.exists(_.eventId == "evt-1")` → false. Continue.
3. `remaining = max(0, 100_000 - 90_000) = 10_000`; `fromQuota = min(30_000, 10_000) = 10_000`; `overage = 30_000 - 10_000 = 20_000`.
4. `overageThousands = 20_000 / 1000 = 20.0000`; `charged = 20.0000 * 0.0100 = 0.2000` → scale-2 HALF_UP → `0.20`.
5. Return `Charge(fromQuota = 10_000, overage = 20_000, charged = "0.20", currency = "USD")`.

Matches AC1's `{fromQuota: 10000, overage: 20000, charged: "0.20"}` exactly. If the trace and the AC ever disagree, the **Canvas** is wrong, not the test.
