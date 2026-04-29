# [User-story-1] Standard-plan billing for fast-model usage

**ID:** BILL-1
**Estimate:** 3 person-days
**Owner:** dev
**Status:** Approved

## Background

The token-billing service currently bills every event at a flat rate. We are introducing a **plan** layer so customers get a monthly quota plus tiered overage pricing. This story covers the Standard plan, fast-model only — the smallest slice that proves the design end to end.

## Business value

Lets us turn off Finance's hand-run monthly reconciliations and clears the path for the three Pro-curious customers Sales is waiting on. Once the Standard / fast-model slice ships, Pro and premium-model are mechanical follow-ups (data only, no engine changes).

## Scope (in)

- A `Plan` value with a monthly quota and a per-model rate map.
- A `Standard` plan: 100,000-token monthly quota, fast-model overage at $0.01 / 1,000 tokens.
- Routing every `UsageEvent` for a Standard customer through the new pricing function.
- A response shape exposing `fromQuota`, `overage`, `charged`.

## Scope (out)

- Premium-model overage (separate story, BILL-2).
- Pro plan (BILL-3 + BILL-4).
- Plan-change mid-period semantics — flagged for follow-up; this story bills against the customer's current plan only.
- Currencies other than USD.
- Per-model quotas — the quota is total across models.

## Acceptance criteria

### AC1 — within-quota plus overage on a single event (normal)

> **Given** a Standard customer with a 100,000-token monthly quota, 90,000 already used in the current period, fast-model overage = $0.01 / 1,000 tokens
> **When** the system receives a `UsageEvent` for 30,000 fast-model tokens with `eventId=evt-1`
> **Then** the response is HTTP 200 and the body contains `{"fromQuota": 10000, "overage": 20000, "charged": "0.20", "currency": "USD"}`

### AC2 — exact-quota single-token boundary

> **Given** a Standard customer with a 100,000-token monthly quota, 99,999 already used
> **When** the system receives a `UsageEvent` for 1 fast-model token with `eventId=evt-2`
> **Then** the response is HTTP 200 and the body contains `{"fromQuota": 1, "overage": 0, "charged": "0.00", "currency": "USD"}`

### AC3 — unknown model rejected (error)

> **Given** a Standard customer with quota remaining
> **When** the system receives a `UsageEvent` for a model not in the plan's rate map (e.g. `weird-model`)
> **Then** the response is HTTP 400 with body `{"error": {"code": "UNKNOWN_MODEL", "model": "weird-model"}}` and **no** ledger row is created for that event id

## Open questions (resolved)

- **Quota reset window:** calendar month in UTC. Confirmed with Product.
- **Quota counting:** total tokens across all models, billed per-event in event order.
- **Pro overage rates:** independently configured (just happen to be half of Standard today). This story doesn't touch Pro.

## Done means

- All three AC pass `scripts/spdd/test-<canvasId>.sh`.
- Code review approved against the linked REASONS Canvas.
- Canvas in `Approved` state, no `Stale` downstream code.
- Norms profile `norms-default` and Safeguards profile `safeguards-billing` are satisfied.
