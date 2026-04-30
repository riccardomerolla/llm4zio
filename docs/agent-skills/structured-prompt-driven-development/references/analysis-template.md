# Analysis Context Template

The Analysis Context is the bridge between a User Story and the REASONS Canvas. It locks **intent** before any design happens. It is short — usually 1-2 pages — and answers: *what does the codebase already give us, what's new, what's risky, and how does the story map onto reality?*

File-name: `[ID]-[YYYYMMDDHHMM]-[Analysis]-Title.md`.

```markdown
# [Analysis] <feature title>

**ID:** <STORY-ID>
**Story:** <link/path>
**Status:** Draft | Reviewed | Approved
**Author / agent:** <who>
**Last updated:** <ISO datetime>

---

## 1. Domain concepts

### Existing (already in the codebase)
| Concept | Where it lives | Notes |
|---|---|---|
| `Customer` | `modules/billing-domain/.../Customer.scala` | Has `planId` since v0.4 |
| `UsageEvent` | `modules/billing-domain/.../UsageEvent.scala` | Already idempotent via `eventId` |

### New (introduced by this story)
| Concept | Why new | Lives in |
|---|---|---|
| `Plan` | First time we model tiered plans | `billing-domain/entity` |
| `PriceRule` | Per-model overage rates | `billing-domain/entity` |
| `UsageLedger` | Period-scoped accumulator | `billing-domain/control` |

### Glossary deltas
- "**Plan**" replaces the previous string-typed `customer.tier` (deprecated).
- "**Quota**" is a count of *total* tokens across models in a billing period.

---

## 2. Strategic approach

### One-paragraph strategy
<e.g. "Add a Plan + PriceRule pair as configuration-as-data; route every UsageEvent through a pure BillingCalculator that consults the active Plan; persist accumulation in a UsageLedger keyed by (customerId, periodStart). Period rollover is lazy on first event of a new month.">

### Why this and not alternatives
| Alternative | Rejected because |
|---|---|
| Hard-coded enum of plans | Adding a third plan requires deploy; product wants self-service in next quarter |
| Eager monthly cron rollover | Cron + clock-skew risk; lazy rollover is testable and idempotent |

### Mapping each AC to an approach element
| AC | Approach element |
|---|---|
| AC1 (within-quota + overage) | `BillingCalculator.calculate` step 3-4 |
| AC2 (exact-quota boundary) | Same; covered by `op-001` test-002 |
| AC3 (unknown model) | `PriceRule` lookup raises `UnknownModel` |

If any AC has no element: the approach is incomplete — go back to design before writing the Canvas.

---

## 3. Risks, gaps, edge cases

### Risks
- **R1 — financial:** rounding error compounding across millions of events. Mitigation: BigDecimal, HALF_UP at 4dp, property test with random splits summing back to total.
- **R2 — replay:** retried `UsageEvent` could double-bill. Mitigation: ledger keyed by `eventId`, idempotent insert.
- **R3 — clock skew at period boundary:** event timestamped `2026-04-30T23:59:59.999Z` could land in the wrong period. Mitigation: trust server-side receive time; reject events with `now - eventTime > 5s` clock drift.

### Edge cases (each becomes a test scenario)
- 0-token event (skip silently? bill 0?).
- Plan change mid-period (which rate applies to existing usage?).
- Negative usage (impossible per types? assert and fail loud).
- Period exactly at the boundary millisecond.

### Gaps in the story
- Story doesn't say what happens on plan **downgrade** mid-period — flagged for PO.
- Story doesn't specify currency — assumed USD; flagged.

---

## 4. Acceptance-criteria coverage

| AC | Approach element | Risks addressed | Edge cases covered | Status |
|---|---|---|---|---|
| AC1 | `op-001` step 3-4 | R1 | normal | ✅ |
| AC2 | `op-001` step 3 | — | exact-quota boundary | ✅ |
| AC3 | `op-001` step 1 | — | unknown model | ✅ |

If any row has empty cells, the analysis is not yet complete.

---

## 5. Open questions for the human

1. <question, with proposed default>
2. <question>

These must be resolved before `/spdd-reasons-canvas` runs.
```

## Quality bar

The analysis is **done** when:
- Every story AC appears in section 4 with a non-empty approach element.
- Risks are concrete (each has a mitigation, not just a name).
- "New vs existing" concepts is decisive — no maybes.
- Open questions are flagged, not silently assumed.

The analysis is **NOT** done when it merely restates the story in different words. The deliverable is *understanding*, not *summary*.
