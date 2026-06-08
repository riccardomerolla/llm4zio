# Examples

End-to-end llm4zio flows, each a single scala-cli script under [`plans/`](../plans/)
plus a seeder that drops a starter project next to it. The ZIO-native counterpart
to [orca's examples](https://github.com/VirtusLab/orca/tree/main/examples).

| Example | What it shows |
| ------- | ------------- |
| [01-simple](01-simple/) | Autonomous planning + coding for a small task: plan → per-task implement (claude CLI) → LLM review-and-fix → commit. Plan persists to `.llm4zio/plan-*.md`, so a re-run resumes. |
| [02-interactive](02-interactive/) | Same shape as 01, but the planner can ask clarifying questions on the terminal (`Interaction` / `TerminalInteraction`) before planning. For open-ended prompts. |
| [03-bugfix](03-bugfix/) | Issue-driven Scala bugfix (touches GitHub): read issue → triage → failing test on a branch → PR → wait for CI red → fix → update PR. No `--run` (needs a real repo + issue). |
| [04-epic](04-epic/) | A multi-task epic in a resumable on-disk file, with **cross-agent review** (claude implements; the claude reasoner + codex review in parallel), a final doc-update stage, and epic-file cleanup. |
| [05-interactive-live](05-interactive-live/) | **Interactive live coding**: each task drives a held, steerable `claude` session — streaming its work, asking you questions mid-task (`ask_user`), and gating tool calls through an approval policy, all over an in-process MCP server. claude-only. |
| [06-issue-pr](06-issue-pr/) | Autonomous GitHub-issue → PR (touches GitHub): read issue → skeptically assess (`Planner.assessThenPlan`; a "blocked" verdict comments the reason and stops) → branch → per-task implement + review → push → summarise the diff → open a PR. No `--run` (needs a real repo + issue). |
| [07-enhanced](07-enhanced/) | Like 01, **enhanced**: the planner self-reviews its draft (correctness / completeness / simplicity / conciseness) and writes a one-off **codebase brief** prepended to every task (`Planner.reviewed` + `briefed`, persisted in the plan file), plus format-after-every-edit (`LLM4ZIO_FORMAT`) and optional lint (`LLM4ZIO_LINT`). |

All six orca examples are ported (implement → 01 & 07, interactive → 02, issue-pr-bugfix
→ 03, epic → 04, issue-pr → 06, implement-enhanced → 07); 05 adds llm4zio's interactive
runtime. See `.claude/plans/orca-examples-parity.md`.

## Prerequisites

- **JDK 21+** and [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` CLI logged in (the coder backend edits files in the repo).
- The chosen agent CLI logged in (claude/codex/gemini) — no API key needed.
- The starter's toolchain (01-simple ships a Rust crate, so `cargo` on PATH).

## Seeding and running

Each example has a `create-test-project.sh` that copies its starter + the flow
script into a temp dir and inits git:

```bash
./examples/01-simple/create-test-project.sh            # seed only, prints next step
./examples/01-simple/create-test-project.sh --run      # seed, then run the flow
./examples/01-simple/create-test-project.sh /tmp/demo  # explicit destination
```

### Running against a local build (`--local`)

llm4zio isn't on Maven Central yet, so until it is, pass `--local` to resolve the
flow script against your in-tree build:

```bash
./examples/01-simple/create-test-project.sh --local --run
```

`--local` runs `sbt publishLocal`, discovers the published version, and rewrites
the script's `//> using dep` to pin it with `//> using repository ivy2Local`.
