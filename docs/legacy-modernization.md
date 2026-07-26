# Legacy modernization flows

Five pack-parameterized flows that take a legacy estate (mainframe COBOL/JCL + DB2,
old J2EE/JSP, IBM ACE message flows) to a modern target (Spring Boot on a PaaS,
Next.js SPA) through judged reverse-engineering, a human approval gate, gated
implementation behind an **enforced clean-room wall**, a per-rule **equivalence
proof**, and a review that feeds lessons back into future runs.

```
modernize-extract.sc ──▶ (human approves) ──▶ modernize-seed.sc ──▶ modernize-implement.sc
     legacy repo                                  target repo            target repo
                                                                              │
                              ┌── fix specs + plan ◀── modernize-verify.sc ◀──┘
                              ▼                            target repo
                     modernize-implement.sc ──▶ … ──▶ modernize-review.sc
                                                          target repo
                                                     lessons → the pack
```

The clean-room split is enforced, not just practiced: only extract and its gate ever
touch legacy source; implement/verify/review refuse to start if anything matching the
pack's `sources:` regex is inside the target workspace (`flow.Wall`), and
`docs/modernization/provenance.json` — written by seed, extended by verify — records
what crossed the wall and on whose authority: spec-pack file hashes (plain sha256,
`shasum`-checkable), gate verdict digests, the approver (`LLM4ZIO_APPROVER`), and the
equivalence report hash. That file is the evidence chain a risk committee files.

Try it end-to-end against the synthetic estate:

```bash
examples/seed.sh modernize --local   # --local pins the flow scripts to a fresh sbt publishLocal
```

Four packs ship with the examples — pick the pair with `LLM4ZIO_PACK` before
seeding (default `cobol-springboot`):

| Pack | Source | Target |
| ---- | ------ | ------ |
| `cobol-springboot` | COBOL/JCL batch + DB2 | Spring Boot service |
| `jsp-nextjs` | J2EE servlets + JSP | client-only Next.js SPA (S3 static export) |
| `jsp-bff-nextjs` | J2EE servlets + JSP | Spring Boot BFF + Next.js SPA monorepo |
| `ace-integration` | IBM ACE msgflow + ESQL | Spring Boot integration service |

## The pack: modernization knowledge as data

Everything estate-specific lives in a **modernization pack** (`flow.Pack`) — a plain
versioned directory, not code:

```
packs/cobol-springboot/
  pack.md          # manifest: source kind, scaffold ref, sources/programs regexes,
                   #   gate commands, judge rubrics, coverage-unit regexes, seed destinations
  prompts/*.md     # analysis / spec / bdd / plan / implement / review templates
  reviewers/*.md   # pack-specific reviewer lenses (same format as the shipped roster)
  lessons.md       # accumulated lessons — appended by review, injected by extract/implement
```

The five flows never change per estate; supporting a new pair means authoring a
new pack — the shipped four packs are worked examples. Packs are git-versioned, so a
bank's modernization know-how is reviewable, diffable, and grows run over run.
`Pack.load` is the library entry
([Pack.scala](../modules/llm4zio-flow/src/main/scala/llm4zio/flow/Pack.scala)).

## Phase 1 — extract (rooted at the legacy repo)

`modernize-extract.sc --repo <legacy>` sends a coder-as-analyst through the estate
and produces a spec pack under `docs/modernization/`: one behavioural spec per
program, BDD `.feature` files, a traceability matrix, a data/interface mapping,
and (once the gate passes) a proposed task plan in the canonical `Plan.render`
Markdown.

Extraction is **per program**, so real estates are resumable and bounded:

- the pack's `programs:` regex (falling back to `sources:`) enumerates the
  spec-worthy files (`SpecChecks.matchingFiles`) — programs and jobs, not
  copybooks;
- each program gets its own analyst turn, its own commit, and its own artifacts:
  `specs/<NAME>.md`, `features/<name>.feature`, `traceability/<NAME>.md`,
  `mapping/<NAME>.md`. A rerun skips every program whose spec exists — a flaky
  stream or crash costs one program's turn, not the estate. Delete a
  `specs/<NAME>.md` to re-extract just that program;
- `traceability.md` and `mapping.md` are **generated** from the per-program
  fragments before every gate round — fix findings in the fragments, never in
  the indexes;
- every analyst turn runs under a turn limit (gemini `--turn-limit`, exposed as
  `withTurnLimit`), so a wedged headless agent cannot burn quota on no-op
  commands; if the limit trips after the spec landed, the flow keeps the work.

The gate is layered, and **nothing auto-approves**:

| Layer | Mechanism | What it proves |
| ----- | --------- | -------------- |
| 1 — deterministic | `SpecChecks.coverage`: every COBOL paragraph / JCL step enumerated from the source (pack regexes) appears in the traceability matrix; `SpecChecks.features`: the Gherkin parses and has steps; traceability + mapping indexes exist | "How do you KNOW every paragraph was covered" |
| 2 — LLM-as-a-Judge | `Judge` on `reasoning` scores completeness / faithfulness / testability against the pack's rubrics, full marks required — **per program**, each judge call bounded to one program's source + spec (`LLM4ZIO_JUDGE_SOURCES_LIMIT` caps it, default 400k chars; an empty response retries at half, then quarter context) | Nothing invented, nothing vague — and no estate-sized prompt to blow the model context |
| 3 — human | `ApprovalGate` draft marker in `docs/modernization/README.md` | A person signs off before any target repo is touched |

The gate is **resumable per program**, like extraction: every verdict persists
in `docs/modernization/gate/<NAME>.json` (`flow.ReviewCache`), fingerprinted
over the source + spec + feature + rubric it judged. A matching fingerprint
reuses the stored verdict with no LLM call, so a crash, quota death, or
auto-resume re-entry re-judges only the programs whose files actually changed —
and an untouched dirty program keeps its stored findings instead of a fresh
roll of the dice. The verdict files commit with the draft (resume survives
machines); delete `gate/` to force a full re-judge.

`fixLoop` feeds failures back to the analyst (3 rounds) — one bounded fix turn
per sub-bar program (own commit), plus a single residual turn for estate-wide
findings (coverage gaps, malformed features, missing indexes). Fixing a
program's files changes its fingerprint, so exactly the programs touched get
re-judged next round. A still-dirty pack is committed as an explicit **draft**
and the flow halts for human triage.

## Phase 2 — seed (rooted at the target repo)

`modernize-seed.sc --repo <target>` (with `LLM4ZIO_LEGACY_REPO` pointing at the
legacy repo) is deliberately deterministic — no LLM calls. It refuses to run until
the approval marker is flipped, scaffolds an empty target from the pack's scaffold
ref (bank-provided starters slot in here), places specs and features where the
pack's `specs-dir` / `features-dir` say, and materializes the plan at
`docs/modernization/plan.md` — re-parsed as a hard validation, committed so
progress is auditable in git history.

## Phase 3 — implement (rooted at the target repo)

`modernize-implement.sc` resumes the committed plan with `PlanStore` and runs the
`sdd.sc` harness pack-parameterized: task 1 encodes the seeded scenarios as
acceptance tests and must come out **RED**; every later task loops
`reviewAndFixLoop` with the pack's `test` command as the lint gate plus
`Reviewers.minimal` and the pack's own lenses (e.g. `cobol-fidelity`:
BigDecimal/HALF_UP, validation order, reason codes). After the final `verify`
gate, an LLM-as-a-Judge scores the whole branch diff against the committed specs
(spec-compliance + scenario-coverage) with a bounded feedback round. Push + PR go
to Azure DevOps when configured, GitHub otherwise, and degrade to an Info event
when neither is.

## Phase 4 — verify (rooted at the target repo)

`modernize-verify.sc` proves the implementation equivalent to its specs — per rule,
with a deterministic diff, behind the wall (no legacy source in reach):

- **Vectors, generated-first.** For each spec'd program with no vector file yet,
  `reasoning` generates equivalence vectors from the spec + BDD scenarios (happy
  path, each boundary, each reject) into `docs/modernization/vectors/<PROGRAM>.jsonl`
  — resumable per program; delete a file to regenerate. The JSONL is the **capture
  interchange format**: a bank's own tooling drops recorded legacy runs into the same
  directory as `"tier":"captured"` vectors. *Generated vectors prove spec
  conformance; captured vectors prove behavioural equivalence* — the report keeps the
  two claims in separate columns and never sums them.
- **Replay, transport-blind.** Every vector is piped (JSON on stdin) into the pack's
  `replay:` command, which drives the target however the pack decides (the
  cobol-springboot scaffold ships `scripts/replay.sh` → a `ReplayHarness` the
  implement phase builds as part of its contract) and prints the observations as a
  JSON array.
- **Diff, deterministic.** `Equiv.diff` compares expected vs actual observations
  under the pack's `## Equivalence` policy (`ordering: ordered|unordered`,
  `ignore:` fields such as timestamps). No LLM decides equivalence.
- **Report, per rule.** `docs/modernization/equivalence.md` speaks the auditor's
  language: every rule from the spec pack's `rules.txt` (enumerated deterministically
  by extract — the target side never could, that would need legacy source) with
  proven/FAILING/UNEXERCISED status. Its hash lands in provenance.json.
- **Triage, bounded.** Mismatches are distilled into fix specs + appended plan tasks
  (rerun `modernize-implement.sc`), then the flow **halts non-green** so a pipeline
  can gate on it.

### Captured vectors from the real COBOL (the golden adapter)

For the synthetic estate the captured tier is not hypothetical:
`tools/cobol-capture.sc` executes the actual 1988 `ACCTXFR` batch under
GnuCOBOL (`brew install gnucobol`) — each `EXEC SQL` block rewritten into a
call to the `DBSHIM.cbl` stand-ins, zoned-decimal overpunch and record-format
mainframe idioms preserved — and records what the program really does as
captured-tier vectors:

```bash
cd examples
scala-cli run tools/cobol-capture.sc -- \
  --cobol fixtures/legacy-bank/cobol \
  --probes fixtures/legacy-bank/probes/ACCTXFR.probes.jsonl \
  --out <target>/docs/modernization/vectors/ACCTXFR.captured.jsonl
```

The shipped probe set covers the fee schedule, the same-customer waiver, the
overdraft floor, the daily limit, and the reject paths. For a real estate the
same JSONL contract is the hand-off point: the bank's own capture tooling
(recorded mainframe runs) writes the vectors; llm4zio never needs mainframe
access.

### The sabotage demo

The 30-second trust argument, after a green run: break a rule in the generated Java
(e.g. change the transfer fee rounding), rerun `modernize-verify.sc`, and watch the
exact spec rule go **FAILING** with the field-level diff (`fee expected 2.50, actual
2.60`), a fix spec filed, and a plan task appended. Green checkmarks demo a happy
path; a red diff that names the violated rule demos the gate.

## Phase 5 — review (rooted at the target repo)

`modernize-review.sc` finds and routes — it does not fix. The full reviewer roster
plus the pack lenses read the branch diff against the spec pack; `reasoning`
distills the findings three ways:

- **Fix specs** (spec violations) → `docs/specs/fixes/*.md` + appended as new plan
  tasks — rerunning `modernize-implement.sc` picks them up. That is the iteration
  loop the gates promise.
- **Improvements** (compliant follow-ups) → fix-spec documents only.
- **Lessons** (generalizable) → `Pack.appendLesson` into the pack's `lessons.md`.
  Extract and implement inject lessons into their briefs, so the next run of the
  same pack starts smarter. Commit the pack change like any reviewed edit.

## Seats and models

All-gemini defaults, per role: `reasoning` and both judges on `gemini-2.5-pro`
(the gates that halt flows run on the strong model), reviewer lenses on
`gemini-3.5-flash`, the coder on the gemini CLI. Edit the `val`s at the top of
each script, or set `LLM4ZIO_CODER=claude|codex|gemini|pi` to run a whole flow on
one provider. Cross-provider judging (e.g. Claude judging Gemini's extraction) is
one config swap.

llm4zio passes the configured model to the CLI (`gemini -m <model>`), but the CLI
can still route or fall back to a different model (its own settings, `auto`
routing, tier fallbacks); the run logs a WARN when the session's serving model
differs from the requested one — that's the first thing to check when a flash-pinned
flow is unexpectedly burning pro quota.

### Provider quota exhaustion

When gemini exhausts a model's quota mid-flow it often reports the reason
(`TerminalQuotaError: … Your quota will reset after 21h1m53s`) only on the CLI's
stderr while stdout carries a catch-all error or nothing. llm4zio classifies that
stderr diagnostic into a typed usage-limit error with the concrete reset time, so
the flow **fails fast with the reason instead of burning its retry budget**. Two
ways out:

- `LLM4ZIO_USAGE_WAIT=24h` (or `on`) — sleep until the reported reset, then
  auto-resume the flow (extract is resumable: the committed draft pack is reused).
  While sleeping, a "⏳ still waiting" heartbeat is emitted every 5 minutes
  (`UsageLimitPolicy.heartbeat`) so the run is visibly waiting, not stuck.
- Point the seats at a model with remaining quota (e.g. swap the `ProModel` val
  to `gemini-2.5-flash`) and rerun.

## Token & cost traceability

Every flow run appends one structured record to an append-only ledger —
`<workspace>/.llm4zio/[<repo-id>/]costs.jsonl` — carrying the run's tokens and
estimated cost as (stage × agent × model) cells plus totals, the run id (joins the
trace file), the repo, a 120-char prompt head + hash, and the pricing-table
version. The console footer renders and forgets; the ledger is never pruned.

Set `LLM4ZIO_RUN_LABEL` (e.g. `meridian-acctxfr`) before running the phases to
stamp a correlation key: five extract iterations plus seed/implement/review then
aggregate as one effort. Report with:

```bash
scala-cli run costs.sc -- meridian-acctxfr
```

which lists each run and rolls costs up per stage (the Gate's judge vs the
analyst, per model) — `flow.CostLedger` is the library API when you want the raw
records.

## Benchmarking the tools against the same task

`modernize-bench.sc` runs the WHOLE conversion (fixture copy → per-program
extract → gate → plan → seed → implement → verify → score) as one flow in a
fresh temp directory, under one tool per invocation
(`LLM4ZIO_CODER=gemini|claude|codex`), and appends one self-contained JSON line
per run to `bench-results.jsonl`. It is deliberately **not** the product
pipeline: there is no human approval gate, and internal gates never stop a run
— a dirty gate, a failed build, a dead provider are all *recorded* as metrics
(`outcome: failed-<phase>` keeps the partial row).

Per line (`flow.BenchRecord`, schema-versioned): provenance (models requested
AND served, examiner identity, tool + llm4zio versions, pack, fixture content
fingerprint, machine block), per-phase duration / tokens / est. cost /
robustness counters (flaky + transient retries, auto-resumes, turn-limit trips,
empty-response shrinks — summed as "self-healing actions"), and a final quality
block: `mvn verify` outcome, test counts from surefire, traceability coverage
%, scenario counts, LOC, and judged scores. Internal loops run all-in-provider
(self-correction is part of the benchmark); the final scores are self-graded
unless `LLM4ZIO_BENCH_JUDGE=provider:model` pins one fixed examiner for every
run — only then are judged scores cross-comparable, and the report says so.

`bench-report.sc` (pure Scala, no LLM — same inputs, same bytes) aggregates any
set of `bench-results.jsonl` files, sections them by (pack, fixture
fingerprint) so different tasks are never merged, and renders `benchmark.md`:
best ✅ / worst ⚠️ per metric with the gap, median + spread over repeated runs,
failures, machines footnote (durations are compared across machines as-is —
read them with that in mind), and with `--project N` the linear per-program
extrapolation to an N-program estate:

```bash
cd examples
LLM4ZIO_CODER=gemini scala-cli run modernize-bench.sc
LLM4ZIO_CODER=claude scala-cli run modernize-bench.sc
LLM4ZIO_CODER=codex  scala-cli run modernize-bench.sc
scala-cli run bench-report.sc -- bench-results.jsonl --project 500
```

Model defaults: `gemini-2.5-pro` / `claude-opus-4-8` / `gpt-5.5`
(`LLM4ZIO_BENCH_MODEL` overrides); `LLM4ZIO_BENCH_RUNS=N` loops runs;
`LLM4ZIO_RUN_LABEL` correlates lines collected from different machines;
`LLM4ZIO_BENCH_PHASE_TIMEOUT` (minutes, default 60) bounds each phase's
wall clock so a provider that can neither finish nor fail still yields a
`failed-<phase>` row.

Runs are **resumable**: the bench prints its root at start; if a 4-hour run
dies, rerun with `LLM4ZIO_BENCH_DIR=<that root>` and everything already done —
extracted programs, cached gate verdicts, the plan, the seeded target,
completed implement tasks — is skipped. Resumed records are flagged `♻` in the
report (their time/tokens are incremental, not comparable with fresh runs).

Token accounting differs per CLI — claude reports prompt tokens net of cache
reads, codex reports the full prompt and no cache figure — so the report's
"Total tokens" includes cache reads, "Output tokens" is the one directly
cross-CLI-comparable figure (crowned), and "Cached reads" is informational.
Cost estimates price cache reads at 10% of the input rate.

## Azure DevOps

Optional and detected from the environment (`Ado.configFrom`): when
`SYSTEM_COLLECTIONURI`/`LLM4ZIO_ADO_*` + a PAT are present, seed creates one work
item per plan task (spec text as acceptance criteria), implement opens the PR in
Azure Repos, and review files a work item per fix spec. Absent config, each step
logs an Info event and moves on — the pipeline stays fully runnable offline. See
[azure-devops.md](azure-devops.md).

## The synthetic estate

`examples/fixtures/legacy-bank/` is a small-but-real fake bank ("Meridian
Savings") covering all three legacy shapes: `cobol/` (`ACCTXFR.cbl`, ~500 lines —
transfer validation chain, daily limits, tiered fees with a same-customer waiver,
overdraft floors, DB2 ledger/audit posting — plus `INTCALC.cbl` and a two-step JCL
job), `jsp/` (a Servlet 2.4-era account/transfer webapp with web-only rules like a
confirmation threshold, queueing transfers for the nightly batch), and `ace/` (a
payment-routing msgflow + ESQL with reject codes, a routing table, and regulatory
thresholds). The business rules live only in the code — extracting them is the
demo. `examples/fixtures/scaffolds/` holds the bank-provided-style target
scaffolds: `spring-boot-service`, `nextjs-spa` (tests run with `node --test`, no
install), and `spring-bff` (monorepo, gated by `scripts/test.sh`).

## Authoring a pack for your estate

1. Copy `packs/cobol-springboot/` and rewrite `pack.md`: `source`, the scaffold
   ref, your build/test/verify commands, judge rubrics in your estate's terms, and
   coverage regexes that enumerate YOUR units (JSP pages, ACE nodes, stored
   procedures…).
2. Rewrite the prompt templates in the estate's vocabulary; keep the structure
   (spec sections, traceability contract, boundary-value discipline).
3. Add lenses for the failure modes your reviewers actually catch.
4. Leave `lessons.md` empty — the review flow fills it.

The scripts pin the released llm4zio version in their headers (`flow.Pack`,
`flow.SpecChecks`, `AdoTool.createWorkItem` arrived in 3.13.0; the empty-response
retry, raw-output surfacing, and extract resumability in 3.13.1). To run against an
unreleased local build, use `examples/seed.sh modernize --local`.
