# Code-first loop example

**Scenario:** the canvas is **Approved** at v2 (after the Pro-rate prompt-first round). All API tests are green. A reviewer comments on the regenerated `BillingCalculator.scala`:

> The two-step `min(event.tokens, remaining)` then `event.tokens - fromQuota` would read better as a tiny private helper, and the inline `BigDecimal(overage) / 1000` could move into a `PriceRule.priceFor(overage: Long): BigDecimal` extension. Identical behaviour, cleaner shape.

This is a **pure refactor** — no observable behaviour change. The loop is **code-first**: refactor the code, then `spdd_canvas_update_sections` documents the new shape so the Canvas doesn't drift. **API tests must stay green throughout.**

## Step 1 — refactor the code

The dev rewrites the affected files:

```diff
- val remaining = math.max(0L, plan.monthlyQuotaTokens - ledger.consumed)
- val fromQuota = math.min(event.tokens, remaining)
- val overage   = event.tokens - fromQuota
+ val (fromQuota, overage) = splitQuota(event.tokens, plan.monthlyQuotaTokens, ledger.consumed)
  ...
- val overageThousands = BigDecimal(overage) / 1000
- val charged = (overageThousands * priceRule.ratePerThousand).setScale(2, HALF_UP)
+ val charged = priceRule.priceFor(overage)
```

`splitQuota` is a private helper at the bottom of `BillingCalculator`. `priceFor` is an extension method on `PriceRule`. Both are pure.

## Step 2 — re-run the API tests **before** syncing

```bash
sbt billingDomain/test
sh scripts/spdd/test-bill-1.sh
```

If anything fails: the refactor *did* change behaviour. STOP — this is a prompt-first situation in disguise. Revert the refactor, redo it via `06-prompt-update-example.md`-style prompt-first.

If both pass: continue to step 3.

## Step 3 — sync the Canvas

The agent calls `spdd_canvas_update_sections` with the *minimum* edits needed to make the Canvas describe the new code:

```json
{
  "canvasId": "BILL-1",
  "rationale": "code-first sync: extract splitQuota helper + PriceRule.priceFor extension; behaviour unchanged",
  "updates": [
    {
      "sectionId": "Operations",
      "newContent": "<Operations section with steps 3 and 4 reworded to mention splitQuota and priceFor; the worked example numbers are unchanged because the behaviour is unchanged>",
      "rationale": "Step 3 now delegates to splitQuota; step 4 now delegates to PriceRule.priceFor"
    },
    {
      "sectionId": "Structure",
      "newContent": "<Structure section with one extra bullet under New files: PriceRule extensions; helper splitQuota colocated in BillingCalculator>",
      "rationale": "new private helper splitQuota; new extension on PriceRule"
    }
  ]
}
```

The Requirements, Entities, Approach, Norms, and Safeguards sections are untouched — none of them changed.

## What the gateway does

1. `spdd_canvas_update_sections` appends a `CanvasEvent.SectionUpdated`.
2. Status was `Approved` → flips to `InReview` (the entity rule again — even a code-first sync triggers it). The Canvas now goes through a quick re-approval review focused on "does the Canvas accurately describe the new code?".
3. Version bumps to v3.
4. **Re-run the API test script after the sync lands**, not just after the refactor. Belt-and-braces: catches the rare case where the sync itself revealed the refactor had a subtle behaviour delta.

## Critical rule

`spdd_canvas_update_sections` is **the same MCP tool** for both loops — it doesn't know which loop you're in. The discipline is on the caller: only edit Operations *semantics* (numbers, error cases, step ordering that observable callers see) in prompt-first; only edit Operations *prose* (helper names, structural references) in code-first. Operating semantics are intent and are protected by the prompt-first rule.

If you ever find yourself updating Operations *and* the API tests then start failing, you were doing prompt-first work disguised as a refactor. Revert and go through prompt-first properly.

## Commit shape

Two commits, in this order:

1. `refactor(billing-domain): extract splitQuota helper and PriceRule.priceFor extension`
   — code only. API tests must be green at this commit.
2. `prompt(canvas-domain): sync BILL-1 canvas to v3 — describe new helpers (no behaviour change)`
   — touches only the Canvas event log + snapshot.

The order matters: code first, then sync. A reviewer reading commit 1 alone sees a clean refactor; commit 2 makes the Canvas catch up.

## Anti-pattern caught here

A team that does code-first when behaviour *did* change ends up with a Canvas that confidently lies about the system. Worse, the lie compounds: the next feature seeded from this Canvas inherits the wrong intent. The "API tests stay green" check at step 2 is the only thing that distinguishes "refactor + sync" from "intent change disguised as a refactor". Don't skip it.
