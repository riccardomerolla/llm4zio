# User Story Template — INVEST + Given/When/Then

File-name: `[ID]-[YYYYMMDDHHMM]-[User-story-N]-Title.md`.

A story passes the INVEST check (**I**ndependent, **N**egotiable, **V**aluable, **E**stimable, **S**mall, **T**estable) **and** every acceptance criterion is in Given/When/Then with at least one **numeric** example.

```markdown
# [User-story-1] <Short title>

**ID:** <STORY-ID>
**Estimate:** ≤ 5 person-days (if larger, split)
**Owner:** <PO/BA/dev>
**Status:** Draft | Refined | Approved

## Background
<1-3 sentences: where this fits in the product, why now>

## Business value
<1-2 sentences: what changes for the customer / the business when this is done>

## Scope (in)
- <bullet>
- <bullet>

## Scope (out)
- <bullet — explicit non-goals>
- <bullet>

## Acceptance criteria

Each AC is a Given/When/Then with concrete numbers (no "some", no "a few").

### AC1 — <name>
> **Given** a Standard customer with 100K monthly quota, 90K already used, fast-model overage = $0.01/1K
> **When** 30,000 fast-model tokens are submitted in a single event
> **Then** the bill shows 10,000 from quota, 20,000 overage, $0.20 total charge.

### AC2 — boundary: exact quota
> **Given** a Standard customer with 100K monthly quota, 99,999 used
> **When** 1 fast-model token is submitted
> **Then** the bill shows 1 from quota, 0 overage, $0.00 charge.

### AC3 — error: unknown model
> **Given** a Standard customer with quota remaining
> **When** a token event for `unknown-model` is submitted
> **Then** the system rejects with code `UNKNOWN_MODEL` and records no usage.

(At minimum: 1 normal, 1 boundary, 1 error per story.)

## Open questions
- <question 1>
- <question 2>

## Done means
- [ ] All AC pass `/spdd-api-test` script.
- [ ] Code review approved against the linked REASONS Canvas.
- [ ] Canvas in `Approved` state, no `Stale` downstream code.
- [ ] Norms & Safeguards profiles satisfied.
```

## INVEST checklist

| Letter | Check | If not met |
|---|---|---|
| Independent | Can ship without other stories | Refactor: extract dependency |
| Negotiable | Scope can move during refinement | Detail too prescriptive — pull back |
| Valuable | Business value stated | Rewrite "Business value" |
| Estimable | Team can size it | Too vague — add detail or research spike |
| Small | ≤ 5 days | Split |
| Testable | Every AC has GWT + numbers | Rewrite AC; reject vague verbs ("handle", "support") |

## Common AC mistakes

- "**handle** invalid input" → not testable. Replace with `When invalid input X arrives, Then HTTP 400 with code Y`.
- "Reasonable performance" → not testable. Replace with `p99 latency < N ms at M req/s`.
- "User can see usage" → no number. Replace with `Then the response body contains `{quotaRemaining: 70000, periodEnd: "2026-05-01T00:00Z"}`.`
