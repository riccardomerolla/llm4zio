# Enhancement Idea — Multi-plan token billing

**Submitted by:** Product
**Date:** 2026-04-29
**Target service:** `token-billing` (existing internal service that bills customers per-token usage of fast-model and premium-model)

## What we want

We want to introduce **plans** so customers get a monthly token quota plus tiered overage pricing. Two plans to start:

- **Standard plan** — 100,000-token monthly quota, total across both models. Overage rates: $0.01 / 1,000 fast-model tokens; $0.05 / 1,000 premium-model tokens.
- **Pro plan** — 500,000-token monthly quota, total across both models. Overage rates 50% cheaper than Standard ($0.005 / 1,000 fast; $0.025 / 1,000 premium).

Existing customers default to Standard until they explicitly upgrade.

## Why now

Sales has three Pro-curious customers waiting on tiered pricing. Without a plan layer, Finance is hand-running monthly reconciliations.

## What "done" feels like

A customer hits `POST /usage` with their usage event; the response shows `fromQuota`, `overage`, `charged` (in USD, two decimals). Existing reports continue to work. We can add a third plan later without a deploy.

## What this enhancement does NOT include

- Annual / committed-use plans
- Per-model quotas (the quota is total across models)
- Self-service plan upgrades (Sales does that for now)
- Currency other than USD

## Open questions for the engineers

1. When does the quota reset — calendar month, billing anchor date, or rolling 30-day?
2. What happens on a mid-period plan upgrade or downgrade — pro-rate, reset, or carry over?
3. Are the Pro overage rates *defined* as 50% of Standard, or are they independently configured (and just happen to be half today)?

These shape the data model. The engineers should confirm them before the canvas is drafted.
