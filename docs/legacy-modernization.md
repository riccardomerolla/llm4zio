# Legacy modernization flows

Four pack-parameterized flows that take a legacy estate (mainframe COBOL/JCL + DB2,
old J2EE/JSP, IBM ACE message flows) to a modern target (Spring Boot on a PaaS,
Next.js SPA) through judged reverse-engineering, a human approval gate, gated
implementation, and a review that feeds lessons back into future runs.

```
modernize-extract.sc ──▶ (human approves) ──▶ modernize-seed.sc ──▶ modernize-implement.sc ──▶ modernize-review.sc
     legacy repo                                  target repo            target repo                 target repo
                                                                                ▲                        │
                                                                                └── fix specs + plan ─────┘
                                                                                    lessons → the pack
```

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
  pack.md          # manifest: source kind, scaffold ref, gate commands, judge rubrics,
                   #   coverage-unit regexes, seed destinations
  prompts/*.md     # analysis / spec / bdd / plan / implement / review templates
  reviewers/*.md   # pack-specific reviewer lenses (same format as the shipped roster)
  lessons.md       # accumulated lessons — appended by review, injected by extract/implement
```

The four flows never change per estate; supporting a new pair means authoring a
new pack — the shipped four are worked examples. Packs are git-versioned, so a
bank's modernization know-how is reviewable, diffable, and grows run over run.
`Pack.load` is the library entry
([Pack.scala](../modules/llm4zio-flow/src/main/scala/llm4zio/flow/Pack.scala)).

## Phase 1 — extract (rooted at the legacy repo)

`modernize-extract.sc --repo <legacy>` sends a coder-as-analyst through the estate
and produces a spec pack under `docs/modernization/`: one behavioural spec per
program, BDD `.feature` files, a traceability matrix, a data/interface mapping,
and (once the gate passes) a proposed task plan in the canonical `Plan.render`
Markdown.

The gate is layered, and **nothing auto-approves**:

| Layer | Mechanism | What it proves |
| ----- | --------- | -------------- |
| 1 — deterministic | `SpecChecks.coverage`: every COBOL paragraph / JCL step enumerated from the source (pack regexes) appears in the traceability matrix; `SpecChecks.features`: the Gherkin parses and has steps | "How do you KNOW every paragraph was covered" |
| 2 — LLM-as-a-Judge | `Judge` on `reasoning` scores completeness / faithfulness / testability against the pack's rubrics, full marks required | Nothing invented, nothing vague |
| 3 — human | `ApprovalGate` draft marker in `docs/modernization/README.md` | A person signs off before any target repo is touched |

`fixLoop` feeds failures back to the analyst (3 rounds); a still-dirty pack is
committed as an explicit **draft** and the flow halts for human triage.

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

## Phase 4 — review (rooted at the target repo)

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
