# SPDD Quality Gates — Checklist

Eight gates, in order. Each has a single binary criterion. **No advancing on a yellow.**

| # | Gate | When | Pass criterion (binary) |
|---|---|---|---|
| 1 | **Story-clarity** | After `/spdd-story` | Every story is INVEST-compliant **and** every AC has Given/When/Then with at least one numeric value. Validator: `references/story-template.md`. |
| 2 | **Intent-alignment** | After `/spdd-analysis` | Every Story AC appears in the Analysis section-4 coverage matrix with a non-empty approach element; every risk has a named mitigation. |
| 3 | **Architecture-validation** | After `/spdd-reasons-canvas` | All 7 sections present and non-empty; every Operation has a method signature and at least one referenced AC; Norms and Safeguards profiles are linked (or inlined with reason). |
| 4 | **Functional-correctness** | After `/spdd-generate` + `/spdd-api-test` | `scripts/spdd/test-<canvasId>.sh` exits 0; coverage matrix shows every Op has ≥1 normal + ≥1 error scenario. |
| 5 | **Logic-verification** | At code review, before any code edits to fix logic | If reviewer flags a behaviour issue: a `/spdd-prompt-update` commit lands **before** any code commit. The Canvas diff is reviewable in isolation. |
| 6 | **Refactoring-safety** | At code review, before any Canvas edit to document a refactor | If reviewer requests a refactor with no behaviour change: refactor commit lands first, then `/spdd-sync` commit; the API test script passes against both. |
| 7 | **Unit-test-coverage** | After unit-test generation | Every Op has ≥1 normal + ≥1 error unit test (new or pre-existing with citation); every numeric Safeguard has at least one negative test. No flakes on three consecutive runs. |
| 8 | **Regression-final** | Before merge | Re-run API tests **and** unit tests **after** all fix-up commits; both green. Canvas status is `Approved`, not `Stale`. |

## Gate-specific failure responses

### Gate 1 fails — bad Story
- AC says "should be reasonable" → reject; demand numeric.
- Story estimated at 2 weeks → reject; split. Use `/spdd-story` again on the largest chunk.

### Gate 2 fails — Analysis missing pieces
- AC has no approach element → return to design discussion; do not start the Canvas.
- Risk listed without mitigation → either mitigate or accept-and-document; never silent.

### Gate 3 fails — Canvas incomplete
- Operation without signature → it is not yet specific enough to generate code.
- Norms = "use sensible defaults" → reject; reference a real NormProfile or write the rules.
- Safeguard inferred from norms → no; safeguards are non-negotiable, must be named.

### Gate 4 fails — code or tests broken
- Test script exits non-zero → fix code (this is the loop most teams know).
- Coverage matrix has an empty row → add the missing scenario before merging.

### Gate 5 fails — wrong loop chosen
- Code commit changed behaviour without a preceding Canvas commit → revert; redo as prompt-first.
- Canvas commit + code commit landed together → next time, separate; this time, document the merge in the PR description.

### Gate 6 fails — sync skipped after refactor
- Refactor merged without `/spdd-sync` → Canvas is stale; flag it, do the sync as a follow-up commit on the same branch.

### Gate 7 fails — test gaps
- Op without an error test → write it now; do not merge.
- Safeguard without negative test → write it now; this is the gate's whole point.

### Gate 8 fails — regression
- Tests passed before fix-up commits, fail now → bisect, fix, re-run gates 5-7 as appropriate.

## Roles (default; flexible per team)

| Gate | Who runs it | Who can override |
|---|---|---|
| 1, 2 | PO/BA + dev | PO |
| 3 | Tech lead + dev | Tech lead |
| 4, 7, 8 | CI + dev | None — gates are mechanical |
| 5, 6 | Reviewer | Reviewer |

Mechanical gates (4, 7, 8) are **never** overridden. If the script fails, the change does not merge.

## Why so many gates?

Each gate catches a class of error that the previous gate cannot:

- 1 catches **vague stories** (cheapest to fix here).
- 2 catches **missing intent** (cheap; just rewrite).
- 3 catches **missing design** (cheap; rewrite Canvas).
- 4 catches **wrong code** (medium; rewrite code or Canvas).
- 5 catches **wrong loop choice** (cheap if caught now, expensive in production).
- 6 catches **silent Canvas drift** (audit-trail damage if missed).
- 7 catches **untested invariants** (the bugs that wake people at 3am).
- 8 catches **rebase-induced regressions** (the ones that ship Friday afternoon).

Skip a gate, you accept that class of bug. Decide consciously.
