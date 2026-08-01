# Bank pilot playbook

The operator's guide to running a modernization pilot on your own estate with
llm4zio — written for the platform engineer who must convince their risk
committee. A pilot is one wave of ~10–20 programs through all six phases, ending
with an evidence bundle, not a demo.

## What you need

- **The legacy repo**: your COBOL/JCL (or JSP, or ACE) sources in git. Nothing
  leaves it — extraction runs rooted there.
- **An empty target repo** and, ideally, your own scaffold (your golden-path
  Spring Boot/Kafka starter). The shipped scaffolds are stand-ins.
- **A pack**: copy the closest shipped pack (`examples/packs/…`) and rewrite it
  in your estate's vocabulary — prompts, judge rubrics, coverage and survey
  regexes, gates. The pack is where your know-how accumulates; version it.
- **Seats**: a reasoning/judge model and a CLI coder (`claude`, `codex`,
  `gemini`). JDK 21+, scala-cli, maven, git.

## Residency: the wall is the boundary

Only two phases ever read legacy source: **extract** and its **gate judge**.
If your source is licensed or classified, point those seats at your on-prem
OpenAI-compatible endpoint (vLLM on your GPU infra — the `LmStudio`/`OpenAI`
connectors speak to any such endpoint) and let the rest of the pipeline use
cloud coders: past the human approval, the enforced wall (`flow.Wall`)
guarantees there is no source left to leak — implement/verify/review refuse to
start if any file matching the pack's `sources:` regex is present in the
target.

## Two ways to run the pipeline

Everything below shows the `.sc` scripts (clone the repo, `scala-cli run …`) —
the authoring surface, best when you are customizing flows. The **operator
surface** is the published product, no repo clone and no Scala:

```bash
cs launch io.github.riccardomerolla:llm4zio-modernize_3:4.3.0 -- <phase> -- --repo <dir>
```

or the OCI image for air-gapped/CI use
(`ghcr.io/riccardomerolla/llm4zio-modernize`). Both read the same env vars,
plus `./modernize.conf` (KEY=value) for whatever env leaves unset — keep seats
and endpoints in the conf, estate knowledge in the pack.

## The six phases

```bash
export LLM4ZIO_PACK=packs/your-pack
export LLM4ZIO_RUN_LABEL=pilot-wave-1     # correlates every phase in the cost ledger
export LLM4ZIO_APPROVER="your name"       # recorded in the provenance manifest

# 0) survey: inventory + dependency graph + triage + wave plan (halts for approval)
scala-cli run modernize-survey.sc -- --repo ~/estates/your-legacy

#    review docs/modernization/wave-plan.md, flip "- [x] Approved"

# 1) extract wave 1 into a judged spec pack (halts unapproved)
LLM4ZIO_WAVE=wave-1 scala-cli run modernize-extract.sc -- --repo ~/estates/your-legacy

#    review docs/modernization/README.md, flip "- [x] Approved"

# 2) seed the target (deterministic; writes the provenance manifest)
LLM4ZIO_LEGACY_REPO=~/estates/your-legacy \
  scala-cli run modernize-seed.sc -- --repo ~/services/your-target

# 3) implement, gated until green (RED-first acceptance tests, pack gates, judge)
scala-cli run modernize-implement.sc -- --repo ~/services/your-target

# 4) prove equivalence (generated-first vectors; halts non-green)
scala-cli run modernize-verify.sc -- --repo ~/services/your-target

# 5) review: fix specs + plan increment + lessons back into your pack
scala-cli run modernize-review.sc -- --repo ~/services/your-target
```

Every phase is resumable (plans, per-program spec files, cached gate verdicts,
per-program vector files); rerunning skips what exists. Provider quota
exhaustion fails fast with the reset time (`LLM4ZIO_USAGE_WAIT=24h` waits it
out and auto-resumes).

## Captured vectors: upgrading the proof

Generated vectors prove **spec conformance** from day one. To upgrade to
**behavioural equivalence**, record real legacy runs into the vector JSONL
format (`docs/modernization/vectors/*.jsonl`, `"tier":"captured"` — see
`tools/cobol-capture.sc` for the worked GnuCOBOL example and the format) using
your existing parallel-run capture tooling. llm4zio never needs mainframe
access; the JSONL file is the hand-off.

## The evidence bundle you end with

| Artifact | What it proves |
| -------- | -------------- |
| `inventory.md` + `graph.json` + approved `wave-plan.md` | You know what you have and migrate in dependency order, with a cost projection from measured runs |
| Spec pack + `gate/*.json` verdicts | Every paragraph/step covered; specs judged complete, faithful, testable — per program |
| `provenance.json` | What crossed the wall, hashed (`shasum`-checkable), who approved, which models ran |
| `equivalence.md` | Per spec rule: proven (generated / captured, never summed), failing, or unexercised |
| `.llm4zio/costs.jsonl` | Every token and estimated cost, per stage × agent × model, correlated by run label |

That bundle — not a slide deck — is what goes to the risk committee.
