# Prompt-first loop example

**Scenario:** the canvas is **Approved**, code is generated, API tests are green. Then product changes their mind: **Pro overage** is now defined as **60% cheaper** than Standard, not 50%. (BILL-1 itself doesn't ship Pro, but the team uses this Canvas as the seed for BILL-3, and the Pro rule is captured in the PriceRule schema.)

This is a **behaviour change** — the billed amount differs for any Pro overage. So the loop is **prompt-first**: fix the Canvas, regenerate the affected code, re-run the API tests.

## What goes through `spdd_canvas_update_sections`

Reviewer's intent (the `deltaIntent` argument):

> Pro plan overage is 60% cheaper than Standard, not 50%. Fast-model Pro rate becomes $0.004 / 1,000 (was $0.005); premium-model Pro rate becomes $0.020 / 1,000 (was $0.025). Update the Operations section's worked example numbers and any Pro reference in the price rule comments. The PriceRule schema is unchanged.

Sections hint: `Operations,Approach`.

## What the agent produces (the call payload)

```json
{
  "canvasId": "BILL-1",
  "deltaIntent": "Pro plan overage is 60% cheaper than Standard, not 50%. Fast-model Pro rate becomes $0.004 / 1,000; premium-model Pro rate becomes $0.020 / 1,000. PriceRule schema unchanged.",
  "sectionsHint": "Operations,Approach",
  "behaviourChangeConfirmed": true,
  "updates": [
    {
      "sectionId": "Operations",
      "newContent": "<full Operations section with the worked example traced through the new rate; the `0.0050` constant becomes `0.0040`>",
      "rationale": "deltaIntent: Pro fast-model rate dropped from $0.005 to $0.004 / 1K"
    },
    {
      "sectionId": "Approach",
      "newContent": "<full Approach section with the rate-table footnote updated>",
      "rationale": "deltaIntent: rate-table footnote now reads '60% cheaper' instead of '50% cheaper'"
    }
  ],
  "staleCodePaths": [
    "modules/billing-domain/src/main/scala/billing/control/BillingCalculator.scala",
    "modules/billing-domain/src/test/scala/billing/control/BillingCalculatorSpec.scala"
  ],
  "newCanvasStatus": "InReview"
}
```

## What the gateway does

1. `spdd_canvas_update_sections` appends a `CanvasEvent.SectionUpdated` with the two updates.
2. The aggregate's `fromEvents` replay sees status was `Approved` → flips to `InReview` automatically. **The reviewer does not control this; the entity does.** This is the SPDD golden rule, enforced at the entity layer.
3. The Canvas version bumps to v2.
4. Before merging the regenerated code: the Approved gate is unsatisfied, so the governance engine blocks dispatch until a fresh `spdd_canvas_approve`.

## Code update flow

After the prompt update lands as a separate commit:

```text
1. Run spdd_render_prompt("spdd-generate", {canvas, operationId: "op-001"})
   to produce a regeneration prompt against the new Canvas v2.
2. The agent (Claude) runs the prompt itself, produces the diff with
   targeted edits to BillingCalculator and the affected tests, all
   tagged `derivedFrom: ["operations:op-001"]`.
3. Re-run scripts/spdd/test-bill-1.sh — must still be green
   (these scenarios test Standard, which is unchanged in this delta).
4. Add property tests for Pro rates if BILL-3 is in scope.
5. spdd_canvas_approve(canvasId) → status goes back to Approved.
```

## Anti-pattern caught here

If the team had updated `BillingCalculator.scala` directly (changing the constant from `0.005` to `0.004`), the Canvas would still document `0.005` and **the next reviewer would have no way to tell which version is correct**. The audit trail would be silently wrong. Prompt-first prevents that.

## Commit shape

Two commits, on the same branch, **in this order**:

1. `prompt(canvas-domain): bump BILL-1 canvas to v2 — Pro overage 60% cheaper`
   — touches only the Canvas event log + snapshot.
2. `feat(billing-domain): regenerate op-001 against canvas BILL-1 v2`
   — touches `BillingCalculator.scala` + tests; references the canvas version in the message.

A single mixed commit is an anti-pattern: the reviewer can't tell which led which.
