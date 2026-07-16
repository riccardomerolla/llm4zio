# 20. Stage-result resume (orca ADR 0018) — study

- Status: Proposed (study — decision deferred; no implementation committed)

## Context

llm4zio's resume model today is **plan-task granularity**: `implementTaskLoop` checks tasks off in
the Markdown plan (`PlanStore`, ADR 0007) after each one, and `PlanStore.recoverOrCreate` re-reads
the plan on re-entry. Everything *outside* the task loop — branch checkout, `Chat.start`, push, PR
creation — re-runs on every re-entry (`withUsageLimitRetry`, `AutoResume`) and survives only by
hand-made idempotence (`createPr` is find-or-create; `checkoutOrCreate` tolerates an existing
branch). `stage(name)` is pure event decoration: it records nothing.

orca 0.0.16 (its ADR 0018, PR #22) **replaced** its plan-file resume — the very pattern llm4zio's
`PlanStore` inherits — with a stage-bound runtime:

- A `flow(...)` run binds up-front to one feature branch (pluggable, injection-safe
  `BranchNamingStrategy` with a git-ref-safe `slug`) and one progress log
  (`.orca/progress-<prompt-hash>.json`, committed to the branch via a single-path `git add -f`).
- Each `stage(name)` block records one JSON-serialisable result and makes one commit. A re-run
  skips any stage whose stored result decodes to the call-site type, and re-runs on decode failure
  (fail-safe over misattribution). Stage ids are hierarchical `name#occurrenceIndex` paths, making
  nested-stage misattribution structurally impossible.
- Capabilities split four ways (`FlowContext` / `FlowControl` / `InStage` / `WorkspaceWrite`), with
  Scala 3.8.4 capture/separation checking enforcing at a `CheckedPar` fork funnel that a fork may
  capture `InStage` (parallel reviewers need it) but never `WorkspaceWrite`/`FlowControl`.
- `FlowSession` as the durable-session door (probe → seed → run → persist, per-backend existence
  probes, re-seed as guaranteed fallback); `SurfacedFlowFailure` guaranteeing every failure reaches
  the user exactly once; idempotent externals (`createPr` reuse, `upsertComment(marker)`).

Pitfalls orca's ADR records: the committed progress log rides in open-PR pushes (a confidentiality
surface — never return secrets as stage results); resume is decode-safe, not meaning-safe; resume
restores files but **not agent context** (each seed must stand alone); stages must not run
concurrently; push must live in a later stage than the commit that produced the code.

## The gap this would address in llm4zio

1. Re-entry granularity: a usage-limit or transient re-entry repeats every completed step outside
   `implementTaskLoop` — wasted turns/quota, and correctness rests on each step happening to be
   idempotent rather than on the runtime.
2. Nothing ties a run to its branch: a resumed flow trusts the script to re-derive the same
   `plan.epicId` and re-checkout.
3. Coder `Chat` context is lost on re-entry (same limitation orca accepted: seeds must stand alone).

## Options

**A. Incremental: `step` + progress log (library-shaped).** Add an opt-in
`step[A: JsonCodec](name)(effect)` that behaves like `stage` plus: record the result under a
hierarchical `name#occurrence` id in `.llm4zio/progress-<prompt-hash>.json`, and skip (returning
the decoded result) when present. `stage` stays pure decoration; `PlanStore` stays the plan
artifact; `implementTaskLoop` unchanged (its plan file already provides task-level skipping).
Sub-decision: commit the progress log (orca's choice — resume survives fresh clones/worktrees, but
secrets ride in PR pushes) vs keep it local-only (safe, simpler; resume only on the same checkout).

**B. A + flow-level branch binding.** `flow(args, branch = ...)` (or a `BranchNaming` strategy)
creates/rebinds the feature branch before the body runs, so scripts drop their `stage("branch")`
lines and a resumed run cannot land on the wrong branch. Moves llm4zio one step toward
runtime-owns-control-flow; conflicts mildly with the bare-names, top-to-bottom script ethos and
ADR 0009's "runtime owns git" is currently about *seeding*, not control flow.

**C. Full stage-bound runtime.** Follow orca: progress log becomes *the* resume, one commit per
step, `PlanStore` reduced to a planning artifact or deprecated (revisits/supersedes ADR 0007's
"resumable plans persist as Markdown"). Orca deleted plans outright; llm4zio's plan file is a
documented user-facing feature, so this is an identity decision, not just a mechanism swap.

**D. Capability split.** orca's guarantee (a fork cannot capture the workspace-write capability)
comes from experimental capture/separation checking on direct-style code. llm4zio is ZIO: effects
are values, and coarse gating is already expressible without new machinery (evidence parameters or
`R`-channel marker services, e.g. `ZIO[WorkspaceWrite, …]`). What ZIO does **not** give cheaply is
fork-separation — `ZIO#fork` inherits the environment, so an `R`-marker does not stop a forked
reviewer from calling a write effect. Matching orca's guarantee would require capture checking
(experimental on our Scala 3.8.3, forcing experimental flags on every user of the library) or a
bespoke fork funnel. The payoff is also smaller here: llm4zio's parallel fan-outs
(`reviewAndFixLoop`) call an injected read-only `LlmService`, not ambient workspace tools.

## Recommendation (non-binding)

Start with **A** with a **local-only (uncommitted) progress log** — it removes the re-entry waste
without touching llm4zio's identity (plan files, thin `stage`, no framework runtime), and it is
reversible. Adopt **B** only if resumed-branch drift is observed in practice. Treat **C** as a
separate identity decision to revisit after A has run in anger (evidence: how often plan-file and
progress-log disagree, whether users edit plans by hand in practice). Skip **D** until capture
checking is non-experimental; revisit when the compiler is bumped past 3.8.x.

## Consequences of deferring

The known costs remain: re-entries repeat non-loop work (bounded by idempotence and quota, not by
the runtime), and correctness of resumed flows continues to rest on convention. No API surface is
added or broken while the design space stays open; orca remains a live reference implementation to
port from once evidence justifies a slice.
