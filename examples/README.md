# Examples

Flat, orca-shaped flow scripts. Each is a single `.sc` file: `//> using dep` pins
llm4zio, `flow(args)` opens the flow, and the body is an ordinary ZIO
for-comprehension with `git`/`gh`/`coder`/`reasoning`/`userPrompt` available bare.
Documentation lives in each script's header comment.

| Script                    | What it shows                                                | Starter           |
| ------------------------- | ------------------------------------------------------------ | ----------------- |
| `implement.sc`            | Autonomous plan → implement → review loop                    | calculator-rs     |
| `implement-interactive.sc`| Planner asks clarifying questions first                      | calculator-rs-open|
| `implement-enhanced.sc`   | Plan self-review + shared codebase brief (`.reviewed/.briefed`) | calculator-rs  |
| `implement-enhanced-pr.sc`| Enhanced plan → branch → implement → push → open PR (needs a remote + `gh`) | calculator-rs |
| `implement-live.sc`       | Held, steerable claude session, streaming + ask_user over MCP | calculator-rs-open|
| `epic.sc`                 | Multi-task epic, full reviewer roster, doc update at the end | todo-java         |
| `issue-pr.sc`             | GitHub issue → assess → implement → PR                       | calculator-scala  |
| `issue-pr-bugfix.sc`      | Bug report → failing test → red CI → fix → PR                | calculator-scala  |
| `sdd.sc`                  | Spec → tests-first → implement → verify; per-role gemini models; mvn as the gate | todo-java |
| `pipeline.sc`             | Specify → design → acceptance → implement → verify; outside-in, one scenario per commit | todo-java |
| `reverse-engineer.sc`     | Read-only: discover → architecture → domain → ADRs → reverse-spec → review; docs an existing repo | todo-java |
| `local.sc`                | Fully local — reasoning on LM Studio, coding on pi (local model); no cloud/API key | calculator-rs |
| `local-claude.sc`         | Fully local — reasoning on LM Studio, coding on Claude Code routed to LM Studio; no cloud/API key | calculator-rs |
| `judge-gate.sc`           | LLM-as-a-Judge quality gate: a per-task score loop (correctness/scope/safety, bar = 2) replaces the review loop and gates each commit | calculator-rs |
| `judge-suite.sc`          | Offline LLM-as-a-Judge eval harness: scores a built-in retail-chatbot dataset, Layer 1 (`noPii`) + Layer 2 composed via `Evaluator.all`, each case judged 3× for variance | — (no starter) |
| `ado-spec.sc`             | Azure DevOps: card→Refine → draft spec onto the work item → Spec Review (needs ADO) | — |
| `ado-implement.sc`        | Azure DevOps: card→Approved → spec→tests→implement → PR linked to the work item (needs ADO) | — |
| `modernize-extract.sc`    | Legacy modernization 1/4: reverse-engineer the estate into a spec pack; SpecChecks + judge gate; halts for human approval | fixtures/legacy-bank |
| `modernize-seed.sc`       | Legacy modernization 2/4: approval-gated, deterministic seeding of the target repo (scaffold, specs, features, plan) | fixtures/scaffolds |
| `modernize-implement.sc`  | Legacy modernization 3/4: RED-gated tests-first per task, pack lenses + command gates, final spec-compliance judge, PR | — (seeded target) |
| `modernize-review.sc`     | Legacy modernization 4/4: roster review vs the spec pack → fix specs + plan increment + lessons appended to the PACK | — (seeded target) |

Flows can also be authored in **Java** and run the same way — see [`java/`](java/)
(the `llm4zio-java` facade: blocking, exception-based, one `JavaFlow` handle).

## Running one

```bash
examples/seed.sh implement          # seed a starter into a temp dir
examples/seed.sh implement --run    # seed + run
examples/seed.sh implement --local  # test against the in-tree build (sbt publishLocal)
```

Or by hand: copy a starter from `examples/starters/`, drop the script next to it,
`git init`, then

```bash
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

Backend: `LLM4ZIO_CODER=claude|codex|gemini` (default claude). No API key —
one CLI login is enough. The issue-pr flows additionally need `gh` authenticated
and a repo with a remote.

The `ado-*` examples target Azure DevOps instead of a local starter and are run by a
pipeline (or locally with `LLM4ZIO_ADO_*` env vars + a PAT) — see [docs/azure-devops.md](../docs/azure-devops.md).

The `modernize-*` examples are a four-phase pipeline over TWO repos (a legacy estate and a
target), parameterized by a **modernization pack** under [`packs/`](packs/) — four ship:
`cobol-springboot`, `jsp-nextjs`, `jsp-bff-nextjs`, `ace-integration` — and demoed against
the synthetic estate under [`fixtures/`](fixtures/): `examples/seed.sh modernize --local`
seeds both repos and prints the workflow (`LLM4ZIO_PACK` picks the pair) — see
[docs/legacy-modernization.md](../docs/legacy-modernization.md).
