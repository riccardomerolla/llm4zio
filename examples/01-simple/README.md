# Example 01 — simple plan + code flow (autonomous planning)

The minimum-viable llm4zio flow: the reasoning model plans in one structured
turn, then each task is implemented by the coder, reviewed, and committed on a
single epic branch. The plan persists to `.llm4zio/plan-<epicId>.md`, so a crash
or re-run resumes from the first incomplete task.

## What it does

1. **Plan.** `Planner.from(ctx.reasoning, prompt)` returns a structured
   `Plan(epicId, tasks)` via the API connector's structured output.
2. **Branch.** `stage("branch")(ctx.git.createBranch(plan.epicId))`.
3. **For each task** (`implementTaskLoop`, resumable):
   - `coderChat.ask(task.description)` — the `claude` CLI edits files in the repo.
   - `reviewAndFixLoop(ctx.reasoning, coderChat, task.title, ctx.git.diff)` —
     the reasoning model reviews the diff; the coder fixes; repeat until clean.
   - `ctx.git.commitAll(...)` — commit the task.

The flow script is [`plans/implement.sc`](../../plans/implement.sc).

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/), `cargo`.
- `claude` logged in; a reasoning API key in the environment (`ANTHROPIC_API_KEY`).

## Run

```bash
./examples/01-simple/create-test-project.sh --local --run
# or, step by step:
./examples/01-simple/create-test-project.sh --local
cd /tmp/llm4zio-01-simple-…
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

### Choosing the coder backend

The same flow runs with any of the three CLI coding agents — set `LLM4ZIO_CODER`
(default `claude`); each runs rooted in the repo with its headless edit-approval:

```bash
LLM4ZIO_CODER=claude scala-cli run implement.sc -- "Add a multiply function"   # claude --print --permission-mode acceptEdits
LLM4ZIO_CODER=codex  scala-cli run implement.sc -- "Add a multiply function"   # codex exec --full-auto
LLM4ZIO_CODER=gemini scala-cli run implement.sc -- "Add a multiply function"   # gemini -p -y (auto-approve)
```

- `claude` → needs `claude` logged in.
- `codex` → needs `codex` logged in.
- `gemini` → needs `gemini` logged in (the CLI auto-approves edits via its built-in `-y`).

Reasoning (planning + review) always runs over the API connector
(`ANTHROPIC_API_KEY`), regardless of the coder.

Edit [`test-project/`](test-project/) for a different starter, or
[`plans/implement.sc`](../../plans/implement.sc) for a different flow.
