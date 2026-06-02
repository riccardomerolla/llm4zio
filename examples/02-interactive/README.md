# Example 02 — interactive planning

Same shape as [01-simple](../01-simple/), but the planner can ask clarifying
questions before producing a plan. Use it for open-ended prompts where the
planner shouldn't guess.

## What it does

1. **Plan interactively** — `Planner.interactive(ctx.reasoning, prompt, TerminalInteraction.live)`
   loops: the model either asks a question (printed on the terminal; your answer
   read from stdin) or proposes the `Plan`.
2. **Then, exactly as 01-simple**: branch → per-task implement (claude CLI) →
   `reviewAndFixLoop` → commit, resumable via `.llm4zio/plan-*.md`.

The flow script is [`plans/implement-interactive.scala`](../../plans/implement-interactive.scala).
Interactivity is modeled by the `Interaction` trait (the ZIO-native stand-in for
orca's `ask_user` MCP tool); the runner ships `TerminalInteraction` (stdin).

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/), `cargo`.
- `claude` logged in — no API key needed (reasoning runs over the claude CLI).

## Run

```bash
./examples/02-interactive/create-test-project.sh --local --run
# or step by step:
./examples/02-interactive/create-test-project.sh --local
cd /tmp/llm4zio-02-interactive-…
scala-cli run implement-interactive.scala -- "Make the calculator crate more useful"
```

The planner will ask you a clarifying question on the terminal before planning.
