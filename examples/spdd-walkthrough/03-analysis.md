# [Analysis] Standard-plan billing for fast-model usage

**ID:** BILL-1
**Story:** [02-user-story.md](02-user-story.md)
**Status:** Approved
**Author:** spdd-architect (agent)
**Last updated:** 2026-04-29T10:30:00Z

---

## 1. Domain concepts

### Existing (already in the codebase)

| Concept | Where it lives | Notes |
|---|---|---|
| `Customer` | `modules/billing-domain/.../entity/Customer.scala` | Has a `planId: Option[PlanId]` field since v0.4; defaults `None` |
| `UsageEvent` | `modules/billing-domain/.../entity/UsageEvent.scala` | Already idempotent via `eventId: String` |
| `BillingError` | `modules/billing-domain/.../entity/BillingError.scala` | Sealed enum; we add `UnknownModel` |

### New (introduced by this story)

| Concept | Why new | Lives in |
|---|---|---|
| `Plan` | First time we model tiered plans | `billing-domain/entity` |
| `PriceRule` | Per-model overage rate | `billing-domain/entity` |
| `UsageLedger` | Period-scoped accumulator keyed by `(customerId, periodStart)` | `billing-domain/control` |
| `Charge` | Result type from `BillingCalculator.calculate` | `billing-domain/entity` |

### Glossary deltas

- **Plan** replaces the previous string-typed `customer.tier` (deprecated, will be removed in BILL-3).
- **Quota** is a count of *total* tokens across models in a billing period — confirmed with Product, locked in section 5 of this analysis.

---

## 2. Strategic approach

### One-paragraph strategy

Add `Plan` and `PriceRule` as configuration-as-data (a `PlanCatalog` table, not a hard-coded enum); route every `UsageEvent` through a pure `BillingCalculator.calculate` that consults the active plan and the customer's current `UsageLedger`; persist accumulation in `UsageLedger`, keyed by `(customerId, periodStart)`. Period rollover is *lazy*: on the first event whose timestamp is past the active period, we mint a new `UsageLedger` row.

### Why this and not alternatives

| Alternative | Rejected because |
|---|---|
| Hard-coded `enum Plan` with quota/rates as Scala constants | Adding a third plan would need a deploy; Product wants self-service in the next quarter |
| Eager monthly cron rollover | Cron + clock-skew risk; lazy rollover is testable and idempotent |
| Per-model quotas | Would change the meaning of "quota" mid-flight; out of scope per Product confirmation |

### Mapping each AC to an approach element

| AC | Approach element |
|---|---|
| AC1 (within-quota + overage) | `BillingCalculator.calculate` step 3 (quota/overage split) and step 4 (price the overage) |
| AC2 (exact-quota boundary) | Same `op-001`; covered by test-002 |
| AC3 (unknown model) | `PriceRule` lookup raises `BillingError.UnknownModel`; no ledger row written |

If any AC has no element: the approach is incomplete — go back to design before writing the Canvas.

---

## 3. Risks, gaps, edge cases

### Risks

- **R1 — financial accuracy.** Floating-point money compounds across millions of events. Mitigation: `BigDecimal` with `HALF_UP` rounding at 4 decimal places of computation, surfaced as 2 decimals on output. Property test: `Σ charged == Σ (overage_tokens × rate)`.
- **R2 — replay.** A retried `UsageEvent` could double-bill. Mitigation: ledger keyed by `eventId`, idempotent insert; second insert with the same id returns the original `Charge`.
- **R3 — clock skew at period boundary.** An event timestamped `2026-04-30T23:59:59.999Z` could land in the wrong period if the gateway clock drifts. Mitigation: trust server-side receive time (`Clock.instant`); reject events with `now - eventTime > 5s` skew with `BillingError.StaleEvent` — but that is a separate story (out of scope here).

### Edge cases (each becomes a test scenario)

- 0-token event — bill 0, write a zero ledger row for traceability (handled by op-001 step 1; not a separate AC).
- Negative usage — impossible per types (`tokens: Long` with `validate >= 0` at the boundary); assert and fail loud if it leaks through.
- Period exactly at the boundary millisecond — covered by R3.

### Gaps in the story

- The story doesn't say what happens on plan **downgrade** mid-period — flagged for Product. *Not blocking this story* (story is upgrade-only and Standard for now; downgrade is a follow-up).

---

## 4. Acceptance-criteria coverage

| AC | Approach element | Risks addressed | Edge cases covered | Status |
|---|---|---|---|---|
| AC1 | `op-001` step 3 + step 4 | R1 | normal | ✅ |
| AC2 | `op-001` step 3 | — | exact-quota boundary | ✅ |
| AC3 | `op-001` step 1 | — | unknown model in price map | ✅ |

No empty cells: analysis is complete.

---

## 5. Open questions for the human

All three open questions in the enhancement idea were resolved before this analysis was approved:

1. **Quota reset window** → calendar month in UTC. Locked.
2. **Mid-period plan change** → out of scope; story is single-plan single-period.
3. **Pro overage rates** → independently configured. Not relevant to this story; relevant to BILL-3.

No remaining blockers. Proceed to `/spdd-reasons-canvas`.
